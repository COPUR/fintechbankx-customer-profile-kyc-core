package com.bank.customer.infrastructure.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Versioning rules of the AsyncAPI contract for evt.cus.customer.*. */
class CustomerEventsAsyncApiContractTest {

    private static final Path SPEC = Path.of("..", "api", "asyncapi", "svc-cus-profile-kyc.yaml");
    private static final Pattern TOPIC_VERSION = Pattern.compile("\\.v(\\d+)$");

    /**
     * A breaking change moves topics to .v(N+1) and the contract to major N+1;
     * a minor bump never hides a removed or newly required field. So the major
     * of info.version always equals the .vN suffix every topic carries.
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
