package com.bank.customer;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * What Flyway does when a context starts (customer.database.flyway).
 *
 * <p>The service validates: it connects as the runtime role, which may read
 * but not write the schema history (V13), and refuses to start while a
 * migration is pending or an applied one was changed. Only the chart's
 * migration Job ({@link DatabaseMigration}, profile db-migrate) migrates, as
 * the schema owner, so the service pods never hold the owner's credential
 * (decision 0001). A value other than validate or migrate fails the startup.
 */
@Configuration(proxyBeanMethods = false)
public class FlywayStartupConfiguration {

    static final String PROPERTY = "customer.database.flyway";

    enum Mode { VALIDATE, MIGRATE }

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(Environment environment) {
        return strategy(mode(environment));
    }

    static Mode mode(Environment environment) {
        return Binder.get(environment).bind(PROPERTY, Mode.class).orElse(Mode.VALIDATE);
    }

    static FlywayMigrationStrategy strategy(Mode mode) {
        return switch (mode) {
            case VALIDATE -> flyway -> flyway.validate();
            case MIGRATE -> flyway -> flyway.migrate();
        };
    }
}
