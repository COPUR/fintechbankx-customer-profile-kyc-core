package com.bank.customer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The service pods never migrate: they hold only the runtime role, so at
 * startup Flyway validates and the service refuses to start while a migration
 * is pending. Only the chart's migration Job (args "migrate", profile
 * db-migrate) migrates, as the schema owner.
 */
class FlywayStartupConfigurationTest {

    @Test
    void theServiceValidatesAndOnlyTheMigrationProfileMigrates() throws Exception {
        assertThat(FlywayStartupConfiguration.mode(environment(Map.of())))
            .isEqualTo(FlywayStartupConfiguration.Mode.VALIDATE);
        assertThat(FlywayStartupConfiguration.mode(environment(Map.of(), "kafka-msk")))
            .isEqualTo(FlywayStartupConfiguration.Mode.VALIDATE);
        assertThat(FlywayStartupConfiguration.mode(environment(Map.of(), DatabaseMigration.PROFILE)))
            .isEqualTo(FlywayStartupConfiguration.Mode.MIGRATE);
    }

    @Test
    void validateModeNeverMigrates() {
        Flyway flyway = mock(Flyway.class);

        FlywayStartupConfiguration.strategy(FlywayStartupConfiguration.Mode.VALIDATE).migrate(flyway);

        verify(flyway).validate();
        verify(flyway, never()).migrate();
    }

    @Test
    void migrateModeMigrates() {
        Flyway flyway = mock(Flyway.class);

        FlywayStartupConfiguration.strategy(FlywayStartupConfiguration.Mode.MIGRATE).migrate(flyway);

        verify(flyway).migrate();
    }

    @Test
    void aMisspelledModeRefusesToStart() throws Exception {
        StandardEnvironment environment = environment(Map.of("CUSTOMER_DATABASE_FLYWAY", "migrat"));

        assertThatThrownBy(() -> FlywayStartupConfiguration.mode(environment)).isInstanceOf(BindException.class);
    }

    @Test
    void onlyAFirstArgumentMigrateSelectsTheMigrationJob() {
        assertThat(DatabaseMigration.isRequested(new String[] {"migrate"})).isTrue();
        assertThat(DatabaseMigration.isRequested(new String[] {"migrate", "--debug"})).isTrue();
        assertThat(DatabaseMigration.isRequested(new String[] {})).isFalse();
        assertThat(DatabaseMigration.isRequested(new String[] {"--migrate"})).isFalse();
        assertThat(DatabaseMigration.isRequested(new String[] {"--server.port=0", "migrate"})).isFalse();
        assertThat(DatabaseMigration.arguments(new String[] {"migrate", "--debug"})).containsExactly("--debug");
    }

    /** application.yml as Spring reads it: the default document, then the documents of the active profiles. */
    private static StandardEnvironment environment(Map<String, Object> podEnv, String... profiles) throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("pod-env", podEnv));
        // Each later document goes directly below the pod environment, so a profile
        // document (after the default one in the file) overrides the default.
        for (PropertySource<?> document : documents) {
            Object profile = document.getProperty("spring.config.activate.on-profile");
            if (profile == null || Arrays.asList(profiles).contains(profile.toString())) {
                environment.getPropertySources().addAfter("pod-env", document);
            }
        }
        return environment;
    }
}
