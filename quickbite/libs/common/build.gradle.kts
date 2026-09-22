plugins { id("quickbite.library") }

sourceSets {
    main {
        java.srcDir("../../common")
    }
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-validation")

    // Optional features: compiled against, but only activated if the service has them on its classpath
    compileOnly("org.springframework.boot:spring-boot-starter-kafka")
    compileOnly("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}