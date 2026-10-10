package com.bank.customer.infrastructure.persistence;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL
 * (a service container in required-gates.yml); locally either set TEST_DB_URL
 * or have Docker running for Testcontainers.
 *
 * <p>These adapter tests connect as the database's test user, which owns the
 * schema and migrates it (as the chart's migration Job does). The grants
 * migration names the runtime role through the Flyway placeholder
 * {@code runtime_role}; it is filled with the same role the bootstrap module's
 * integration tests run the service as, so the grants on the shared schema
 * are the same whichever module migrates first.
 */
public final class PostgresTestDatabase {

    /** The runtime role the bootstrap module's tests connect the service as (created here too). */
    public static final String RUNTIME_ROLE = "customer_runtime_it";
    private static final String RUNTIME_PASSWORD = "customer_runtime_it";

    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {
    }

    /**
     * Call from a static @BeforeAll. Without a database the class is skipped
     * locally, but fails when REQUIRE_TEST_DB=true (set in CI), so a missing
     * database can never turn the integration tests into a silent pass.
     */
    public static void assumeAvailable() {
        boolean available = hasExternalDatabase() || DockerClientFactory.instance().isDockerAvailable();
        if (!available && "true".equalsIgnoreCase(System.getenv("REQUIRE_TEST_DB"))) {
            throw new IllegalStateException("REQUIRE_TEST_DB=true but neither TEST_DB_URL nor Docker is available");
        }
        Assumptions.assumeTrue(available, "Set TEST_DB_URL or start Docker to run PostgreSQL integration tests");
    }

    private static boolean hasExternalDatabase() {
        String url = System.getenv("TEST_DB_URL");
        return url != null && !url.isBlank();
    }

    public static synchronized void register(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        String username;
        String password;
        if (url != null && !url.isBlank()) {
            username = env("TEST_DB_USERNAME", "customer_test");
            password = env("TEST_DB_PASSWORD", "customer_test");
        } else {
            if (container == null) {
                container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("db_cus_profile_kyc_test")
                    .withUsername("customer_test")
                    .withPassword("customer_test");
                container.start();
            }
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        }
        createRuntimeRole(url, username, password);
        String finalUrl = url;
        registry.add("spring.datasource.url", () -> finalUrl);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.placeholders.runtime_role", () -> RUNTIME_ROLE);
    }

    /** Flyway placeholders for a test that runs the migrations itself. */
    public static Map<String, String> flywayPlaceholders() {
        return Map.of("runtime_role", RUNTIME_ROLE);
    }

    private static void createRuntimeRole(String url, String username, String password) {
        new JdbcTemplate(new DriverManagerDataSource(url, username, password)).execute("""
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
