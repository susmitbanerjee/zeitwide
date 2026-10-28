package com.settlecal.api;

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

@AnalyzeClasses(
    packages = "com.settlecal.api",
    importOptions = ImportOption.DoNotIncludeTests.class)
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
          .resideInAPackage("com.settlecal.api.*.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "java.sql..",
              "javax.sql..",
              "com.fasterxml.jackson..",
              "com.settlecal.api.*.app..",
              "com.settlecal.api.*.api..",
              "com.settlecal.api.*.infra..");

  @ArchTest
  static final ArchRule useCasesDependOnPortsNotAdapters =
      noClasses()
          .that()
          .resideInAPackage("com.settlecal.api.*.app..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.settlecal.api.*.api..", "com.settlecal.api.*.infra..");

  // Allowed to be empty until the first controller exists.
  @ArchTest
  static final ArchRule controllersDoNotTouchRepositories =
      noClasses()
          .that()
          .resideInAPackage("com.settlecal.api.*.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.settlecal.api.*.infra..")
          .allowEmptyShould(true);
}
