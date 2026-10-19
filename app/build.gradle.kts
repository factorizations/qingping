plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qingping"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qingping"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.7"
    }

    // 固定签名：qingping.jks 随仓库分发，保证每次云构建 APK 签名一致，
    // 手机上可覆盖安装/升级（不会出现 INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("qingping.jks")
            storePassword = "qingping"
            keyAlias = "qingping"
            keyPassword = "qingping"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 有意保持零第三方依赖：无 AndroidX / Room / kapt，
    // 让 GitHub Actions 云构建只依赖 Android SDK 与 Kotlin 编译器，最大化成功率。
}
