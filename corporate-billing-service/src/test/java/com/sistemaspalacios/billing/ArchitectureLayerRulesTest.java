package com.sistemaspalacios.billing;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.sistemaspalacios.billing", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureLayerRulesTest {

    @ArchTest
    static final ArchRule layered_architecture_is_respected = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy("..billing.web..")
            .layer("Service").definedBy("..billing.service..")
            .layer("Domain").definedBy("..billing.domain..")
            .layer("Repository").definedBy("..billing.repository..")
            .layer("Infrastructure").definedBy("..billing.infrastructure..")
            .whereLayer("Web").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Web", "Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Service", "Repository", "Infrastructure", "Web");

    @ArchTest
    static final ArchRule web_must_not_access_repository_directly = noClasses()
            .that().resideInAPackage("..billing.web..")
            .should().dependOnClassesThat().resideInAPackage("..billing.repository..");

    @ArchTest
    static final ArchRule no_cycles_between_packages = slices()
            .matching("com.sistemaspalacios.billing.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring_security = noClasses()
            .that().resideInAPackage("..billing.domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.security..");
}
