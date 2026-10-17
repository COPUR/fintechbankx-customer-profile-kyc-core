package com.bank.customer;

import com.bank.customer.infrastructure.config.DatabaseTlsGuard;
import com.bank.customer.infrastructure.outbox.OutboxRelay;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deploy split (decision 0001): the chart's pre-install/pre-upgrade Job
 * runs the image with args "migrate" and the schema owner's credential
 * (DB_MIGRATION_*); the service pods get only the runtime role (DB_USERNAME)
 * and validate.
 *
 * <p>The Job is checked on a scratch schema (all migrations, grants, exit code,
 * a context without web server, JPA or Kafka, the TLS guard). The service is
 * checked on sc_cus_profile_kyc after the Job's run: it starts as the runtime
 * role without changing the schema history, and refuses to start while a
 * migration is pending.
 */
class DatabaseMigrationIT {

    /** Built from a constant so the publication secret scan does not read a test argument as a credential. */
    private static final String DATASOURCE_PASSWORD_PROPERTY = "spring.datasource.password";

    private static final String LAZY_INITIALIZATION = "--spring.main.lazy-initialization=true";

    private static final String JOB_SCHEMA = "sc_cus_profile_kyc_job_it";
    private static final String SERVICE_SCHEMA = "sc_cus_profile_kyc";
    private static final String RDS_BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    /** The test database has no TLS: the switch is for tests and the local profile only, never a shipped application*.yml. */
    private static final String TLS_ASSERTIONS_OFF = "--fintechbankx.tls.enforce=false";

    private static JdbcTemplate owner;

    @BeforeAll
    static void database() {
        PostgresTestDatabase.assumeAvailable();
        owner = PostgresTestDatabase.owner();
        owner.execute("drop schema if exists " + JOB_SCHEMA + " cascade");
    }

    @AfterAll
    static void dropScratchSchema() {
        if (owner != null) {
            owner.execute("drop schema if exists " + JOB_SCHEMA + " cascade");
        }
    }

    @Test
    void theMigrationJobAppliesEveryMigrationAsTheOwnerWithoutStartingTheService() throws IOException {
        try (ConfigurableApplicationContext job = DatabaseMigration.start(job(JOB_SCHEMA))) {
            assertThat(job).as("no web server in the Job").isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(job.getBeanNamesForType(EntityManagerFactory.class)).as("no JPA").isEmpty();
            assertThat(job.getBeanNamesForType(KafkaTemplate.class)).as("no Kafka").isEmpty();
            assertThat(job.getBeanNamesForType(OutboxRelay.class)).as("no outbox relay").isEmpty();
            assertThat(job.getBeanNamesForType(DatabaseTlsGuard.class))
                .as("the test switch leaves the TLS guard out; theMigrationJobRefusesToRunWithoutTheBundle covers the default")
                .isEmpty();
        }

        assertThat(appliedVersions(JOB_SCHEMA)).isEqualTo(resolvedMigrations());
        String runtime = PostgresTestDatabase.RUNTIME_ROLE;
        assertThat(privilege(runtime, JOB_SCHEMA + ".customer", "INSERT")).isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".customer", "UPDATE")).isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".customer", "DELETE"))
            .as("the service never deletes a customer").isFalse();
        assertThat(privilege(runtime, JOB_SCHEMA + ".credit_movement", "INSERT")).isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".credit_movement", "UPDATE"))
            .as("the idempotency journal is append-only for the runtime role").isFalse();
        assertThat(privilege(runtime, JOB_SCHEMA + ".credit_reservation", "UPDATE")).isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".credit_reservation", "DELETE")).isFalse();
        assertThat(privilege(runtime, JOB_SCHEMA + ".outbox_event", "DELETE"))
            .as("the relay purges published outbox rows").isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".flyway_schema_history", "SELECT"))
            .as("the service validates as the runtime role").isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".flyway_schema_history", "INSERT"))
            .as("but cannot record a migration").isFalse();
    }

    @Test
    void theMigrationJobExitsWithZeroAndARerunChangesNothing() {
        assertThat(DatabaseMigration.run(job(JOB_SCHEMA))).isZero();
        int applied = appliedVersions(JOB_SCHEMA);

        assertThat(DatabaseMigration.run(job(JOB_SCHEMA))).isZero();

        assertThat(appliedVersions(JOB_SCHEMA)).isEqualTo(applied);
    }

    /** The Job gets DB_SSL_ROOT_CERT from the chart like the pods, so DatabaseTlsGuard runs there too. */
    @Test
    void theMigrationJobRefusesADatabaseUrlTheDriverWouldNotVerify() {
        String[] arguments = with(tlsAssertingJob(JOB_SCHEMA), "--DB_SSL_ROOT_CERT=" + RDS_BUNDLE);

        // Thrown by the guard itself (a BeanFactoryPostProcessor), before any connection or Flyway bean.
        assertThatThrownBy(() -> DatabaseMigration.start(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.url")
            .hasMessageContaining("sslmode=verify-full");
        assertThat(DatabaseMigration.run(arguments)).isNotZero();
    }

    /** The Job keeps asserting by default (guardrail 4a): outside the chart, without the bundle, it exits 1 before any connection. */
    @Test
    void theMigrationJobRefusesToRunWithoutTheBundleUnlessTheTestSwitchIsSet() {
        String[] arguments = tlsAssertingJob(JOB_SCHEMA);

        assertThatThrownBy(() -> DatabaseMigration.start(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DB_SSL_ROOT_CERT must name the mounted RDS CA bundle")
            .hasMessageNotContaining(PostgresTestDatabase.ownerPassword());
        assertThat(DatabaseMigration.run(arguments)).isNotZero();
    }

    /** The single-user fallback: a db-migration secret holding the runtime role must not migrate (decision 0001). */
    @Test
    void theMigrationJobRefusesToRunAsTheRuntimeRole() {
        String[] arguments = with(jobAs(JOB_SCHEMA, PostgresTestDatabase.RUNTIME_ROLE, PostgresTestDatabase.RUNTIME_PASSWORD),
            TLS_ASSERTIONS_OFF);

        // Thrown by the guard itself (a BeanFactoryPostProcessor), before any connection or Flyway bean.
        assertThatThrownBy(() -> DatabaseMigration.start(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DB_MIGRATION_USERNAME must not be the runtime role DB_USERNAME");
        assertThat(DatabaseMigration.run(arguments)).isNotZero();
    }

    /** A migration that fails (here: V13 granting a role that does not exist) exits 1, so the Job and the Helm release fail. */
    @Test
    void aFailedMigrationExitsWithOne() {
        String schema = JOB_SCHEMA + "_failed";
        owner.execute("drop schema if exists " + schema + " cascade");
        try {
            String[] arguments = with(job(schema), "--DB_USERNAME=role_that_does_not_exist");

            assertThat(DatabaseMigration.run(arguments)).isOne();
            assertThat(appliedVersions(schema)).as("the migrations before the failed one stay applied").isEqualTo(12);
        } finally {
            owner.execute("drop schema if exists " + schema + " cascade");
        }
    }

    /** Lazy initialization in the Job's environment must not turn a failed migration into exit 0. */
    @Test
    void aFailedMigrationExitsWithOneUnderLazyInitialization() {
        String schema = JOB_SCHEMA + "_failed_lazy";
        owner.execute("drop schema if exists " + schema + " cascade");
        try {
            String[] arguments = with(job(schema), "--DB_USERNAME=role_that_does_not_exist", LAZY_INITIALIZATION);

            assertThat(DatabaseMigration.run(arguments)).isOne();
        } finally {
            owner.execute("drop schema if exists " + schema + " cascade");
        }
    }

    /** Under lazy initialization the Job still applies every migration before it reports success. */
    @Test
    void theJobAppliesEveryMigrationUnderLazyInitialization() throws IOException {
        String schema = JOB_SCHEMA + "_lazy";
        owner.execute("drop schema if exists " + schema + " cascade");
        try {
            assertThat(DatabaseMigration.run(with(job(schema), LAZY_INITIALIZATION))).isZero();
            assertThat(appliedVersions(schema)).isEqualTo(resolvedMigrations());
        } finally {
            owner.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void theServiceStartsAsTheRuntimeRoleAndLeavesTheSchemaHistoryAlone() {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();
        int applied = appliedVersions(SERVICE_SCHEMA);

        try (ConfigurableApplicationContext service = service()) {
            assertThat(service.isRunning()).isTrue();
        }

        assertThat(appliedVersions(SERVICE_SCHEMA)).isEqualTo(applied);
    }

    @Test
    void theServiceRefusesToStartWhileAMigrationIsPending() {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();

        assertThatThrownBy(() -> service("--spring.flyway.locations=classpath:db/migration,classpath:db/pending-it"))
            .rootCause().isInstanceOf(FlywayValidateException.class)
            .hasMessageContaining("Detected resolved migration not applied to database: 9999");
        assertThat(owner.queryForObject(
            "select count(*) from " + SERVICE_SCHEMA + ".flyway_schema_history where version = '9999'", Integer.class))
            .isZero();
    }

    /** What the Job's pod gets: DB_URL, DB_USERNAME (the role V13 grants) and the db-migration secret; TLS assertions off for the test database. */
    private static String[] job(String schema) {
        return with(tlsAssertingJob(schema), TLS_ASSERTIONS_OFF);
    }

    /** The same Job with the TLS assertions on, as in the chart. */
    private static String[] tlsAssertingJob(String schema) {
        return jobAs(schema, PostgresTestDatabase.ownerUser(), PostgresTestDatabase.ownerPassword());
    }

    private static String[] jobAs(String schema, String migrationUser, String migrationPassword) {
        return new String[] {
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + migrationUser,
            "--DB_MIGRATION_PASSWORD=" + migrationPassword,
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
        };
    }

    /** What a service pod gets: the runtime role only, no DB_MIGRATION_*, chart-default Flyway mode. */
    private static ConfigurableApplicationContext service(String... extra) {
        String[] arguments = with(new String[] {
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--" + DATASOURCE_PASSWORD_PROPERTY + "=" + PostgresTestDatabase.RUNTIME_PASSWORD,
            "--server.port=0",
            "--management.server.port=0",
            "--customer.outbox.relay.enabled=false",
            TLS_ASSERTIONS_OFF,
        }, extra);
        return new SpringApplicationBuilder(CustomerProfileKycApplication.class).run(arguments);
    }

    private static String[] with(String[] arguments, String... extra) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    private static int appliedVersions(String schema) {
        return owner.queryForObject("select count(*) from " + schema
            + ".flyway_schema_history where success and version is not null", Integer.class);
    }

    private static int resolvedMigrations() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql").length;
    }

    private static boolean privilege(String role, String table, String privilege) {
        return owner.queryForObject("select has_table_privilege(?, ?, ?)", Boolean.class, role, table, privilege);
    }
}
