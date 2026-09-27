plugins { java }

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

tasks.withType<JavaCompile>().configureEach {
    // -parameters lets Spring bind @PathVariable/@RequestParam by name without annotations' value
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading") // silences Mockito's agent warning on modern JDKs
}

dependencies {
    // The Spring Boot Gradle plugin adds this automatically for services; libs:common has no such plugin.
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}