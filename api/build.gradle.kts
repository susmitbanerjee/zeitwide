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

group = "com.zeitwide"
version = "0.1.0-SNAPSHOT"

// Spring Boot's BOM pins an older OpenTelemetry; the extension must match the agent's SDK.
extra["opentelemetry.version"] =
    libs.versions.opentelemetry.sdk
        .get()

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// The OpenTelemetry agent's trace sampling extension. The agent loads it in its own class
// loader, so it is a separate jar compiled against the agent's SDK, not part of the app.
val otelExtension: SourceSet = sourceSets.create("otelExtension")
val otelAgent: Configuration by configurations.creating

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.context.support)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.caffeine)
    implementation(libs.bucket4j.postgresql)
    implementation(libs.java.json.canonicalization)
    implementation(libs.apache.httpclient5)
    implementation(libs.spring.ai.starter.mcp.server.webmvc)
    implementation(libs.micrometer.registry.otlp)
    runtimeOnly(libs.postgresql)
    developmentOnly(libs.spring.boot.docker.compose)

    errorprone(libs.errorprone.core)

    otelAgent(libs.opentelemetry.javaagent)
    "otelExtensionCompileOnly"(libs.opentelemetry.sdk.trace)
    "otelExtensionCompileOnly"(libs.opentelemetry.sdk.extension.autoconfigure.spi)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jqwik)
    testImplementation(otelExtension.output)
    testImplementation(libs.opentelemetry.sdk.trace)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.opentelemetry.sdk.extension.autoconfigure.spi)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.test {
    useJUnitPlatform()
    // The API refuses to start without a token secret (no default), so tests bring their own.
    environment("ZEITWIDE_AUTH_TOKEN_SECRET", "test-only-secret-0123456789abcdef0123456789")
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
val coverageExclusions = listOf("com/zeitwide/api/ZeitwideApiApplication.class")

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

val otelExtensionJar by tasks.registering(Jar::class) {
    archiveBaseName = "zeitwide-otel-extension"
    archiveVersion = ""
    from(otelExtension.output)
}

// The agent and its extension, at fixed names, for the image's /otel directory.
val otelFiles by tasks.registering(Sync::class) {
    from(otelAgent) { rename { "opentelemetry-javaagent.jar" } }
    from(otelExtensionJar)
    into(layout.buildDirectory.dir("otel"))
}

// Distroless Java 21, non-root, pinned by digest. deploy/gcp/deploy-images.sh sets the
// target image and tag.
jib {
    // Like bootJar: leaves out developmentOnly dependencies such as Docker Compose support.
    configurationName = "productionRuntimeClasspath"
    from {
        image = "gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d"
    }
    extraDirectories {
        paths {
            path {
                setFrom(
                    layout.buildDirectory
                        .dir("otel")
                        .get()
                        .asFile
                        .toPath(),
                )
                into = "/otel"
            }
        }
    }
    container {
        // The agent reads its OTEL_* settings from the Cloud Run environment (deploy/gcp).
        jvmFlags =
            listOf(
                "-javaagent:/otel/opentelemetry-javaagent.jar",
                "-Dotel.javaagent.extensions=/otel/zeitwide-otel-extension.jar",
            )
        ports = listOf("8080")
        user = "nonroot"
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}

// Jib 3.5 still reads Task.project while it runs.
tasks.matching { it.name.startsWith("jib") }.configureEach {
    notCompatibleWithConfigurationCache("Jib reads Task.project at execution time")
    dependsOn(otelFiles)
}
