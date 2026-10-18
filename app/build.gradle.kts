plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rahcare.autoreply"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rahcare.autoreply"
        minSdk = 26
        targetSdk = 34
        // প্রতিটি নতুন বিল্ডে CI থেকে -Pvc=রান নম্বর আসে, তাই নতুন APK আগেরটার ওপর আপডেট হিসেবে বসে
        versionCode = (findProperty("vc") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("autoreply.keystore")
            storePassword = System.getenv("KS_PASSWORD")
            keyAlias = "autoreply"
            keyPassword = System.getenv("KS_PASSWORD")
            storeType = "pkcs12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
