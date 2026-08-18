plugins {
    id("com.android.application")
}

val appVersionCode = System.getenv("REGION_UNLOCK_VERSION_CODE")?.toIntOrNull() ?: 4001
val appVersionName = System.getenv("REGION_UNLOCK_VERSION_NAME") ?: "0.4.1"
val releaseKeystore = System.getenv("REGION_UNLOCK_KEYSTORE")
val releaseStorePassword = System.getenv("REGION_UNLOCK_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("REGION_UNLOCK_KEY_ALIAS")
val releaseKeyPassword = System.getenv("REGION_UNLOCK_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystore,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.oplus.regionunlock.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.oplus.regionunlock.app"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    sourceSets {
        getByName("main").java.srcDir("../../src")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
}
