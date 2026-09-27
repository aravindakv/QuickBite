import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    namespace = "com.quickbite.app"
    compileSdk {
        version = release(libsCatalog.findVersion("compileSdk").get().requiredVersion.toInt())
    }

    defaultConfig {
        applicationId = "com.quickbite.app"
        minSdk = libsCatalog.findVersion("minSdk").get().requiredVersion.toInt()
        targetSdk = libsCatalog.findVersion("targetSdk").get().requiredVersion.toInt()
        versionCode = libsCatalog.findVersion("appVersionCode").get().requiredVersion.toInt()
        versionName = libsCatalog.findVersion("appVersionName").get().requiredVersion

        // AppAuth's redirect receiver activity is registered for this scheme:
        manifestPlaceholders["appAuthRedirectScheme"] = "com.quickbite.app"

        buildConfigField("String", "API_BASE_URL", "\"http://localhost:8000\"")
        buildConfigField("String", "WS_BASE_URL", "\"ws://localhost:8000\"")
        buildConfigField("String", "ISSUER", "\"http://localhost:8180/realms/quickbite\"")
        buildConfigField("String", "CLIENT_ID", "\"android-app\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.net.appauth)
    implementation(libs.androidx.browser)                  // Custom Tabs
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid.android)            // OpenStreetMap: no API key needed

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}