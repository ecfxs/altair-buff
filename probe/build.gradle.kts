plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.altair.probe"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.altair.probe"
        minSdk = 26
        // 设计文档 2.5 节：取 30 以同时兼容 Android 13 与 11，
        // 并规避 POST_NOTIFICATIONS / SCHEDULE_EXACT_ALARM 两代权限收紧。
        targetSdk = 30
        versionCode = 38
        versionName = "0.24.14"
    }

    // 显式指定工作区内的 keystore。
    // 原因：AGP 默认会在 ~/.android 下创建 debug.keystore，而在受限环境里
    // 该目录不可写。用自己的 keystore 同时也让构建不依赖用户主目录。
    signingConfigs {
        create("probe") {
            storeFile = rootProject.file("keystore/probe.jks")
            storePassword = "android"
            keyAlias = "probe"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("probe")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("probe")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// 刻意零依赖：
//   - 并发用 java.lang.Thread，不用 coroutines
//   - UI 用 android.app.Activity + 系统主题，不用 appcompat
//   - 只用标准 Android API，不用 core-ktx
// 好处：APK 极小、构建快、彻底避开 compileSdk 版本冲突、
//       且不引入任何可能被云手机环境影响的第三方代码。
dependencies {
}
