package com.bank.customer.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link DatabaseTlsGuard}; static so it runs before any other bean is created. */
@Configuration(proxyBeanMethods = false)
public class DatabaseTlsGuardConfiguration {

    @Bean
    static DatabaseTlsGuard databaseTlsGuard() {
        return new DatabaseTlsGuard();
    }
}
