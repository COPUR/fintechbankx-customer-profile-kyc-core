package com.bank.customer;

import com.bank.customer.infrastructure.config.DatabaseTlsGuardConfiguration;
import org.flywaydb.core.Flyway;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.Arrays;

/**
 * Migrate-only mode of the service image, run by the chart's pre-install and
 * pre-upgrade Job with the argument {@value #COMMAND}: Flyway migrates
 * sc_cus_profile_kyc as the schema owner (DB_MIGRATION_USERNAME / _PASSWORD from
 * the db-migration secret) through the same verified DB_URL, then the process
 * exits. Exit code 0 means every migration is applied; anything else fails the
 * Job and with it the Helm install or upgrade, before any pod of the new
 * release starts (decision 0001).
 *
 * <p>The context holds only the datasource, Flyway and the DatabaseTlsGuard
 * ({@link DatabaseTlsGuardConfiguration}, on by default: the Job sets
 * DB_SSL_ROOT_CERT in the chart, and only the local profile or the test
 * switch fintechbankx.tls.enforce=false turn the guard off): no web server,
 * JPA, Kafka, security, identity client or outbox relay. It reads the same application.yml with the
 * {@value #PROFILE} profile, which sets customer.database.flyway=migrate
 * ({@link FlywayStartupConfiguration}).
 *
 * <p>Deliberately not a {@code @Configuration}: component scanning of the
 * service must not pick it up.
 */
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@Import({FlywayStartupConfiguration.class, DatabaseTlsGuardConfiguration.class})
public final class DatabaseMigration {

    static final String COMMAND = "migrate";
    static final String PROFILE = "db-migrate";

    private DatabaseMigration() {
    }

    /** The Job passes {@value #COMMAND} as the first argument; the service is started without it. */
    static boolean isRequested(String[] args) {
        return args.length > 0 && COMMAND.equals(args[0]);
    }

    /** The arguments after {@value #COMMAND}, passed on to Spring. */
    static String[] arguments(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    /** Migrates and returns the process exit code: 0 when every migration is applied, 1 otherwise. */
    static int run(String... args) {
        try {
            return SpringApplication.exit(start(args));
        } catch (RuntimeException e) {
            // SpringApplication has already logged the failure with its cause.
            return 1;
        }
    }

    /**
     * Starts the migrate-only context; Flyway has run when this returns.
     *
     * <p>Success does not rest on bean-time work alone: spring.main.lazy-initialization
     * would leave the Flyway initializer uncreated and the schema unmigrated. So the
     * initializer is fetched explicitly (a no-op when it already ran), and the context
     * is refused while any migration is still pending.
     */
    static ConfigurableApplicationContext start(String... args) {
        SpringApplication application = new SpringApplication(DatabaseMigration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(PROFILE);
        application.setBannerMode(Banner.Mode.OFF);
        ConfigurableApplicationContext context = application.run(args);
        try {
            context.getBean(FlywayMigrationInitializer.class);
            int pending = context.getBean(Flyway.class).info().pending().length;
            if (pending > 0) {
                throw new IllegalStateException(pending + " migration(s) still pending after the migrate-only run");
            }
            return context;
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }
}
