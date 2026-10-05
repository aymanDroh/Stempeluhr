plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// In GitHub Actions wird die Build-Nummer als versionCode übergeben (-PversionCode=…),
// damit jede neue APK als Update über die alte installiert werden kann.
val buildNumber = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Fester Signatur-Schlüssel (aus GitHub-Secrets). Ohne festen Schlüssel lassen sich
// spätere Versionen nicht als Update installieren.
val releaseKeystore = System.getenv("STEMPELUHR_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "de.droh.stempeluhr"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.droh.stempeluhr"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("STEMPELUHR_KEYSTORE_PASSWORD")
                keyAlias = "stempeluhr"
                keyPassword = System.getenv("STEMPELUHR_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // PDF-Text auslesen für den Import von Stundenzetteln
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}
