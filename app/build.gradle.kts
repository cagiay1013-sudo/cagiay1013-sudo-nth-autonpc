plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    compileSdkVersion(33)

    defaultConfig {
        applicationId = "com.example.mousemapper"
        minSdkVersion(26)      // cần Android 8.0+: bắt con trỏ chuột (pointer capture) và cử chỉ giữ liên tục
        targetSdkVersion(30)
        versionCode = 1
        versionName = "1.0"
        resConfigs("vi", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// Không dùng thư viện ngoài nào: APK rất nhẹ.
