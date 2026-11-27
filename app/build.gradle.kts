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
