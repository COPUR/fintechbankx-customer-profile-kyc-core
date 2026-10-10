package com.bank.customer.infrastructure.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerContextOpenApiContractTest {

    @Test
    void shouldDefineImplementedCustomerEndpoints() throws IOException {
        String spec = loadSpec();

        assertThat(spec).doesNotContain("paths: {}");
        assertThat(spec).contains("\n  /api/v1/customers:\n");
        assertThat(spec).contains("\n  /api/v1/customers/{customerId}:\n");
        assertThat(spec).contains("\n  /api/v1/customers/{customerId}/credit-limit:\n");
        assertThat(spec).contains("\n  /api/v1/customers/{customerId}/credit/reserve:\n");
        assertThat(spec).contains("\n  /api/v1/customers/{customerId}/credit/release:\n");
        assertThat(spec).contains("\n  /api/v1/customers/{customerId}/identity-link:\n");
    }

    /**
     * Platform contract addendum 2026-10-08: DPoP binds open-finance TPP
     * tokens only. This service is called with internal client-credentials,
     * staff and first-party customer tokens, so the contract must not promise
     * a DPoP check the service does not make.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theDpopHeaderIsOptionalAndSaysWhyThisServiceDoesNotVerifyIt() throws IOException {
        java.util.Map<String, Object> spec = new org.yaml.snakeyaml.Yaml().load(loadSpec());
        java.util.Map<String, Object> dpop = (java.util.Map<String, Object>) ((java.util.Map<String, Object>)
            ((java.util.Map<String, Object>) spec.get("components")).get("parameters")).get("DPoP");

        assertThat(dpop).containsEntry("name", "DPoP").containsEntry("in", "header").containsEntry("required", false);
        assertThat((String) dpop.get("description")).contains("TPP").contains("not verify");
        java.util.Map<String, Object> paths = (java.util.Map<String, Object>) spec.get("paths");
        paths.forEach((path, item) -> ((java.util.Map<String, Object>) item).forEach((method, operation) -> {
            List<java.util.Map<String, Object>> security = (List<java.util.Map<String, Object>>)
                ((java.util.Map<String, Object>) operation).get("security");
            assertThat(security).as("%s %s security: bearer only, no dpopAuth alternative", method, path)
                .containsExactly(java.util.Map.of("bearerAuth", List.of()));
        }));
        java.util.Map<String, Object> schemes = (java.util.Map<String, Object>) ((java.util.Map<String, Object>)
            spec.get("components")).get("securitySchemes");
        assertThat(schemes).containsOnlyKeys("bearerAuth");
    }

    /** Review 5456301261: a replay is recognised only when the whole movement matches. */
    @Test
    @SuppressWarnings("unchecked")
    void reserveAndReleaseStateTheFullReplayKey() throws IOException {
        java.util.Map<String, Object> spec = new org.yaml.snakeyaml.Yaml().load(loadSpec());
        java.util.Map<String, Object> paths = (java.util.Map<String, Object>) spec.get("paths");
        for (String path : List.of("/api/v1/customers/{customerId}/credit/reserve",
                "/api/v1/customers/{customerId}/credit/release")) {
            java.util.Map<String, Object> post = (java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) paths.get(path)).get("post");
            assertThat(((String) post.get("description")).replaceAll("\\s+", " "))
                .as(path).contains("same key, movement type, amount, currency and reference");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void creditMovementsAnswerWithTheCreditPositionOnly() throws IOException {
        java.util.Map<String, Object> spec = new org.yaml.snakeyaml.Yaml().load(loadSpec());
        java.util.Map<String, Object> paths = (java.util.Map<String, Object>) spec.get("paths");

        for (String path : List.of("/api/v1/customers/{customerId}/credit/reserve",
                "/api/v1/customers/{customerId}/credit/release", "/api/v1/customers/{customerId}/credit")) {
            java.util.Map<String, Object> operations = (java.util.Map<String, Object>) paths.get(path);
            java.util.Map<String, Object> operation = (java.util.Map<String, Object>)
                operations.getOrDefault("post", operations.get("get"));
            java.util.Map<String, Object> ok = (java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) operation.get("responses")).get("200");
            java.util.Map<String, Object> schema = (java.util.Map<String, Object>) ((java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) ok.get("content")).get("application/json")).get("schema");
            assertThat(schema.get("$ref")).as(path).isEqualTo("#/components/schemas/CustomerCreditResponse");
        }
    }

    /** Release by reference (loan PR #14 review): the two new refusals are documented on the release 422. */
    @Test
    @SuppressWarnings("unchecked")
    void releaseDocumentsTheReservationRefusals() throws IOException {
        java.util.Map<String, Object> spec = new org.yaml.snakeyaml.Yaml().load(loadSpec());
        java.util.Map<String, Object> release = (java.util.Map<String, Object>) ((java.util.Map<String, Object>)
            ((java.util.Map<String, Object>) spec.get("paths")).get("/api/v1/customers/{customerId}/credit/release")).get("post");
        java.util.Map<String, Object> unprocessable = (java.util.Map<String, Object>)
            ((java.util.Map<String, Object>) release.get("responses")).get("422");

        assertThat((String) unprocessable.get("description"))
            .contains("CURRENCY_MISMATCH", "RELEASE_EXCEEDS_RESERVATION", "RESERVATION_NOT_FOUND");
        assertThat(((String) release.get("description")).replaceAll("\\s+", " "))
            .contains("untracked used credit")
            .contains("A release that carries a reference matching no reservation is always 422 RESERVATION_NOT_FOUND")
            .contains("Only a release without a reference releases untracked used credit")
            .doesNotContain("or whose reference matches no reservation");
        assertThat(((String) unprocessable.get("description")).replaceAll("\\s+", " "))
            .contains("RESERVATION_NOT_FOUND, the reference matches no reservation, or a release without a reference is larger than the untracked used credit");
    }

    /** The loan service is the consumer; its keys must be deterministic so a retry resends the same key. */
    @Test
    @SuppressWarnings("unchecked")
    void theIdempotencyKeyIsDocumentedAsDerivedFromTheLoan() throws IOException {
        java.util.Map<String, Object> spec = new org.yaml.snakeyaml.Yaml().load(loadSpec());
        java.util.Map<String, Object> key = (java.util.Map<String, Object>) ((java.util.Map<String, Object>)
            ((java.util.Map<String, Object>) spec.get("components")).get("parameters")).get("IdempotencyKey");

        assertThat((String) key.get("description"))
            .contains("deterministic")
            .contains("<loanId>:reserve")
            .contains("<loanId>:release")
            .contains("random");
    }

    /** Payments reads only the KYC fields; staff set the status. No personal data in either answer. */
    @Test
    @SuppressWarnings("unchecked")
    void theKycStatusEndpointsAnswerWithTheKycFieldsOnly() throws IOException {
        java.util.Map<String, Object> spec = new org.yaml.snakeyaml.Yaml().load(loadSpec());
        java.util.Map<String, Object> kyc = (java.util.Map<String, Object>)
            ((java.util.Map<String, Object>) spec.get("paths")).get("/api/v1/customers/{customerId}/kyc-status");

        assertThat(kyc).as("kyc-status path").containsKeys("get", "put");
        for (String method : List.of("get", "put")) {
            java.util.Map<String, Object> responses = (java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) kyc.get(method)).get("responses");
            java.util.Map<String, Object> schema = (java.util.Map<String, Object>) ((java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) ((java.util.Map<String, Object>) responses.get("200")).get("content"))
                    .get("application/json")).get("schema");
            assertThat(schema.get("$ref")).isEqualTo("#/components/schemas/KycStatusResponse");
            assertThat(responses).containsKey("404");
        }
        java.util.Map<String, Object> schemas = (java.util.Map<String, Object>)
            ((java.util.Map<String, Object>) spec.get("components")).get("schemas");
        java.util.Map<String, Object> response = (java.util.Map<String, Object>) schemas.get("KycStatusResponse");
        assertThat(((java.util.Map<String, Object>) response.get("properties")).keySet())
            .containsExactlyInAnyOrder("customerId", "kycVerified", "kycStatus", "kycSource", "verifiedAt");
        java.util.Map<String, Object> request = (java.util.Map<String, Object>) schemas.get("ChangeKycStatusRequest");
        java.util.Map<String, Object> status = (java.util.Map<String, Object>)
            ((java.util.Map<String, Object>) request.get("properties")).get("status");
        assertThat((List<String>) status.get("enum")).containsExactly("VERIFIED", "REJECTED");
    }

    private static String loadSpec() throws IOException {
        List<Path> candidates = List.of(
                Path.of("api/openapi/customer-context.yaml"),
                Path.of("../api/openapi/customer-context.yaml"),
                Path.of("../../api/openapi/customer-context.yaml"),
                Path.of("../../../api/openapi/customer-context.yaml")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }

        throw new IOException("Unable to locate customer-context.yaml");
    }
}
