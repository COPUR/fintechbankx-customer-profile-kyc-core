package com.bank.customer;

import com.bank.customer.infrastructure.config.DatabaseTlsGuardConfiguration;
import com.bank.customer.infrastructure.config.KafkaTlsGuardConfiguration;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review finding: spring.main.lazy-initialization=true defers every bean
 * definition, so a guard that runs when its bean is created would not run at
 * all. The startup guards of this service (DatabaseTlsGuard, KafkaTlsGuard,
 * MigrationRoleGuard) are BeanFactoryPostProcessors, which the context
 * instantiates and runs before any bean, lazy or not; this test proves each
 * still refuses with lazy initialisation on (the chart also refuses every
 * spring.main.* config key, defence in depth).
 *
 * <p>The contexts are started through SpringApplication, which is what
 * applies spring.main.lazy-initialization (an ApplicationContextRunner would
 * ignore it). Every context also holds {@link BeanTimeGuard}, a bean whose
 * factory method refuses: it fails an eager context and is skipped by a lazy
 * one, so a guard refusal below is proven to happen with lazy initialisation
 * in effect. The settings are command-line arguments, above application.yml
 * and the environment.
 */
class LazyInitializationGuardsTest {

    private static final String LAZY = "--spring.main.lazy-initialization=true";
    private static final String BEAN_TIME_REFUSAL = "bean-time guard ran";

    /** A guard of the kind lazy initialisation skips: it refuses only when its bean is created. Not a stereotype, so no scan picks it up. */
    static class BeanTimeGuard {
        @Bean
        Object beanTimeGuard() {
            throw new IllegalStateException(BEAN_TIME_REFUSAL);
        }
    }

    @Test
    void lazyInitialisationSkipsABeanTimeGuard() {
        assertThatThrownBy(() -> start(List.of(BeanTimeGuard.class), "--spring.main.lazy-initialization=false"))
            .satisfies(e -> assertThat(messages(e)).contains(BEAN_TIME_REFUSAL));
        assertThatCode(() -> start(List.of(BeanTimeGuard.class), LAZY)).doesNotThrowAnyException();
    }

    @Test
    void databaseTlsGuardStillRefusesWithLazyInitialisation() {
        assertThatThrownBy(() -> start(List.of(BeanTimeGuard.class, DatabaseTlsGuardConfiguration.class), LAZY,
                "--DB_SSL_ROOT_CERT=", "--spring.datasource.url=jdbc:postgresql://localhost:5432/db_cus_profile_kyc_local"))
            .satisfies(refusedBy("DB_SSL_ROOT_CERT must name the mounted RDS CA bundle"));
    }

    @Test
    void kafkaTlsGuardStillRefusesWithLazyInitialisation() {
        assertThatThrownBy(() -> start(List.of(BeanTimeGuard.class, KafkaTlsGuardConfiguration.class), LAZY,
                "--spring.kafka.security.protocol=PLAINTEXT"))
            .satisfies(refusedBy("spring.kafka producer security.protocol must be SASL_SSL or SSL"));
    }

    @Test
    void migrationRoleGuardStillRefusesWithLazyInitialisation() {
        assertThatThrownBy(() -> start(List.of(BeanTimeGuard.class, FlywayStartupConfiguration.class), LAZY,
                "--DB_MIGRATION_USERNAME=customer_profile_kyc_app", "--DB_USERNAME=customer_profile_kyc_app"))
            .satisfies(refusedBy("DB_MIGRATION_USERNAME must not be the runtime role DB_USERNAME"));
    }

    /** The guard's message is in the failure's cause chain, the bean-time guard's is not (it never ran). */
    private static ThrowingConsumer<Throwable> refusedBy(String refusal) {
        return e -> assertThat(messages(e)).contains(refusal).doesNotContain(BEAN_TIME_REFUSAL);
    }

    private static String messages(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    /** Starts and closes a non-web context from the given sources; the TLS assertions are on and no profile is active. */
    private static void start(List<Class<?>> sources, String... args) {
        List<String> all = new ArrayList<>(List.of("--fintechbankx.tls.enforce=true", "--spring.profiles.active=",
            "--spring.main.banner-mode=off"));
        all.addAll(List.of(args));
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .web(WebApplicationType.NONE)
                .run(all.toArray(String[]::new))) {
            // started: nothing refused
        }
    }
}
