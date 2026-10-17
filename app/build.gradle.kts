import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val pairingLocal = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.isFile) local.inputStream().use { load(it) }
}
fun pairingSetting(key: String, fallback: String): String = providers.environmentVariable(key).orNull
    ?: pairingLocal.getProperty(key) ?: fallback
val pairingApi = pairingSetting("PAIRING_API_BASE_URL", "https://pairing.invalid")
val pairingHost = pairingSetting("PAIRING_APP_LINK_HOST", "pairing.invalid")
val pairingPath = pairingSetting("PAIRING_APP_LINK_PATH", "/pair")
fun quotedConfig(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.pradertamas.uicapturetest"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.pradertamas.uicapturetest"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "PAIRING_API_BASE_URL", quotedConfig(pairingApi))
        buildConfigField("String", "PAIRING_APP_LINK_HOST", quotedConfig(pairingHost))
        buildConfigField("String", "PAIRING_APP_LINK_PATH", quotedConfig(pairingPath))
        manifestPlaceholders["pairingAppLinkHost"] = pairingHost
        manifestPlaceholders["pairingAppLinkPath"] = pairingPath

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
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
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
}
