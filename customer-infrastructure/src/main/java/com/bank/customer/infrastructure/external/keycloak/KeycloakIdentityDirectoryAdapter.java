package com.bank.customer.infrastructure.external.keycloak;

import com.bank.customer.domain.IdentityDirectoryUnavailableException;
import com.bank.customer.domain.IdentityLinkConflictException;
import com.bank.customer.domain.IdentityUserId;
import com.bank.customer.domain.IdentityUserNotFoundException;
import com.bank.customer.domain.port.out.IdentityDirectoryPort;
import com.bank.shared.kernel.domain.CustomerId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IdentityDirectoryPort} over the Keycloak admin REST API. Reads the
 * user, adds the customer_id attribute and writes the whole representation
 * back (Keycloak replaces the attribute map on update, so the other attributes
 * must be sent again). A user that already carries the customer id is not
 * written; one that carries another id is a conflict. The client's scoped
 * permission (Keycloak FGAP v2, identity 8f9024b) covers users in group
 * /customers only, so a 403 on reading the user is the same outcome as an
 * unknown user. This service only links existing users; it never creates
 * Keycloak users. The admin token is fetched with client_secret_basic (the
 * secret is never in a request body). Every other failure
 * fails closed with {@link IdentityDirectoryUnavailableException}.
 */
public class KeycloakIdentityDirectoryAdapter implements IdentityDirectoryPort {

    static final String CUSTOMER_ID_ATTRIBUTE = "customer_id";
    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityDirectoryAdapter.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final KeycloakAdminSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;
    private volatile AccessToken token;

    public KeycloakIdentityDirectoryAdapter(RestClient http, KeycloakAdminSettings settings, Clock clock,
                                            MeterRegistry meters) {
        this.http = http;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public void linkCustomer(IdentityUserId userId, CustomerId customerId) {
        try {
            Map<String, Object> user = readUser(userId);
            Map<String, Object> attributes = attributesOf(user);
            Object current = attributes.get(CUSTOMER_ID_ATTRIBUTE);
            if (current instanceof List<?> values && !values.isEmpty()) {
                if (values.equals(List.of(customerId.getValue()))) {
                    return;
                }
                throw new IdentityLinkConflictException();
            }
            attributes.put(CUSTOMER_ID_ATTRIBUTE, List.of(customerId.getValue()));
            user.put("attributes", attributes);
            http.put().uri(settings.userUrl(), userId.value())
                .headers(h -> h.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                token = null;
            }
            log.warn("Keycloak admin API answered {} while linking an identity user", e.getStatusCode().value());
            throw new IdentityDirectoryUnavailableException("Identity directory answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            log.warn("Keycloak admin API unreachable while linking an identity user: {}", e.getClass().getSimpleName());
            throw new IdentityDirectoryUnavailableException("Identity directory unreachable", e);
        }
    }

    private Map<String, Object> readUser(IdentityUserId userId) {
        try {
            Map<String, Object> user = http.get().uri(settings.userUrl(), userId.value())
                .headers(h -> h.setBearerAuth(accessToken()))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JSON_OBJECT);
            if (user == null) {
                throw new IdentityDirectoryUnavailableException("Identity directory returned no user representation");
            }
            return new LinkedHashMap<>(user);
        } catch (RestClientResponseException e) {
            // FGAP v2 (identity 8f9024b) scopes this client to users in group /customers:
            // a 403 means the user exists but is not a customer user, so it is "not found" here.
            int status = e.getStatusCode().value();
            if (status == HttpStatus.FORBIDDEN.value()) {
                countResponse(status);
                // Never the user id. If every link answers 422 after enabling, check the FGAP permission first.
                log.warn("Keycloak answered 403 reading an identity user; expected only for users outside /customers");
                throw new IdentityUserNotFoundException();
            }
            if (status == HttpStatus.NOT_FOUND.value()) {
                countResponse(status);
                throw new IdentityUserNotFoundException();
            }
            throw e;
        }
    }

    /** identity.directory.responses{status}: reads answered 403 (outside /customers) or 404 (unknown user). */
    private void countResponse(int status) {
        Counter.builder("identity.directory.responses")
            .description("Keycloak user reads that ended as not a customer user (403) or unknown user (404)")
            .tag("status", Integer.toString(status))
            .register(meters)
            .increment();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> user) {
        Object attributes = user.get("attributes");
        return attributes instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private String accessToken() {
        AccessToken current = token;
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.value();
        }
        // client_secret_basic: the credential is only in the Authorization header, never in the body,
        // so nothing that logs request bodies can capture it (observability masking does not cover it).
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        Map<String, Object> response = http.post().uri(settings.tokenUrl())
            .header(HttpHeaders.AUTHORIZATION, basicCredentials(settings.clientId(), settings.clientSecret()))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JSON_OBJECT);
        if (response == null || !(response.get("access_token") instanceof String value)) {
            throw new IdentityDirectoryUnavailableException("Identity directory returned no access token");
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
        // Refresh 30 s early so a token never expires in flight.
        token = new AccessToken(value, clock.instant().plusSeconds(Math.max(0, expiresIn - 30)));
        return value;
    }

    /** RFC 6749 section 2.3.1: form-urlencode id and secret, then base64 "id:secret". */
    static String basicCredentials(String clientId, String clientSecret) {
        String pair = URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":"
            + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

        private record AccessToken(String value, Instant refreshAt) {
    }
}
