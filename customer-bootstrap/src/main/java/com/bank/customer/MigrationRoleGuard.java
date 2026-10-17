package com.bank.customer;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

/**
 * Refuses the single-user fallback (decision 0001). When both
 * DB_MIGRATION_USERNAME (the schema owner, from the db-migration secret) and
 * DB_USERNAME (the runtime role the chart sets and V13 grants) are known, they
 * must name different roles: equal names mean the migration secret holds the
 * runtime credential, Flyway would migrate as the role the service connects
 * with, V13 would grant the owner to itself and the least-privilege split
 * (no DELETE on customer, append-only journal) would not hold. Without
 * DB_MIGRATION_USERNAME (service pods, local runs) nothing is checked.
 * PostgreSQL folds unquoted names to lower case, so the comparison ignores
 * case. The message names the properties, never a value.
 *
 * <p>A BeanFactoryPostProcessor, so it runs before the datasource and Flyway
 * beans exist ({@link FlywayStartupConfiguration}): the migration Job exits 1
 * without connecting.
 */
public final class MigrationRoleGuard implements BeanFactoryPostProcessor {

    static final String MIGRATION_USERNAME = "DB_MIGRATION_USERNAME";
    static final String RUNTIME_USERNAME = "DB_USERNAME";

    private final Environment environment;

    MigrationRoleGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        verify();
    }

    void verify() {
        String migration = environment.getProperty(MIGRATION_USERNAME, "").trim();
        String runtime = environment.getProperty(RUNTIME_USERNAME, "").trim();
        if (!migration.isEmpty() && !runtime.isEmpty() && migration.equalsIgnoreCase(runtime)) {
            throw new IllegalStateException(MIGRATION_USERNAME + " must not be the runtime role " + RUNTIME_USERNAME
                    + ": the migration Job runs Flyway as the schema owner and the service as the runtime role"
                    + " (decision 0001), never one credential for both");
        }
    }
}
