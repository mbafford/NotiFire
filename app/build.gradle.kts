plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mbafford.notifire"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mbafford.notifire"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    // Release signing is only configured when a keystore is supplied, normally by
    // scripts/build-release.sh, which fetches it from 1Password. Debug builds are unaffected.
    val releaseKeystore = providers.gradleProperty("notifireKeystore").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("notifireKeystorePassword").get()
                keyAlias = "notifire"
                keyPassword = storePassword
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
}
