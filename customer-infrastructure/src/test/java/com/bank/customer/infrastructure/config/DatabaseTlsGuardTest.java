package com.bank.customer.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review 5478458791 finding 2: the service refuses to start unless every
 * PostgreSQL URL it will use verifies the server (sslmode=verify-full against
 * DB_SSL_ROOT_CERT, no sslfactory), parsed the way the driver parses it
 * (org.postgresql.Driver.parseURL). On by default wherever the service runs
 * (guardrail 4a): a missing or blank DB_SSL_ROOT_CERT is refused, not skipped.
 * Only the local profile and the test switch fintechbankx.tls.enforce=false
 * turn the guard off, and then the log says so.
 */
@ExtendWith(OutputCaptureExtension.class)
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

    /** The bundle path is required while the guard is on: a run outside the chart gets the same refusal as a pod. */
    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void aMissingOrBlankDbSslRootCertIsRefusedNotSkipped(String rootCert) {
        MockEnvironment missing = new MockEnvironment().withProperty("spring.datasource.url", GOOD);
        MockEnvironment blank = new MockEnvironment().withProperty("DB_SSL_ROOT_CERT", rootCert)
            .withProperty("spring.datasource.url", GOOD);

        for (MockEnvironment env : new MockEnvironment[] {missing, blank}) {
            assertThatThrownBy(() -> DatabaseTlsGuard.check(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_SSL_ROOT_CERT must name the mounted RDS CA bundle")
                .hasMessageContaining("profile local").hasMessageContaining("fintechbankx.tls.enforce=false")
                .hasMessageNotContaining("db.example.internal");
        }
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

    /**
     * Hikari's jdbc-url wins over spring.datasource.url and Flyway's url over the
     * datasource: a second URL that verifies but names another server or database
     * would move the pool or the schema check off the URL the chart validated.
     */
    @Test
    void aHikariOrFlywayUrlThatVerifiesButDiffersFromTheDatasourceUrlIsRefused() {
        String elsewhere = "jdbc:postgresql://other.example.internal:5432/db_other?sslmode=verify-full&sslrootcert=" + CA;
        MockEnvironment hikari = env(GOOD);
        hikari.setProperty("spring.datasource.hikari.jdbc-url", elsewhere);
        MockEnvironment flyway = env(GOOD);
        flyway.setProperty("spring.flyway.url", elsewhere);

        assertThatThrownBy(() -> DatabaseTlsGuard.check(hikari)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.hikari.jdbc-url differs from spring.datasource.url")
            .hasMessageNotContaining("other.example.internal");
        assertThatThrownBy(() -> DatabaseTlsGuard.check(flyway)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.flyway.url differs from spring.datasource.url")
            .hasMessageNotContaining("other.example.internal");
        MockEnvironment sameHikari = env(GOOD);
        sameHikari.setProperty("spring.datasource.hikari.jdbc-url", GOOD);
        assertThatCode(() -> DatabaseTlsGuard.check(sameHikari)).doesNotThrowAnyException();
    }

    @Test
    void theContextRefusesToStartWithAHikariUrlThatPointsElsewhere() {
        new ApplicationContextRunner()
            .withUserConfiguration(DatabaseTlsGuardConfiguration.class)
            .withPropertyValues("DB_SSL_ROOT_CERT=" + CA, "spring.datasource.url=" + GOOD,
                "spring.datasource.hikari.jdbc-url=jdbc:postgresql://other.example.internal:5432/db_other?sslmode=verify-full&sslrootcert=" + CA)
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("spring.datasource.hikari.jdbc-url differs from spring.datasource.url"));
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
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DatabaseTlsGuard.class));
    }

    private static final String LOCAL_URL = "jdbc:postgresql://localhost:5432/db_cus_profile_kyc_local";
    private static final String BUNDLE_REQUIRED = "DB_SSL_ROOT_CERT must name the mounted RDS CA bundle";
    private static final String OFF = "TLS startup assertions are off";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(DatabaseTlsGuardConfiguration.class);

    /** On by default: the local shape of application.yml (no bundle, no profile) does not start. */
    @Test
    void theContextRefusesToStartWithoutTheBundleByDefault(CapturedOutput output) {
        contextRunner.withPropertyValues("spring.datasource.url=" + LOCAL_URL)
            .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(BUNDLE_REQUIRED));
        contextRunner.withPropertyValues("DB_SSL_ROOT_CERT=", "spring.datasource.url=" + GOOD)
            .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(BUNDLE_REQUIRED));
        assertThat(output.getOut()).doesNotContain(OFF);
    }

    /** The local profile is the developer's switch: the guard is not registered and the log says so. */
    @Test
    void theContextStartsWithTheLocalProfileWithoutTheGuardAndLogsThatTheAssertionsAreOff(CapturedOutput output) {
        contextRunner.withPropertyValues("spring.profiles.active=local", "spring.datasource.url=" + LOCAL_URL)
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DatabaseTlsGuard.class));
        assertThat(output.getOut()).contains("WARN").contains(OFF);
    }

    /** The test switch (test resources and test annotations only, never a shipped application*.yml). */
    @Test
    void theContextStartsWithTheTestSwitchWithoutTheGuardAndLogsThatTheAssertionsAreOff(CapturedOutput output) {
        contextRunner.withPropertyValues("fintechbankx.tls.enforce=false", "spring.datasource.url=" + LOCAL_URL)
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DatabaseTlsGuard.class));
        assertThat(output.getOut()).contains("WARN").contains(OFF);
    }

    /** Only the exact value false and the exact profile name local turn the guard off. */
    @ParameterizedTest
    @ValueSource(strings = {"fintechbankx.tls.enforce=true", "fintechbankx.tls.enforce=", "fintechbankx.tls.enforce=off",
        "spring.profiles.active=LOCAL", "spring.profiles.active=localstack", "spring.profiles.active=kafka-msk"})
    void theContextStaysOnForAnyOtherValueOrProfile(String property) {
        contextRunner.withPropertyValues(property, "spring.datasource.url=" + LOCAL_URL)
            .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(BUNDLE_REQUIRED));
    }
}
