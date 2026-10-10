package com.bank.customer.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review 5478458791 finding 2: the service refuses to start unless every
 * PostgreSQL URL it will use verifies the server (sslmode=verify-full against
 * DB_SSL_ROOT_CERT, no sslfactory), parsed the way the driver parses it
 * (org.postgresql.Driver.parseURL). Skipped when DB_SSL_ROOT_CERT is unset
 * (local runs and tests); the Helm chart always sets it.
 */
class DatabaseTlsGuardTest {

    private static final String CA = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String HOST = "jdbc:postgresql://db.example.internal:5432/db_cus_profile_kyc";
    private static final String GOOD = HOST + "?sslmode=verify-full&sslrootcert=" + CA;

    private static MockEnvironment env(String url) {
        MockEnvironment env = new MockEnvironment().withProperty("DB_SSL_ROOT_CERT", CA);
        if (url != null) {
            env.setProperty("spring.datasource.url", url);
        }
        return env;
    }

    @Test
    void aVerifyFullUrlWithTheMountedBundleStarts() {
        assertThatCode(() -> DatabaseTlsGuard.check(env(GOOD))).doesNotThrowAnyException();
        assertThatCode(() -> DatabaseTlsGuard.check(env(HOST + "?sslrootcert=" + CA + "&sslmode=verify-full&applicationName=svc")))
            .doesNotThrowAnyException();
    }

    @Test
    void withoutDbSslRootCertTheGuardIsSkipped() {
        MockEnvironment local = new MockEnvironment().withProperty("spring.datasource.url", HOST + "?sslmode=disable");

        assertThatCode(() -> DatabaseTlsGuard.check(local)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // no TLS verification at all
        HOST,
        HOST + "?sslmode=require&sslrootcert=" + CA,
        // a repeated sslmode after verify-full wins in the driver
        HOST + "?sslmode=verify-full&sslrootcert=" + CA + "&sslmode=disable",
        // decoy CA file, alone or after the real one
        HOST + "?sslmode=verify-full&sslrootcert=/tmp/decoy.pem",
        HOST + "?sslmode=verify-full&sslrootcert=" + CA + "&sslrootcert=/tmp/decoy.pem",
        HOST + "?sslmode=verify-full",
        // trust-everything factories and verifiers
        GOOD + "&sslfactory=org.postgresql.ssl.NonValidatingFactory",
        GOOD + "&sslhostnameverifier=com.example.AllowAll",
        GOOD + "&sslpasswordcallback=com.example.Callback",
        // the old substring check passed this one
        HOST + "?sslmode=require&applicationName=sslmode=verify-full&sslrootcert=" + CA,
        // not a PostgreSQL URL the driver accepts
        "JDBC:postgresql://db.example.internal/db?sslmode=verify-full&sslrootcert=" + CA
    })
    void aDatasourceUrlThatDoesNotVerifyTheServerIsRefused(String url) {
        assertThatThrownBy(() -> DatabaseTlsGuard.check(env(url)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.url")
            .hasMessageNotContaining("decoy").hasMessageNotContaining("db.example.internal");
    }

    @Test
    void aMissingDatasourceUrlIsRefusedUnderTheChart() {
        assertThatThrownBy(() -> DatabaseTlsGuard.check(env(null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aWeakFlywayOrHikariUrlIsRefused() {
        MockEnvironment flyway = env(GOOD);
        flyway.setProperty("spring.flyway.url", HOST + "?sslmode=require");
        MockEnvironment hikari = env(GOOD);
        hikari.setProperty("spring.datasource.hikari.jdbc-url", HOST + "?sslmode=disable");

        assertThatThrownBy(() -> DatabaseTlsGuard.check(flyway)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.flyway.url");
        assertThatThrownBy(() -> DatabaseTlsGuard.check(hikari)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.hikari.jdbc-url");
        MockEnvironment goodFlyway = env(GOOD);
        goodFlyway.setProperty("spring.flyway.url", GOOD);
        assertThatCode(() -> DatabaseTlsGuard.check(goodFlyway)).doesNotThrowAnyException();
    }

    /** Driver properties set beside the URL (Hikari data-source-properties) cannot switch TLS off either. */
    @Test
    void sslDriverPropertiesBesideTheUrlAreRefused() {
        MockEnvironment env = env(GOOD);
        env.setProperty("spring.datasource.hikari.data-source-properties.sslfactory", "org.postgresql.ssl.NonValidatingFactory");

        assertThatThrownBy(() -> DatabaseTlsGuard.check(env)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("data-source-properties");
    }

    @Test
    void theContextRefusesToStartWithAWeakUrlWhenDbSslRootCertIsSet() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(DatabaseTlsGuardConfiguration.class);

        runner.withPropertyValues("DB_SSL_ROOT_CERT=" + CA, "spring.datasource.url=" + HOST + "?sslmode=require")
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("DB_SSL_ROOT_CERT=" + CA, "spring.datasource.url=" + GOOD)
            .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/db_cus_profile_kyc_local")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
