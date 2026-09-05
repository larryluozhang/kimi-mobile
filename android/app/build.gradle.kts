import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置从 keystore.properties 读取（自用项目，文件不进 VCS）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "com.kimi.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kimi.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.7.2"
        // sherpa-onnx 原生库四 ABI 约 126MB；目标设备全是 arm64，只打 arm64-v8a
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val path = keystoreProps.getProperty("storeFile")
                ?: throw GradleException("keystore.properties 缺少 storeFile，无法签名 release 包")
            storeFile = file(path)
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // sherpa-onnx 离线语音识别（未发布到 Maven Central，用 GitHub releases 的本地 AAR）
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
