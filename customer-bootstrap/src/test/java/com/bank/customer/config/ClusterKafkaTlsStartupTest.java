package com.bank.customer.config;

import com.bank.customer.infrastructure.config.DatabaseTlsGuard;
import com.bank.customer.infrastructure.config.DatabaseTlsGuardConfiguration;
import com.bank.customer.infrastructure.config.KafkaTlsGuard;
import com.bank.customer.infrastructure.config.KafkaTlsGuardConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chart's default pod environment, read through the real application.yml
 * and profile files: DB_SSL_ROOT_CERT set from the mounted bundle,
 * SPRING_PROFILES_ACTIVE=kafka-msk (or kafka-strimzi with the KafkaUser
 * certificate) and OUTBOX_RELAY_ENABLED=false until runbook step 6. The Kafka
 * TLS guard is on by default (guardrail 4a), from the first deploy and
 * wherever the image runs, and must pass, so default cluster pods start; a
 * producer-level downgrade is refused even while the relay is off, because
 * step 6 switches the relay on with --reuse-values and nothing else. A run
 * outside the chart with application.yml's PLAINTEXT default is refused too,
 * unless it sets the local profile, and the log then says the assertions are
 * off.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClusterKafkaTlsStartupTest {

    private static final String BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String REFUSED = "spring.kafka producer security.protocol must be SASL_SSL or SSL";
    private static final String OFF = "TLS startup assertions are off";

    private final ApplicationContextRunner clusterPod = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(KafkaTlsGuardConfiguration.class)
        .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "OUTBOX_RELAY_ENABLED=false",
            "KAFKA_BOOTSTRAP_SERVERS=ci-kafka-bootstrap-servers");

    /** A developer machine: application.yml as it resolves with no environment and no profile. */
    private final ApplicationContextRunner outsideTheChart = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(KafkaTlsGuardConfiguration.class)
        .withPropertyValues("OUTBOX_RELAY_ENABLED=true");

    @Test
    void theDefaultMskPodStartsWithTheGuardOnWhileTheRelayIsOff() {
        clusterPod.withPropertyValues("spring.profiles.active=kafka-msk")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KafkaTlsGuard.class));
    }

    @Test
    void theStrimziPodStartsWithTheGuardOnWhileTheRelayIsOff() {
        clusterPod.withPropertyValues("spring.profiles.active=kafka-strimzi",
                "KAFKA_TLS_CERT=cert", "KAFKA_TLS_KEY=key", "KAFKA_TLS_CA=ca")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KafkaTlsGuard.class));
    }

    /** A producer-level override is the only way past the profile's protocol; it is refused before step 6. */
    @Test
    void aProducerDowngradeIsRefusedWhileTheRelayIsOff() {
        clusterPod.withPropertyValues("spring.profiles.active=kafka-msk",
                "spring.kafka.producer.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(REFUSED));
    }

    /** No bundle, no profile: application.yml's PLAINTEXT default is refused, as in a pod. */
    @Test
    void aRunOutsideTheChartWithoutTheLocalProfileIsRefused(CapturedOutput output) {
        outsideTheChart.run(context -> assertThat(context).hasFailed().getFailure()
            .hasMessageContaining(REFUSED).hasMessageContaining("PLAINTEXT"));
        assertThat(output.getOut()).doesNotContain(OFF);
    }

    /** The local profile is the developer's switch: neither guard is registered (the service loads both configurations) and the log says so. */
    @Test
    void aRunOutsideTheChartWithTheLocalProfileStartsAndLogsThatTheAssertionsAreOff(CapturedOutput output) {
        outsideTheChart.withUserConfiguration(DatabaseTlsGuardConfiguration.class)
            .withPropertyValues("spring.profiles.active=local")
            .run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(KafkaTlsGuard.class).doesNotHaveBean(DatabaseTlsGuard.class));
        assertThat(output.getOut()).contains("WARN").contains(OFF);
    }
}
