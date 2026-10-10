package com.bank.customer.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TLS startup assertions (DatabaseTlsGuard, KafkaTlsGuard) are on by
 * default (guardrail 4a): a runtime outside the chart that starts the service
 * with the application.yml defaults (no profile, no DB_SSL_ROOT_CERT, a plain
 * localhost datasource URL, a PLAINTEXT producer) does not start. Only the
 * local profile, or the test switch fintechbankx.tls.enforce=false set in test
 * resources or test annotations, turns them off, and the log then says so.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupTlsAssertionsTest {

    private static final String OFF = "TLS startup assertions are off";
    private static final String BUNDLE_REQUIRED = "DB_SSL_ROOT_CERT must name the mounted RDS CA bundle";

    /** What application.yml resolves to without any environment: the local single-user shape. */
    private final ApplicationContextRunner localShape = new ApplicationContextRunner()
        .withUserConfiguration(DatabaseTlsGuardConfiguration.class, KafkaTlsGuardConfiguration.class)
        .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/db_cus_profile_kyc_local",
            "spring.kafka.security.protocol=PLAINTEXT");

    @Test
    void refusesToStartWithNoProfileAndNoBundle(CapturedOutput output) {
        localShape.run(context -> assertThat(context).hasFailed().getFailure()
            .hasMessageContaining(BUNDLE_REQUIRED)
            .hasMessageNotContaining("db_cus_profile_kyc_local"));
        assertThat(output.getOut()).doesNotContain(OFF);
    }

    /** A bundle alone is not enough: the URL must verify against it and the producer must use TLS. */
    @Test
    void refusesToStartWithTheBundleButAPlainUrlAndProducer() {
        localShape.withPropertyValues("DB_SSL_ROOT_CERT=/etc/fintechbankx/rds-ca/global-bundle.pem")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("spring.datasource.url does not set sslmode=verify-full"));
    }

    @Test
    void startsWithTheLocalProfileAndLogsThatTheAssertionsAreOff(CapturedOutput output) {
        localShape.withPropertyValues("spring.profiles.active=local")
            .run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(DatabaseTlsGuard.class).doesNotHaveBean(KafkaTlsGuard.class));
        assertThat(output.getOut()).contains("WARN").contains(OFF);
    }

    @Test
    void startsWithTheTestSwitchAndLogsThatTheAssertionsAreOff(CapturedOutput output) {
        localShape.withPropertyValues("fintechbankx.tls.enforce=false")
            .run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(DatabaseTlsGuard.class).doesNotHaveBean(KafkaTlsGuard.class));
        assertThat(output.getOut()).contains("WARN").contains(OFF);
    }

    /** The local profile counts among others too, but only by its exact name, and only the exact value false switches. */
    @Test
    void theLocalProfileCountsOnlyByItsExactName() {
        localShape.withPropertyValues("spring.profiles.active=kafka-msk,local")
            .run(context -> assertThat(context).hasNotFailed());
        localShape.withPropertyValues("spring.profiles.active=kafka-msk")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fintechbankx.tls.enforce=true", "fintechbankx.tls.enforce=", "fintechbankx.tls.enforce=off",
        "fintechbankx.tls.enforce=no", "spring.profiles.active=LOCAL", "spring.profiles.active=localstack",
        "spring.profiles.active=local-ish", "spring.profiles.active=kafka-strimzi"})
    void staysOnForAnyOtherValueOrProfile(String property, CapturedOutput output) {
        localShape.withPropertyValues(property)
            .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(BUNDLE_REQUIRED));
        assertThat(output.getOut()).doesNotContain(OFF);
    }
}
