package com.bank.customer.infrastructure.persistence;

import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL
 * (a service container in required-gates.yml); locally either set TEST_DB_URL
 * or have Docker running for Testcontainers.
 */
public final class PostgresTestDatabase {

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
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "customer_test"));
            registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "customer_test"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("db_cus_profile_kyc_test")
                .withUsername("customer_test")
                .withPassword("customer_test");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
