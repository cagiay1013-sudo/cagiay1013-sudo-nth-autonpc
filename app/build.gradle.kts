plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    compileSdkVersion(33)

    defaultConfig {
        applicationId = "com.example.nthautonpc"
        minSdkVersion(30)
        targetSdkVersion(30)
        versionCode = 1
        versionName = "1.0"
        // Chỉ giữ thư viện cho điện thoại ARM (bỏ x86): APK nhẹ hơn nhiều
        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a")) }
        resConfigs("vi", "en")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true       // R8: bỏ code thừa, rút gọn
            isShrinkResources = true     // bỏ tài nguyên không dùng
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")   // ký bằng khóa debug để cài được ngay
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
