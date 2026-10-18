package com.bank.customer.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link DatabaseTlsGuard} by default, wherever the service or its
 * migrate-only Job runs (guardrail 4a); it then requires DB_SSL_ROOT_CERT (the
 * chart sets it from the mounted RDS CA bundle on every container) and a
 * datasource URL the driver verifies against it. Only the local profile or
 * the test switch ({@link StartupAssertions}) leave the guard out, and then
 * {@link StartupAssertions.OffWarning} logs that the assertions are off.
 * Static beans: a BeanFactoryPostProcessor runs before any other bean is
 * created.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseTlsGuardConfiguration {

    @Bean
    @Conditional(StartupAssertions.On.class)
    static DatabaseTlsGuard databaseTlsGuard() {
        return new DatabaseTlsGuard();
    }

    @Bean
    @Conditional(StartupAssertions.Off.class)
    static StartupAssertions.OffWarning startupAssertionsOffWarning() {
        return new StartupAssertions.OffWarning();
    }
}
