plugins {
    id("com.android.application")
}

val appVersionCode = System.getenv("REGION_UNLOCK_VERSION_CODE")?.toIntOrNull() ?: 3
val appVersionName = System.getenv("REGION_UNLOCK_VERSION_NAME") ?: "0.3.0"

android {
    namespace = "dev.op13.regionunlock.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.op13.regionunlock.app"
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
