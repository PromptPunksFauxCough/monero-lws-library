plugins {
    id("com.android.library") version "8.1.4"
    id("org.jetbrains.kotlin.android") version "1.9.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.21"
}

group = "org.monero.lws.lib"
version = "1.0.0"

android {
    namespace = "org.monero.lws.lib"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
