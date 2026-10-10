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
 * The Kafka half of the startup TLS contract, the sibling of
 * {@link DatabaseTlsGuardTest}. In a deployed environment (the chart always
 * mounts the RDS CA bundle, so DB_SSL_ROOT_CERT is set) the outbox relay must
 * publish over TLS: SASL_SSL (Amazon MSK with IAM, profile kafka-msk) or SSL
 * (Strimzi mutual TLS with the KafkaUser certificate, profile kafka-strimzi).
 * PLAINTEXT, SASL_PLAINTEXT or no protocol at all (Kafka defaults to
 * PLAINTEXT) means a values override or a wrong profile, and the service
 * refuses to start rather than publish customer events in clear.
 *
 * <p>The guard is off when the relay is off (nothing publishes) and off
 * without DB_SSL_ROOT_CERT (local runs and tests).
 */
class KafkaTlsGuardTest {

    private static final String BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String REFUSED = "spring.kafka producer security.protocol must be SASL_SSL or SSL";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(KafkaTlsGuardConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"SASL_SSL", "SSL"})
    void acceptsATlsProtocol(String protocol) {
        assertThatCode(() -> guard(protocol).verify()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAINTEXT", "SASL_PLAINTEXT"})
    void refusesAProtocolWithoutTls(String protocol) {
        assertThatThrownBy(() -> guard(protocol).verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining(protocol);
    }

    @Test
    void refusesAMissingProtocolWhichWouldDefaultToPlaintext() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("DB_SSL_ROOT_CERT", BUNDLE)
            .withProperty("customer.outbox.relay.enabled", "true");

        assertThatThrownBy(() -> new KafkaTlsGuard(environment).verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("none");
    }

    /** The producer's own protocol wins over the common one, exactly as Spring Boot builds the producer. */
    @Test
    void readsTheEffectiveProducerProtocolNotOnlyTheCommonOne() {
        MockEnvironment downgraded = environment("SASL_SSL")
            .withProperty("spring.kafka.producer.security.protocol", "PLAINTEXT");
        MockEnvironment upgraded = environment("PLAINTEXT")
            .withProperty("spring.kafka.producer.security.protocol", "SSL");

        assertThatThrownBy(() -> new KafkaTlsGuard(downgraded).verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED);
        assertThatCode(() -> new KafkaTlsGuard(upgraded).verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesAProtocolSetThroughTheRawProducerProperties() {
        MockEnvironment environment = environment("SASL_SSL")
            .withProperty("spring.kafka.producer.properties.security.protocol", "SASL_PLAINTEXT");

        assertThatThrownBy(() -> new KafkaTlsGuard(environment).verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED);
    }

    /** The message names the property and the value, never a broker address. */
    @Test
    void theRefusalNamesNoBroker() {
        MockEnvironment environment = environment("PLAINTEXT")
            .withProperty("spring.kafka.bootstrap-servers", "b-1.msk.example.internal:9098");

        assertThatThrownBy(() -> new KafkaTlsGuard(environment).verify())
            .hasMessageNotContaining("msk.example.internal");
    }

    @Test
    void theContextRefusesToStartWithAPlaintextProducerWhenTheRelayIsOnAndTheBundleIsMounted() {
        contextRunner
            .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "customer.outbox.relay.enabled=true",
                "spring.kafka.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(REFUSED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SASL_SSL", "SSL"})
    void theContextStartsWithATlsProducerWhenTheRelayIsOnAndTheBundleIsMounted(String protocol) {
        contextRunner
            .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "customer.outbox.relay.enabled=true",
                "spring.kafka.security.protocol=" + protocol)
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KafkaTlsGuard.class));
    }

    @Test
    void staysInactiveWhileTheRelayIsOffBecauseNothingPublishes() {
        contextRunner
            .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "spring.kafka.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
        contextRunner
            .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "customer.outbox.relay.enabled=false",
                "spring.kafka.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
    }

    /** Local runs and tests have no RDS bundle (and application.yml defaults to PLAINTEXT) and keep working. */
    @Test
    void staysInactiveWithoutTheBundleSoLocalRunsStillStart() {
        contextRunner
            .withPropertyValues("customer.outbox.relay.enabled=true", "spring.kafka.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
        contextRunner
            .withPropertyValues("customer.outbox.relay.enabled=true")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
    }

    private static KafkaTlsGuard guard(String protocol) {
        return new KafkaTlsGuard(environment(protocol));
    }

    private static MockEnvironment environment(String protocol) {
        return new MockEnvironment()
            .withProperty("DB_SSL_ROOT_CERT", BUNDLE)
            .withProperty("customer.outbox.relay.enabled", "true")
            .withProperty("spring.kafka.security.protocol", protocol);
    }
}
