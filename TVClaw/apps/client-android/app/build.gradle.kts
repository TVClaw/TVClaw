import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use {
    localProperties.load(it)
}
val tvClawWsListenPort =
    localProperties.getProperty("tvclaw.ws.listen.port")?.trim()?.toIntOrNull() ?: 8765

val tvClawBrainHttpUrl =
    localProperties.getProperty("tvclaw.brain.http.url")?.trim()?.takeIf { it.isNotEmpty() } ?: ""

android {
    namespace = "com.tvclaw.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvclaw.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.0.22"
        buildConfigField("int", "TVCLAW_WS_LISTEN_PORT", "$tvClawWsListenPort")
        buildConfigField(
            "String",
            "TVCLAW_BRAIN_HTTP_URL",
            "\"${tvClawBrainHttpUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

tasks.register("printAppVersion") {
    doLast {
        println(
            "TVClaw built: debug-${android.defaultConfig.versionName} (versionCode ${android.defaultConfig.versionCode})",
        )
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
