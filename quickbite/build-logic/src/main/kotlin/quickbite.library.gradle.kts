import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    id("quickbite.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<DependencyManagementExtension> {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${catalog.findVersion("springCloud").get().requiredVersion}")
    }
}