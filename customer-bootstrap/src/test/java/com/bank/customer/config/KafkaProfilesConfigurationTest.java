package com.bank.customer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kafka-msk profile (Helm SPRING_PROFILES_ACTIVE=kafka-msk) must switch
 * Kafka to IAM auth with classes that are on the runtime classpath, and the
 * base configuration must never create topics.
 */
class KafkaProfilesConfigurationTest {

    @Test
    void kafkaMskProfileUsesIamAuthWithClassesOnTheClasspath() throws Exception {
        PropertySource<?> msk = load("application-kafka-msk.yml");

        assertThat(msk.getProperty("spring.kafka.security.protocol")).hasToString("SASL_SSL");
        assertThat(msk.getProperty("spring.kafka.properties.sasl.mechanism")).hasToString("AWS_MSK_IAM");
        String handler = msk.getProperty("spring.kafka.properties.sasl.client.callback.handler.class").toString();
        String jaas = msk.getProperty("spring.kafka.properties.sasl.jaas.config").toString();

        assertThat(Class.forName(handler)).isNotNull();
        assertThat(Class.forName(jaas.substring(0, jaas.indexOf(' ')))).isNotNull();
    }

    @Test
    void kafkaStrimziProfileUsesTls() throws Exception {
        assertThat(load("application-kafka-strimzi.yml").getProperty("spring.kafka.security.protocol")).hasToString("SSL");
    }

    @Test
    void baseConfigurationNeverCreatesTopicsAndIdentifiesTheService() throws Exception {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("spring.kafka.admin.auto-create")).isEqualTo(false);
        assertThat(base.getProperty("spring.kafka.client-id")).hasToString("svc-cus-profile-kyc");
        assertThat(base.getProperty("spring.kafka.producer.compression-type")).hasToString("lz4");
    }

    @Test
    void producerTimeoutsLetKafkaConstructTheProducer() throws Exception {
        PropertySource<?> base = load("application.yml");
        long delivery = Long.parseLong(base.getProperty("spring.kafka.producer.properties.delivery.timeout.ms").toString());
        long request = Long.parseLong(base.getProperty("spring.kafka.producer.properties.request.timeout.ms").toString());
        long linger = Long.parseLong(base.getProperty("spring.kafka.producer.properties.linger.ms").toString());

        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
        assertThat(java.time.Duration.parse(base.getProperty("customer.outbox.relay.send-timeout").toString()).toMillis())
            .isGreaterThan(delivery);

        java.util.Map<String, Object> config = new java.util.HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        config.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
        config.put("value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
        config.put("enable.idempotence", true);
        config.put("delivery.timeout.ms", (int) delivery);
        config.put("request.timeout.ms", (int) request);
        config.put("linger.ms", (int) linger);
        config.put("compression.type", "lz4");
        // Construction validates the timeout rule; no broker is contacted.
        new org.apache.kafka.clients.producer.KafkaProducer<String, String>(config).close(java.time.Duration.ZERO);
    }

    private static PropertySource<?> load(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).get(0);
    }
}
