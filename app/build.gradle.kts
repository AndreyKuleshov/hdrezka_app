plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.hdrezka.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.hdrezka.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 10
        versionName = "1.9"
        buildConfigField("String", "START_URL", "\"https://serialy-fox-hdrezka.net/\"")
        // Откуда приложение берёт обновления: публичные GitHub Releases этого репозитория
        buildConfigField("String", "UPDATE_REPO", "\"AndreyKuleshov/hdrezka_app\"")
    }

    buildFeatures { buildConfig = true }

    // Постоянный ключ подписи берётся из переменных окружения (в CI — из GitHub Secrets).
    // Без него release подписывается debug-ключом, который у каждой машины свой.
    val releaseStore = System.getenv("SIGNING_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Код — 90% размера APK; R8 убирает неиспользуемое из appcompat и Kotlin
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            // Debug-сборка отлаживаемая (WebView debugging), поэтому по умолчанию подписана своим ключом
            // и не встанет поверх release. Для локальной проверки поверх установленной версии
            // (с сохранением cookies) — FOXTV_DEBUG_RELEASE_KEY=1.
            if (System.getenv("FOXTV_DEBUG_RELEASE_KEY") == "1") {
                signingConfigs.findByName("release")?.let { signingConfig = it }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
