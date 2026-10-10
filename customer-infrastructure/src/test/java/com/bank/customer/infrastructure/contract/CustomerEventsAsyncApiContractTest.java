package com.bank.customer.infrastructure.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Shape and versioning rules of the AsyncAPI contract for evt.cus.customer.v1 (ADR-019). */
class CustomerEventsAsyncApiContractTest {

    private static final Path SPEC = Path.of("..", "api", "asyncapi", "svc-cus-profile-kyc.yaml");
    private static final Pattern TOPIC_VERSION = Pattern.compile("\\.v(\\d+)$");

    /**
     * The topic major changes only for key, partition-count or cleanup changes
     * (ADR-019 s5; a breaking change to one event is a new eventType ...v2 on
     * the same topic). The major of info.version equals the aggregate topic's
     * .vN suffix.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theContractMajorVersionEqualsTheTopicsVersionSuffix() throws IOException {
        Map<String, Object> spec = new Yaml().load(Files.readString(SPEC));
        String version = (String) ((Map<String, Object>) spec.get("info")).get("version");
        Set<String> suffixes = new TreeSet<>();
        ((Map<String, Object>) spec.get("channels")).values().forEach(channel -> {
            Matcher matcher = TOPIC_VERSION.matcher((String) ((Map<String, Object>) channel).get("address"));
            assertThat(matcher.find()).as("topic %s ends in .vN", ((Map<String, Object>) channel).get("address")).isTrue();
            suffixes.add(matcher.group(1));
        });

        assertThat(suffixes).as("one topic version across the contract").hasSize(1);
        assertThat(version.split("\\.")[0]).as("info.version %s major", version).isEqualTo(suffixes.iterator().next());
    }

    private static final String TOPIC = "evt.cus.customer.v1";
    private static final String EVENT_HEADERS = "./common/event-envelope.yaml#/EventHeaders";

    /**
     * ADR-019 s1 and the catalog checker (asyncapi-catalog 44837cc): one
     * channel for the customer aggregate, address = bindings.kafka.topic =
     * evt.cus.customer.v1, carrying every customer event type once.
     */
    @Test
    @SuppressWarnings("unchecked")
    void oneChannelCarriesEveryEventOfTheCustomerAggregate() throws IOException {
        Map<String, Object> spec = new Yaml().load(Files.readString(SPEC));
        Map<String, Object> channels = (Map<String, Object>) spec.get("channels");

        assertThat(channels).as("one channel per aggregate").hasSize(1);
        Map<String, Object> channel = (Map<String, Object>) channels.values().iterator().next();
        assertThat(channel.get("address")).isEqualTo(TOPIC);
        assertThat(((Map<String, Object>) ((Map<String, Object>) channel.get("bindings")).get("kafka")).get("topic"))
            .isEqualTo(TOPIC);
        List<String> eventTypes = new ArrayList<>();
        for (Object ref : ((Map<String, Object>) channel.get("messages")).values()) {
            eventTypes.add(payloadEventType(spec, message(spec, ref)));
        }
        assertThat(eventTypes).as("eventTypes are unique on the channel").doesNotHaveDuplicates();
        assertThat(eventTypes).containsExactlyInAnyOrder(
            "Customer.Customer.Created.v1", "Customer.Customer.ContactUpdated.v1",
            "Customer.Customer.CreditLimitUpdated.v1", "Customer.Customer.CreditReserved.v1",
            "Customer.Customer.CreditReleased.v1", "Customer.Customer.CreditScoreUpdated.v1",
            "Customer.Customer.KycStatusChanged.v1");
        ((Map<String, Object>) spec.get("operations")).forEach((id, operation) ->
            assertThat((Map<String, Object>) ((Map<String, Object>) operation).get("channel"))
                .as("operation %s", id).containsEntry("$ref", "#/channels/" + channels.keySet().iterator().next()));
    }

    /**
     * Each message's headers are allOf [ common EventHeaders, eventType const ]
     * with the same const as the payload, so consumers can route on the
     * eventType record header without parsing the value (ADR-019 s3).
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyMessagePinsItsEventTypeInTheHeadersAndThePayload() throws IOException {
        Map<String, Object> spec = new Yaml().load(Files.readString(SPEC));
        Map<String, Object> channel = (Map<String, Object>) ((Map<String, Object>) spec.get("channels")).values()
            .iterator().next();
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        assertThat((Map<String, Object>) schemas.get("EventHeaders")).containsEntry("$ref", EVENT_HEADERS);

        for (Object ref : ((Map<String, Object>) channel.get("messages")).values()) {
            Map<String, Object> message = message(spec, ref);
            List<Map<String, Object>> headers = (List<Map<String, Object>>) ((Map<String, Object>) message.get("headers")).get("allOf");
            assertThat(headers).as("%s headers allOf", message.get("name")).hasSize(2);
            assertThat(headers.get(0)).containsEntry("$ref", "#/components/schemas/EventHeaders");
            Map<String, Object> eventType = (Map<String, Object>) ((Map<String, Object>) headers.get(1).get("properties"))
                .get("eventType");
            assertThat(eventType).as("%s header eventType", message.get("name"))
                .containsEntry("const", payloadEventType(spec, message));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> message(Map<String, Object> spec, Object ref) {
        String pointer = (String) ((Map<String, Object>) ref).get("$ref");
        assertThat(pointer).startsWith("#/components/messages/");
        Map<String, Object> messages = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("messages");
        return (Map<String, Object>) messages.get(pointer.substring("#/components/messages/".length()));
    }

    @SuppressWarnings("unchecked")
    private static String payloadEventType(Map<String, Object> spec, Map<String, Object> message) {
        List<Map<String, Object>> payload = (List<Map<String, Object>>) ((Map<String, Object>) message.get("payload")).get("allOf");
        Map<String, Object> properties = (Map<String, Object>) payload.get(1).get("properties");
        return (String) ((Map<String, Object>) properties.get("eventType")).get("const");
    }

    /** Additive: CreditReserved and CreditReleased name the loan through an optional reference (absent when untracked). */
    @Test
    @SuppressWarnings("unchecked")
    void creditEventsDeclareAnOptionalReference() throws IOException {
        Map<String, Object> spec = new Yaml().load(Files.readString(SPEC));
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        for (String name : List.of("CustomerCreditReservedData", "CustomerCreditReleasedData")) {
            Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
            Map<String, Object> reference = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("reference");
            assertThat(reference).as("%s.reference", name).isNotNull().containsEntry("type", "string");
            assertThat((List<String>) schema.get("required")).as("%s required", name).doesNotContain("reference");
        }
    }

    /**
     * The contract is unreleased (absent on main, topics not created, catalog
     * #11 unmerged): edits such as the credit score removal stay at 1.0.0
     * rather than being labelled a compatible 1.1.0.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theUnreleasedContractStaysAtItsFirstVersion() throws IOException {
        String text = Files.readString(SPEC);
        Map<String, Object> spec = new Yaml().load(text);

        assertThat(((Map<String, Object>) spec.get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(text).doesNotContain("1.1.0");
    }
}
