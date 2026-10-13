plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dnsswitch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dnsswitch"
        minSdk = 24
        targetSdk = 34
        // 命名规范：年.月.本月更新次数 → 26.10.1 = 2026 年 10 月第 1 次更新
        // versionCode 取同义整数：年*10000 + 月*100 + 次数 = 261001（须单调递增）
        versionCode = 261001
        versionName = "26.10.1"
    }

    signingConfigs {
        create("release") {
            // 本地发布签名（keystore 已在 .gitignore 中，请勿提交到公开仓库）
            // 重新生成：keytool -genkeypair -alias dnsswitch -keyalg RSA -keysize 2048 \
            //   -validity 10000 -keystore keystore/dnsswitch-release.jks
            storeFile = file("keystore/dnsswitch-release.jks")
            storePassword = "dnsswitch"
            keyAlias = "dnsswitch"
            keyPassword = "dnsswitch"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
