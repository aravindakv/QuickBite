plugins { id("quickbite.spring-service") }

dependencies {
    implementation(project(":libs:common"))
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
