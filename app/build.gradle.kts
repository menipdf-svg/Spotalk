import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.spotalk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.spotalk"
        minSdk = 26
        targetSdk = 36
        // מספר הבנייה ב-GitHub — כדי לראות בהגדרות איזו גרסה מותקנת
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // חתימה: אם מוגדרים KEYSTORE_FILE ו-KEYSTORE_PASSWORD (למשל ב-CI) חותמים במפתח שלך (alias: spotalk).
    // בלי המשתנים — מפתח ה-debug הרגיל של Android Studio / המחשב שלך.
    signingConfigs {
        getByName("debug") {
            val secretFile = System.getenv("KEYSTORE_FILE")
            val secretPass = System.getenv("KEYSTORE_PASSWORD")
            if (!secretFile.isNullOrBlank() && !secretPass.isNullOrBlank() && file(secretFile).exists()) {
                storeFile = file(secretFile)
                storePassword = secretPass
                keyAlias = "spotalk"
                keyPassword = secretPass
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Spotify App Remote SDK (שליטה באפליקציית Spotify המותקנת)
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.browser:browser:1.8.0")
    // MediaBrowserService / MediaSession — אפליקציית מדיה ל-Android Auto
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // טעינת תמונות (אמנים, אלבומים, פלייליסטים)
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
