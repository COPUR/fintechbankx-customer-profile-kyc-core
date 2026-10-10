package com.bank.customer.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link KafkaTlsGuard} whenever the chart has mounted the RDS CA
 * bundle (DB_SSL_ROOT_CERT is always set there, so the service runs in a
 * cluster and must publish over SASL_SSL or SSL), exactly the condition of
 * {@link DatabaseTlsGuard}. The outbox relay flag gates publishing only: a
 * cluster pod deployed with the relay off (the chart default until runbook
 * step 6) already refuses a plain-text producer, because step 6 switches the
 * relay on with {@code --reuse-values} and changes nothing else. Without the
 * bundle (local runs, tests) the guard stays off.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTlsGuardConfiguration {

    /** Static: a BeanFactoryPostProcessor runs before any Kafka producer or relay bean exists. */
    @Bean
    @ConditionalOnProperty(DatabaseTlsGuard.ROOT_CERT_VARIABLE)
    static KafkaTlsGuard kafkaTlsGuard(Environment environment) {
        return new KafkaTlsGuard(environment);
    }
}
