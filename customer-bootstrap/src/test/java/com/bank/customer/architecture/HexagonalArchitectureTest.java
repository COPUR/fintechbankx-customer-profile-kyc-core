package com.bank.customer.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four service guardrail rules (ADR-028), checked on every module's
 * production classes. This module sees the domain, application and
 * infrastructure modules, so the rules run here on {@code ./gradlew check}.
 */
@Tag("unit")
class HexagonalArchitectureTest {

    private static final String ROOT = "com.bank.customer";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String PORT_IN = ROOT + ".domain.port.in..";
    private static final String PORT_OUT = ROOT + ".domain.port.out..";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String INFRASTRUCTURE = ROOT + ".infrastructure..";

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
    }

    /** Rule 1: the domain depends on no layer above it and on no framework, persistence or messaging client. */
    @Test
    void domainIsFreeOfOuterLayersAndFrameworks() {
        noClasses().that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                APPLICATION, INFRASTRUCTURE,
                "org.springframework..", "jakarta.persistence..", "org.hibernate..",
                "org.apache.kafka..", "com.mongodb..", "org.bson..", "com.fasterxml.jackson..")
            .check(classes);
    }

    /** Rule 2: the application layer depends on no adapter. */
    @Test
    void applicationDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAPackage(INFRASTRUCTURE)
            .check(classes);
    }

    /** Rule 3a: inbound adapters drive use-case interfaces, never application classes. */
    @Test
    void inboundAdaptersDoNotDependOnApplicationClasses() {
        noClasses().that(areInboundAdapters())
            .should().dependOnClassesThat().resideInAPackage(APPLICATION)
            .check(classes);
    }

    /** Rule 3b: every inbound adapter goes through domain.port.in. */
    @Test
    void inboundAdaptersDependOnInboundPorts() {
        classes().that(areInboundAdapters())
            .should().dependOnClassesThat().resideInAPackage(PORT_IN)
            .check(classes);
    }

    /** Rule 4: out-port implementations are adapters. */
    @Test
    void outboundPortImplementationsLiveInInfrastructure() {
        classes().that().implement(resideInAPackage(PORT_OUT))
            .should().resideInAPackage(INFRASTRUCTURE)
            .check(classes);
    }

    /** Repositories and *Port interfaces of the domain sit in port.out. */
    @Test
    void outboundPortsSitInPortOut() {
        classes().that(areDomainInterfacesNamed("Repository", "Port"))
            .should().resideInAPackage(PORT_OUT)
            .check(classes);
    }

    /** *UseCase types are interfaces in port.in. */
    @Test
    void useCasesAreInterfacesInPortIn() {
        classes().that().haveSimpleNameEndingWith("UseCase")
            .should().beInterfaces().andShould().resideInAPackage(PORT_IN)
            .check(classes);
    }

    /** Rule 3 companion: each use case has an application implementation. */
    @Test
    void useCasesAreImplementedInTheApplicationLayer() {
        classes().that().implement(resideInAPackage(PORT_IN).and(nameEndsWith("UseCase")))
            .should().resideInAPackage(APPLICATION)
            .check(classes);
    }

    private static DescribedPredicate<JavaClass> areInboundAdapters() {
        return DescribedPredicate.describe("are controllers or message listeners in infrastructure", c ->
            c.getPackageName().startsWith(ROOT + ".infrastructure")
                && (c.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    || c.isAnnotatedWith("org.springframework.stereotype.Controller")
                    || c.getMethods().stream().anyMatch(m ->
                        m.isAnnotatedWith("org.springframework.kafka.annotation.KafkaListener"))));
    }

    private static DescribedPredicate<JavaClass> areDomainInterfacesNamed(String... suffixes) {
        return DescribedPredicate.describe("are domain interfaces named *" + String.join(" or *", suffixes), c ->
            c.isInterface() && c.getPackageName().startsWith(ROOT + ".domain")
                && java.util.Arrays.stream(suffixes).anyMatch(c.getSimpleName()::endsWith));
    }

    private static DescribedPredicate<JavaClass> nameEndsWith(String suffix) {
        return DescribedPredicate.describe("name ends with " + suffix, c -> c.getSimpleName().endsWith(suffix));
    }
}
