package com.bank.customer.infrastructure.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.Set;

/**
 * Kafka transport at startup, the sibling of {@link DatabaseTlsGuard}. In a
 * deployed environment the outbox relay publishes customer events over TLS:
 * SASL_SSL to Amazon MSK with IAM authentication (profile kafka-msk) or SSL
 * with the Strimzi KafkaUser client certificate (profile kafka-strimzi). A
 * values override (KAFKA_SECURITY_PROTOCOL, a producer-level property, a
 * wrong profile) could downgrade the producer to PLAINTEXT or SASL_PLAINTEXT,
 * and the relay would then publish customer ids, amounts and KYC changes in
 * clear. Before any Kafka bean exists, this guard builds the producer
 * properties exactly as Spring Boot does
 * ({@link KafkaProperties#buildProducerProperties}: the producer's own
 * settings win over the common ones) and refuses to start unless the
 * effective {@code security.protocol} is one of {@link #ACCEPTED_PROTOCOLS}.
 *
 * <p>Registered by {@link KafkaTlsGuardConfiguration} whenever
 * DB_SSL_ROOT_CERT is set (the chart always sets it from the mounted RDS CA
 * bundle), relay on or off: the relay flag gates publishing, not the check,
 * so a cluster pod refuses a plain-text producer from its first deploy, before
 * runbook step 6 switches the relay on with {@code --reuse-values}. Local runs
 * and tests have no bundle, so the guard is off for them. Messages name the
 * property and the value, never a broker address.
 */
public final class KafkaTlsGuard implements BeanFactoryPostProcessor {

    static final Set<String> ACCEPTED_PROTOCOLS = Set.of("SASL_SSL", "SSL");

    private static final String KAFKA_PREFIX = "spring.kafka";
    private static final String SECURITY_PROTOCOL = "security.protocol";

    private final Environment environment;

    KafkaTlsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        verify();
    }

    void verify() {
        KafkaProperties kafka = Binder.get(environment)
            .bind(KAFKA_PREFIX, KafkaProperties.class)
            .orElseGet(KafkaProperties::new);
        Map<String, Object> producer = kafka.buildProducerProperties(null);
        Object protocol = producer.get(SECURITY_PROTOCOL);
        if (protocol == null || !ACCEPTED_PROTOCOLS.contains(protocol.toString())) {
            throw new IllegalStateException(KAFKA_PREFIX + " producer " + SECURITY_PROTOCOL
                + " must be SASL_SSL or SSL when " + DatabaseTlsGuard.ROOT_CERT_VARIABLE
                + " is set, whether or not the outbox relay is on (Amazon MSK with IAM authentication, profile"
                + " kafka-msk, or Strimzi mutual TLS, profile kafka-strimzi), got " + (protocol == null ? "none (Kafka defaults to PLAINTEXT)" : protocol));
        }
    }
}
