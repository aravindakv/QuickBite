plugins { `kotlin-dsl` }

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${libs.versions.springBoot.get()}")
    implementation("io.spring.gradle:dependency-management-plugin:${libs.versions.depMgmt.get()}")
}