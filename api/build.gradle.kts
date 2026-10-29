import com.diffplug.spotless.LineEnding
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.jib)
}

group = "com.settlecal"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.caffeine)
    implementation(libs.bucket4j.postgresql)
    runtimeOnly(libs.postgresql)
    developmentOnly(libs.spring.boot.docker.compose)

    errorprone(libs.errorprone.core)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
    }
}

tasks.test {
    useJUnitPlatform()
    // The conformance suite reads ../conformance (cases and golden dataset). Declared as an
    // input so a changed case reruns the tests instead of reusing a cached pass.
    inputs
        .dir(layout.projectDirectory.dir("../conformance"))
        .withPropertyName("conformance")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// The Spring Boot entry point only wires the context; tests start the context directly.
val coverageExclusions = listOf("com/settlecal/api/SettleCalApiApplication.class")

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
    lineEndings = LineEnding.UNIX
    java {
        googleJavaFormat(
            libs.versions.google.java.format
                .get(),
        )
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        ktlint()
    }
}

// Distroless Java 21, non-root, pinned by digest. deploy/gcp/deploy-images.sh sets the
// target image and tag.
jib {
    // Like bootJar: leaves out developmentOnly dependencies such as Docker Compose support.
    configurationName = "productionRuntimeClasspath"
    from {
        image = "gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d"
    }
    container {
        ports = listOf("8080")
        user = "nonroot"
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}

// Jib 3.5 still reads Task.project while it runs.
tasks.matching { it.name.startsWith("jib") }.configureEach {
    notCompatibleWithConfigurationCache("Jib reads Task.project at execution time")
}
