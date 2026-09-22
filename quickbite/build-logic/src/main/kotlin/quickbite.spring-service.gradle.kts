import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("quickbite.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${catalog.findVersion("springCloud").get().requiredVersion}")
    }
}

dependencies {
    // Logs a warning if a property key we use was renamed in Boot 4. Remove once the logs are clean.
    runtimeOnly("org.springframework.boot:spring-boot-properties-migrator")
}

// Every service produces build/libs/app.jar -> one generic Dockerfile works for all (file 09)
tasks.named<BootJar>("bootJar") { archiveFileName.set("app.jar") }
tasks.named<Jar>("jar") { enabled = false }