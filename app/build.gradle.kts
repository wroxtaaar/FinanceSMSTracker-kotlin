plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FINANCE_SYNC_URL", "\"" + (System.getenv("FINANCE_SYNC_URL") ?: "") + "\"")
        buildConfigField("String", "FINANCE_SYNC_TOKEN", "\"" + (System.getenv("FINANCE_SYNC_TOKEN") ?: "") + "\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            optimization {
                enable = false
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}