package com.bank.customer.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Keycloak token call (client_secret_basic) and every other HTTP client
 * must never have their requests logged: observability's log masking does
 * not cover OAuth token requests. No Spring profile and no chart value may
 * switch a logger to DEBUG or TRACE (Spring's web client and HTTP loggers,
 * the JDK HttpURLConnection, Apache HttpClient wire logs, or the root).
 */
class NoWireLoggingConfigurationTest {

    private static final Pattern VERBOSE = Pattern.compile("(?i)^(debug|trace|all)$");
    private static final Pattern VERBOSE_ENV = Pattern.compile(
        "(?i)LOGGING_LEVEL_[A-Z0-9_]*[\"']?\\s*(?:[:=]|\\R\\s*value:)\\s*[\"']?(debug|trace|all)\\b");
    private static final Pattern VERBOSE_PROPERTY = Pattern.compile("(?i)logging\\.level\\.[\\w.]*\\s*[=:]\\s*[\"']?(debug|trace|all)\\b");

    @Test
    void noApplicationProfileTurnsOnDebugOrTraceLogging() throws IOException {
        List<Path> profiles;
        try (Stream<Path> files = Files.list(resources())) {
            profiles = files.filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml")).toList();
        }
        assertThat(profiles).isNotEmpty();
        for (Path profile : profiles) {
            for (Object document : new Yaml().loadAll(Files.readString(profile))) {
                assertThat(verboseLevels(document, "")).as(profile.getFileName().toString()).isEmpty();
            }
        }
    }

    @Test
    void theChartSetsNoDebugOrTraceLoggingEnvironment() throws IOException {
        Path chart = repositoryRoot().resolve("deploy/helm/customer-profile-kyc-service");
        List<String> findings = new ArrayList<>();
        try (Stream<Path> files = Files.walk(chart)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(file);
                if (VERBOSE_ENV.matcher(text).find() || VERBOSE_PROPERTY.matcher(text).find()) {
                    findings.add(chart.relativize(file).toString());
                }
            }
        }
        assertThat(findings).as("chart files that enable DEBUG/TRACE logging").isEmpty();
    }

    @Test
    void theGuardCatchesAVerboseLevel() {
        assertThat(verboseLevels(new Yaml().load("logging:\n  level:\n    org.springframework.web.client: DEBUG\n"), ""))
            .containsExactly("logging.level.org.springframework.web.client=DEBUG");
        assertThat(VERBOSE_ENV.matcher("- name: LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB\n  value: \"TRACE\"").find()).isTrue();
        assertThat(VERBOSE_PROPERTY.matcher("logging.level.root=debug").find()).isTrue();
    }

    private static List<String> verboseLevels(Object node, String path) {
        List<String> found = new ArrayList<>();
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> found.addAll(verboseLevels(value, path.isEmpty() ? key.toString() : path + "." + key)));
        } else if (node != null && path.startsWith("logging.level") && VERBOSE.matcher(node.toString().trim()).matches()) {
            found.add(path + "=" + node);
        }
        return found;
    }

    private static Path resources() {
        return Stream.of("src/main/resources", "customer-bootstrap/src/main/resources").map(Path::of)
            .filter(Files::isDirectory).findFirst().orElseThrow();
    }

    private static Path repositoryRoot() {
        return Stream.of(Path.of("."), Path.of("..")).filter(p -> Files.isDirectory(p.resolve("deploy/helm")))
            .findFirst().orElseThrow().toAbsolutePath().normalize();
    }
}
