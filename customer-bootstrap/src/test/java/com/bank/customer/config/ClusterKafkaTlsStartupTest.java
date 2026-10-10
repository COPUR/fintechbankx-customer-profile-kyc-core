package com.bank.customer.config;

import com.bank.customer.infrastructure.config.KafkaTlsGuardConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chart's default pod environment, read through the real application.yml
 * and profile files: DB_SSL_ROOT_CERT set from the mounted bundle,
 * SPRING_PROFILES_ACTIVE=kafka-msk (or kafka-strimzi with the KafkaUser
 * certificate) and OUTBOX_RELAY_ENABLED=false until runbook step 6. The Kafka
 * TLS guard must be armed from that first deploy (the bundle alone arms it,
 * as for DatabaseTlsGuard) and must pass, so default cluster pods start; a
 * producer-level downgrade is refused even while the relay is off, because
 * step 6 switches the relay on with --reuse-values and nothing else.
 */
class ClusterKafkaTlsStartupTest {

    private static final String BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";

    private final ApplicationContextRunner clusterPod = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(KafkaTlsGuardConfiguration.class)
        .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "OUTBOX_RELAY_ENABLED=false",
            "KAFKA_BOOTSTRAP_SERVERS=ci-kafka-bootstrap-servers");

    @Test
    void theDefaultMskPodStartsWithTheGuardArmedWhileTheRelayIsOff() {
        clusterPod.withPropertyValues("spring.profiles.active=kafka-msk")
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(com.bank.customer.infrastructure.config.KafkaTlsGuard.class));
    }

    @Test
    void theStrimziPodStartsWithTheGuardArmedWhileTheRelayIsOff() {
        clusterPod.withPropertyValues("spring.profiles.active=kafka-strimzi",
                "KAFKA_TLS_CERT=cert", "KAFKA_TLS_KEY=key", "KAFKA_TLS_CA=ca")
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(com.bank.customer.infrastructure.config.KafkaTlsGuard.class));
    }

    /** A producer-level override is the only way past the profile's protocol; it is refused before step 6. */
    @Test
    void aProducerDowngradeIsRefusedWhileTheRelayIsOff() {
        clusterPod.withPropertyValues("spring.profiles.active=kafka-msk",
                "spring.kafka.producer.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("spring.kafka producer security.protocol must be SASL_SSL or SSL"));
    }

    /** A developer machine has no bundle: application.yml's PLAINTEXT default keeps working. */
    @Test
    void aLocalRunWithoutTheBundleIsUnaffected() {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(KafkaTlsGuardConfiguration.class)
            .withPropertyValues("OUTBOX_RELAY_ENABLED=true")
            .run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(com.bank.customer.infrastructure.config.KafkaTlsGuard.class));
    }
}
