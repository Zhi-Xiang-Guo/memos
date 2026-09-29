package dev.memos.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.memos", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {
  @ArchTest
  static final ArchRule MODULES_ARE_ACYCLIC =
      slices()
          .matching("dev.memos.(*)..")
          .should()
          .beFreeOfCycles()
          .because("the modular monolith requires an acyclic module graph");

  @ArchTest
  static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
      noClasses()
          .that()
          .resideInAPackage("dev.memos.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..", "jakarta.persistence..", "java.sql..", "org.postgresql..")
          .because("memory-domain owns business invariants without framework coupling");

  @ArchTest
  static final ArchRule BUSINESS_MODULES_DO_NOT_DEPEND_ON_ADAPTERS =
      noClasses()
          .that()
          .resideInAnyPackage(
              "dev.memos.domain..",
              "dev.memos.governance..",
              "dev.memos.audit..",
              "dev.memos.ingestion..",
              "dev.memos.materialization..",
              "dev.memos.retrieval..",
              "dev.memos.context..",
              "dev.memos.answering..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("dev.memos.adapters..", "dev.memos.api..", "dev.memos.worker..");

  @ArchTest
  static final ArchRule ANSWERING_IS_FRAMEWORK_FREE =
      noClasses()
          .that()
          .resideInAPackage("dev.memos.answering..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "java.sql..",
              "java.net.http..",
              "tools.jackson..");

  @ArchTest
  static final ArchRule ANSWER_MODEL_DOES_NOT_DEPEND_ON_ORCHESTRATION =
      noClasses()
          .that()
          .resideInAPackage("dev.memos.answering.model..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("dev.memos.answering.service..", "dev.memos.answering.port..");

  @ArchTest
  static final ArchRule ANSWER_PORTS_DO_NOT_DEPEND_ON_SERVICES =
      noClasses()
          .that()
          .resideInAPackage("dev.memos.answering.port..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("dev.memos.answering.service..");

  @ArchTest
  static final ArchRule ANSWER_HTTP_DOES_NOT_ACCESS_PROVIDER_OR_DATABASE =
      noClasses()
          .that()
          .resideInAPackage("dev.memos.api.answering..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "dev.memos.adapters.answering..",
              "dev.memos.adapters.postgres..",
              "java.net.http..",
              "java.sql..");

  @ArchTest
  static final ArchRule ANSWER_ADAPTERS_DO_NOT_OWN_WIRING =
      noClasses()
          .that()
          .resideInAPackage("dev.memos.adapters.answering..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("dev.memos.adapters.spring..");

  @ArchTest
  static final ArchRule APPLICATIONS_ARE_SEPARATE =
      classes()
          .that()
          .resideInAPackage("dev.memos.api..")
          .should()
          .onlyDependOnClassesThat()
          .resideOutsideOfPackage("dev.memos.worker..")
          .andShould()
          .notBeAssignableTo(org.springframework.boot.CommandLineRunner.class);
}
