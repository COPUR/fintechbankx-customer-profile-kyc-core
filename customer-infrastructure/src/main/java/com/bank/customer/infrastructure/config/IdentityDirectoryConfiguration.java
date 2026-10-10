package com.bank.customer.infrastructure.config;

import com.bank.customer.domain.port.out.IdentityDirectoryPort;
import com.bank.customer.infrastructure.external.keycloak.DisabledIdentityDirectory;
import com.bank.customer.infrastructure.external.keycloak.KeycloakAdminSettings;
import com.bank.customer.infrastructure.external.keycloak.KeycloakIdentityDirectoryAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * Chooses the identity directory adapter: the Keycloak admin API when
 * fintechbankx.identity.admin.enabled is true, otherwise one that fails
 * closed (see {@link DisabledIdentityDirectory}).
 */
@Configuration
public class IdentityDirectoryConfiguration {

    @Bean
    @ConditionalOnProperty(name = "fintechbankx.identity.admin.enabled", havingValue = "true")
    IdentityDirectoryPort keycloakIdentityDirectory(
            @Value("${fintechbankx.identity.admin.base-url:}") String baseUrl,
            @Value("${fintechbankx.identity.admin.realm:}") String realm,
            @Value("${fintechbankx.identity.admin.client-id:}") String clientId,
            @Value("${fintechbankx.identity.admin.client-secret:}") String clientSecret,
            @Value("${fintechbankx.identity.admin.timeout:PT3S}") Duration timeout,
            Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters) {
        KeycloakAdminSettings settings = new KeycloakAdminSettings(baseUrl, realm, clientId, clientSecret, timeout);
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(settings.timeout());
        requests.setReadTimeout(settings.timeout());
        return new KeycloakIdentityDirectoryAdapter(RestClient.builder().requestFactory(requests).build(), settings, clock, meters);
    }

    @Bean
    @ConditionalOnProperty(name = "fintechbankx.identity.admin.enabled", havingValue = "false", matchIfMissing = true)
    IdentityDirectoryPort disabledIdentityDirectory() {
        return new DisabledIdentityDirectory();
    }
}
