package com.quickbite.order;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

@AnalyzeClasses(packages = "com.quickbite.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The domain is the core: it must not know about HTTP, Kafka, or remote clients. */
    @ArchTest
    static final ArchRule domainIsIndependent = noClasses().that().resideInAPackage("..order.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..order.api..", "..order.app..", "..order.client..", "..order.messaging..",
                    "org.springframework.web..", "org.springframework.kafka..");

    /** Remote clients are infrastructure: they must not reach into the web layer. */
    @ArchTest
    static final ArchRule clientsDontUseApi = noClasses().that().resideInAPackage("..order.client..")
            .should().dependOnClassesThat().resideInAPackage("..order.api..");

    /** Constructor injection only: immutable, testable, no hidden dependencies. */
    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}