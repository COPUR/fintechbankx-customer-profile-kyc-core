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

    private static PropertySource<?> load(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).get(0);
    }
}
