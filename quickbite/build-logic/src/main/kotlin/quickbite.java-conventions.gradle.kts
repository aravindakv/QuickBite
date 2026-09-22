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