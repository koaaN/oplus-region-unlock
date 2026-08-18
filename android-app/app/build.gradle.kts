plugins {
    id("com.android.application")
}

val appVersionCode = System.getenv("REGION_UNLOCK_VERSION_CODE")?.toIntOrNull() ?: 4
val appVersionName = System.getenv("REGION_UNLOCK_VERSION_NAME") ?: "0.4.0"

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

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
}
