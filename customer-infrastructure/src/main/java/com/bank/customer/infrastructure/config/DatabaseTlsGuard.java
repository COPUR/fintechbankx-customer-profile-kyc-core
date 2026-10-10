package com.bank.customer.infrastructure.config;

import org.postgresql.Driver;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Refuses to start unless every PostgreSQL URL the service uses verifies the
 * Aurora server (cicd-templates 4f0f266; review 5478458791 finding 2). The URLs
 * are parsed with {@link Driver#parseURL}, as the driver will read them, so a
 * repeated parameter or one hidden in another value cannot pass: sslmode must
 * be verify-full, sslrootcert must equal DB_SSL_ROOT_CERT (the CA bundle the
 * chart mounts), and sslfactory, sslhostnameverifier and sslpasswordcallback
 * must be absent. Checked: spring.datasource.url (required), and
 * spring.datasource.hikari.jdbc-url and spring.flyway.url when set; Hikari
 * data-source-properties may not carry ssl* driver properties.
 *
 * Skipped when DB_SSL_ROOT_CERT is unset (local runs and tests). The Helm chart
 * always sets it, so the check is mandatory in every deployed environment.
 * Runs as a BeanFactoryPostProcessor, before the DataSource or Flyway exist.
 * Messages name the property, never the URL (it may carry credentials).
 */
public class DatabaseTlsGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String ROOT_CERT_VARIABLE = "DB_SSL_ROOT_CERT";
    private static final String[] FORBIDDEN = {"sslfactory", "sslfactoryarg", "sslhostnameverifier", "sslpasswordcallback"};

    private Environment environment = new StandardEnvironment();

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(environment);
    }

    static void check(Environment environment) {
        String rootCert = environment.getProperty(ROOT_CERT_VARIABLE);
        if (rootCert == null || rootCert.isBlank()) {
            return;
        }
        String datasourceUrl = environment.getProperty("spring.datasource.url");
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            throw refused("spring.datasource.url", "is not set", rootCert);
        }
        verify("spring.datasource.url", datasourceUrl, rootCert);
        for (String optional : new String[] {"spring.datasource.hikari.jdbc-url", "spring.flyway.url"}) {
            String url = environment.getProperty(optional);
            if (url != null && !url.isBlank()) {
                verify(optional, url, rootCert);
            }
        }
        if (environment instanceof ConfigurableEnvironment configurable) {
            Map<String, String> driverProperties = Binder.get(configurable)
                .bind("spring.datasource.hikari.data-source-properties", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
            for (String key : driverProperties.keySet()) {
                if (key.toLowerCase(Locale.ROOT).startsWith("ssl")) {
                    throw refused("spring.datasource.hikari.data-source-properties", "sets the driver property " + key, rootCert);
                }
            }
        }
    }

    private static void verify(String property, String url, String rootCert) {
        Properties parsed = Driver.parseURL(url, null);
        if (parsed == null) {
            throw refused(property, "is not a PostgreSQL JDBC URL", rootCert);
        }
        if (!"verify-full".equals(parsed.getProperty("sslmode"))) {
            throw refused(property, "does not set sslmode=verify-full", rootCert);
        }
        if (!rootCert.equals(parsed.getProperty("sslrootcert"))) {
            throw refused(property, "does not set sslrootcert to " + ROOT_CERT_VARIABLE, rootCert);
        }
        for (String name : parsed.stringPropertyNames()) {
            for (String forbidden : FORBIDDEN) {
                if (name.equalsIgnoreCase(forbidden)) {
                    throw refused(property, "sets " + forbidden, rootCert);
                }
            }
        }
    }

    private static IllegalStateException refused(String property, String reason, String rootCert) {
        return new IllegalStateException(property + " " + reason + ": the database connection must use sslmode=verify-full"
            + " with sslrootcert=" + rootCert + " (" + ROOT_CERT_VARIABLE + ") and no sslfactory, sslhostnameverifier"
            + " or sslpasswordcallback");
    }
}
