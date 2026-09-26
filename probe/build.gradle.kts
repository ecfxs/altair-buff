import java.util.Properties
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号与签名信任锚的唯一来源：文件缺失或字段缺失都必须失败，不能静默沿用旧版本号。
val versionFiles = rootProject.file("version.properties")
val projectVersionProperties = Properties().apply {
    check(versionFiles.isFile) { "缺少 version.properties（版本号与 releaseSignerSha256 的唯一来源）" }
    versionFiles.inputStream().use { load(it) }
}
val appVersionName = providers.gradleProperty("altairVersionName").orNull?.takeIf { it.isNotBlank() }
    ?: projectVersionProperties.getProperty("versionName")?.takeIf { it.isNotBlank() }
    ?: error("version.properties 缺少 versionName")
val appVersionCode = providers.gradleProperty("altairVersionCode").orNull?.toIntOrNull()
    ?: projectVersionProperties.getProperty("versionCode")?.trim()?.toIntOrNull()
    ?: error("version.properties 缺少合法的 versionCode")
val releaseSignerSha256 = projectVersionProperties.getProperty("releaseSignerSha256")?.takeIf { it.isNotBlank() }
    ?: error("version.properties 缺少 releaseSignerSha256")

val releaseSigningProperties = Properties().apply {
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.isFile) signingFile.inputStream().use { load(it) }
}

fun signingValue(environmentKey: String, propertyKey: String): String? =
    providers.environmentVariable(environmentKey).orNull?.takeIf { it.isNotBlank() }
        ?: releaseSigningProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("ALTAIR_STORE_FILE", "storeFile") ?: "keystore/probe.jks"
val releaseStorePassword = signingValue("ALTAIR_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ALTAIR_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ALTAIR_KEY_PASSWORD", "keyPassword")
val releaseSigningConfigured = releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null &&
    rootProject.file(releaseStoreFile).isFile
val releaseSignerSha256Configured = Regex("^[0-9a-fA-F]{64}$").matches(releaseSignerSha256)

android {
    namespace = "com.altair.probe"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.altair.probe"
        minSdk = 26
        // 设计文档 2.5 节：取 30 以同时兼容 Android 13 与 11，
        // 并规避 POST_NOTIFICATIONS / SCHEDULE_EXACT_ALARM 两代权限收紧。
        targetSdk = 30
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("probeRelease") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Debug 使用 Android 的 debug signing；不能复用正式用户的更新签名。
        }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("probeRelease")
            }
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
        htmlReport = true
        textReport = true
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf(
            "DiscouragedPrivateApi", // TouchAgent 使用的 InputManager 反射是当前 root 注入实现所需
            "InsecureBaseConfiguration", // Android Lint 仍对该 targetSdk 报旧基线；发布脚本检查成品 HTTPS-only 属性
            "ExpiredTargetSdkVersion", // 产品目标为红手指 Android 11/13，不走 Play 分发
            "StaticFieldLeak", // 单例只持有 applicationContext；Lint 无法推断 init() 的规范化
            "ObsoleteSdkInt", // 同一 APK 仍须维护 API 26+ 的显式前台服务路径
            "SetTextI18n", // 界面固定中文，无本地化目标
            "ViewConstructor", // PickView 只由代码实例化，不经 XML LayoutInflater
            "DrawAllocation", // 720p 悬浮层低频重绘的小型坐标对象
            "ClickableViewAccessibility" // PickView 自行处理触摸，另在代码中实现 performClick()
        )
    }
}

// 刻意零运行时依赖：并发用 Thread，UI 用系统 Activity + View，不引入 AppCompat/Compose。
dependencies {
}

val regressionClasses = layout.buildDirectory.dir("classes/regression")
val compileRegression by tasks.registering(JavaCompile::class) {
    dependsOn(tasks.named("compileDebugKotlin"), tasks.named("compileDebugJavaWithJavac"))
    source(fileTree("src/test/java") { include("**/*.java") })
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("intermediates/javac/debug/classes"),
        configurations.getByName("debugCompileClasspath")
    )
    destinationDirectory.set(regressionClasses)
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.register<JavaExec>("automationRegression") {
    group = "verification"
    description = "Runs the no-device automation regression harness."
    dependsOn(compileRegression)
    classpath = files(
        regressionClasses,
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("intermediates/javac/debug/classes"),
        configurations.getByName("debugRuntimeClasspath")
    )
    mainClass.set("com.altair.probe.AutomationRegression")
    jvmArgs("-ea")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails when release signing is not configured from a protected source."
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Set ALTAIR_STORE_FILE, ALTAIR_STORE_PASSWORD, " +
                "ALTAIR_KEY_ALIAS and ALTAIR_KEY_PASSWORD (or local signing.properties)."
        }
        check(releaseSignerSha256Configured) { "version.properties releaseSignerSha256 must be a 64-digit SHA-256." }
    }
}

tasks.configureEach {
    if (name == "assembleRelease") dependsOn(verifyReleaseSigning)
}
