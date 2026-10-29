import java.io.File
import java.util.Properties

// 发布签名凭据（私钥不进仓库）：先读环境变量，再读仓库外的 shuxue-signing.properties；
// 都没有就回退 debug 签名，任何人都能从源码构建。
// 官方分发版必须用固定私钥，否则用户升级时会被系统以「签名冲突」拒绝。
val signingProps = Properties().apply {
    listOf(
        File(System.getProperty("user.home"), ".hermes/workspace/weiqi-tv/secure/shuxue-signing.properties"),
        rootProject.file("signing.properties"),
    ).firstOrNull { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName) ?: signingProps.getProperty(propName)

val releaseStoreFile = signingValue("HEIZHU_SHUXUE_STORE_FILE", "storeFile")
val releaseStorePass = signingValue("HEIZHU_SHUXUE_STORE_PASS", "storePassword")
val releaseKeyAlias = signingValue("HEIZHU_SHUXUE_KEY_ALIAS", "keyAlias")
val releaseKeyPass = signingValue("HEIZHU_SHUXUE_KEY_PASS", "keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePass, releaseKeyAlias, releaseKeyPass)
    .all { !it.isNullOrBlank() } && File(releaseStoreFile!!).isFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mathking.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mathking.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(releaseStoreFile!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("⚠️ 未配置发布签名，release 将使用 debug 签名：此包不能与官方版互相覆盖安装")
                signingConfigs.getByName("debug")
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    // 只用到 core 生成二维码，不引 android 版
    implementation("com.google.zxing:core:3.5.3")
}
