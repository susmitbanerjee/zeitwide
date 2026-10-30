package com.zeitwide.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.zeitwide.api", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  // Allowed to be empty until the first injected component exists.
  @ArchTest
  static final ArchRule noFieldInjection =
      NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

  @ArchTest static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  @ArchTest static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  @ArchTest static final ArchRule noJodaTime = NO_CLASSES_SHOULD_USE_JODATIME;

  @ArchTest
  static final ArchRule domainIsPlainJava =
      noClasses()
          .that()
          .resideInAPackage("com.zeitwide.api.*.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "java.sql..",
              "javax.sql..",
              "com.fasterxml.jackson..",
              "com.zeitwide.api.*.app..",
              "com.zeitwide.api.*.api..",
              "com.zeitwide.api.*.infra..");

  @ArchTest
  static final ArchRule useCasesDependOnPortsNotAdapters =
      noClasses()
          .that()
          .resideInAPackage("com.zeitwide.api.*.app..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.zeitwide.api.*.api..", "com.zeitwide.api.*.infra..");

  // Allowed to be empty until the first controller exists.
  @ArchTest
  static final ArchRule controllersDoNotTouchRepositories =
      noClasses()
          .that()
          .resideInAPackage("com.zeitwide.api.*.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.zeitwide.api.*.infra..")
          .allowEmptyShould(true);
}
