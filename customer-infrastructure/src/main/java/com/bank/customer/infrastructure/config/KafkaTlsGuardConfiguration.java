package com.bank.customer.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link KafkaTlsGuard} by default, under the same switch as
 * {@link DatabaseTlsGuardConfiguration} ({@link StartupAssertions}: only the
 * local profile or the test switch leave it out, and that configuration logs
 * the warning). The outbox relay flag gates publishing only: a cluster pod
 * deployed with the relay off (the chart default until runbook step 6)
 * already refuses a plain-text producer, because step 6 switches the relay on
 * with {@code --reuse-values} and changes nothing else. Not imported by the
 * migrate-only {@code DatabaseMigration} context, which has no Kafka.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTlsGuardConfiguration {

    /** Static: a BeanFactoryPostProcessor runs before any Kafka producer or relay bean exists. */
    @Bean
    @Conditional(StartupAssertions.On.class)
    static KafkaTlsGuard kafkaTlsGuard(Environment environment) {
        return new KafkaTlsGuard(environment);
    }
}
