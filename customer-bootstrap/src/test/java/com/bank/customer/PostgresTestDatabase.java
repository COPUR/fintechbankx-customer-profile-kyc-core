package com.bank.customer;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL
 * (a service container in required-gates.yml); locally either set TEST_DB_URL
 * or have Docker running for Testcontainers.
 *
 * <p>Runs the service as in production: Flyway as the migration owner (the
 * database's test user, through DB_MIGRATION_USERNAME / _PASSWORD) and the
 * application as a separate runtime role (DB_USERNAME) that has only the
 * grants the migrations give it. The owner needs CREATEROLE to create that
 * role (the CI service container's user is a superuser). The infrastructure
 * module's tests on the same schema use the same role name as their Flyway
 * placeholder, so the grants are the same whichever module migrates first.
 */
final class PostgresTestDatabase {

    static final String RUNTIME_ROLE = "customer_runtime_it";
    static final String RUNTIME_PASSWORD = "customer_runtime_it";

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    private PostgresTestDatabase() {
    }

    /**
     * Call from a static @BeforeAll. Without a database the class is skipped
     * locally, but fails when REQUIRE_TEST_DB=true (set in CI), so a missing
     * database can never turn the integration tests into a silent pass.
     */
    static void assumeAvailable() {
        boolean available = hasExternalDatabase() || DockerClientFactory.instance().isDockerAvailable();
        if (!available && "true".equalsIgnoreCase(System.getenv("REQUIRE_TEST_DB"))) {
            throw new IllegalStateException("REQUIRE_TEST_DB=true but neither TEST_DB_URL nor Docker is available");
        }
        Assumptions.assumeTrue(available, "Set TEST_DB_URL or start Docker to run PostgreSQL integration tests");
    }

    private static boolean hasExternalDatabase() {
        String value = System.getenv("TEST_DB_URL");
        return value != null && !value.isBlank();
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", () -> url);
        // Runtime role: what the pods connect as.
        registry.add("DB_USERNAME", () -> RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        // Migration owner: what Flyway connects as.
        registry.add("DB_MIGRATION_USERNAME", () -> ownerUser);
        registry.add("DB_MIGRATION_PASSWORD", () -> ownerPassword);
    }

    static synchronized String url() {
        start();
        return url;
    }

    static synchronized String ownerUser() {
        start();
        return ownerUser;
    }

    static synchronized String ownerPassword() {
        start();
        return ownerPassword;
    }

    /** The migration owner's connection, for test set-up the runtime role may not do (DELETE on customers, DDL). */
    static synchronized DataSource ownerDataSource() {
        start();
        return new DriverManagerDataSource(url, ownerUser, ownerPassword);
    }

    static JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource());
    }

    /** A plain connection as the runtime role, outside the application. */
    static synchronized JdbcTemplate runtime() {
        start();
        return new JdbcTemplate(new DriverManagerDataSource(url, RUNTIME_ROLE, RUNTIME_PASSWORD));
    }

    private static void start() {
        if (url != null) {
            return;
        }
        String external = System.getenv("TEST_DB_URL");
        if (external != null && !external.isBlank()) {
            url = external;
            ownerUser = env("TEST_DB_USERNAME", "customer_test");
            ownerPassword = env("TEST_DB_PASSWORD", "customer_test");
        } else {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("db_cus_profile_kyc_test")
                .withUsername("customer_test")
                .withPassword("customer_test");
            container.start();
            url = container.getJdbcUrl();
            ownerUser = container.getUsername();
            ownerPassword = container.getPassword();
        }
        new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword)).execute("""
            do $$
            begin
                if not exists (select 1 from pg_roles where rolname = '%1$s') then
                    create role %1$s login password '%2$s';
                end if;
            end $$
            """.formatted(RUNTIME_ROLE, RUNTIME_PASSWORD));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
