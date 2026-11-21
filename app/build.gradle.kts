plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "online.chatsecreto.copiloto"
    compileSdk = 34

    signingConfigs {
        // Chave fixa: sem ela cada build teria assinatura diferente e o Android recusaria a atualização.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "online.chatsecreto.copiloto"
        minSdk = 26
        targetSdk = 34
        // O CI passa -PverCode=<número do build>; o app compara com a release mais nova.
        versionCode = (project.findProperty("verCode") as String?)?.toInt() ?: 1
        versionName = "1.$versionCode"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
