plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.vaultbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.vaultbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.3")
    testImplementation("junit:junit:4.13.2")
    androidTestCompileOnly(files(
        "${android.sdkDirectory}/platforms/android-${android.compileSdk}/optional/android.test.base.jar",
        "${android.sdkDirectory}/platforms/android-${android.compileSdk}/optional/android.test.runner.jar"
    ))
}
