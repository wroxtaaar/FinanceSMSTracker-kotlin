import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Firebase is configured only when app/google-services.json exists, so the
// project still builds without it (the in-app update check then does nothing).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.financesmstracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.financesmstracker"
        minSdk = 26
        targetSdk = 37
        // GitHub Actions sets these on every release build so each APK has a
        // higher versionCode than the last (Android refuses same/lower updates).
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FINANCE_SYNC_URL", "\"" + (System.getenv("FINANCE_SYNC_URL") ?: "") + "\"")
        buildConfigField("String", "FINANCE_SYNC_TOKEN", "\"" + (System.getenv("FINANCE_SYNC_TOKEN") ?: "") + "\"")
    }

    buildFeatures { buildConfig = true }

    // Shared signing: Android Studio reads finance-signing.properties;
    // GitHub Actions supplies the equivalent values through environment variables.
    val signingPropertiesFile = rootProject.file("finance-signing.properties")
    val signingProperties = Properties()
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { signingProperties.load(it) }
    }

    fun signingValue(propertyName: String, environmentName: String): String? =
        System.getenv(environmentName)?.takeIf { it.isNotBlank() }
            ?: signingProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

    val signingKeystorePath = signingValue("storeFile", "FINANCE_KEYSTORE_PATH")
    val signingKeyAlias = signingValue("keyAlias", "FINANCE_KEY_ALIAS")
    val signingStorePassword = signingValue("storePassword", "FINANCE_KEYSTORE_PASSWORD")
    val signingKeyPassword = signingValue("keyPassword", "FINANCE_KEY_PASSWORD")

    val hasSharedSigning = !signingKeystorePath.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

    if (hasSharedSigning) {
        signingConfigs {
            create("shared") {
                storeFile = rootProject.file(signingKeystorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasSharedSigning) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
        release {
            optimization {
                enable = false
            }
            if (hasSharedSigning) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // API-only library in every variant; the full SDK (the part that actually
    // checks for and installs updates) ships in release builds only.
    implementation(libs.firebase.appdistribution.api)
    releaseImplementation(libs.firebase.appdistribution)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
