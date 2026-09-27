import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val ablyKey: String = local.getProperty("ABLY_KEY")
    ?: (project.findProperty("ABLY_KEY") as? String)
    // 密钥不入库:来源为 local.properties(本地)或 ORG_GRADLE_PROJECT_ABLY_KEY(CI Secrets)
    ?: ""

// 腾讯地图 WebService Key 与签名 SecretKey（路线规划等 HTTP 接口使用）
val tencentMapKey: String = local.getProperty("TENCENT_MAP_KEY")
    ?: (project.findProperty("TENCENT_MAP_KEY") as? String)
    ?: ""
val tencentMapSk: String = local.getProperty("TENCENT_MAP_SK")
    ?: (project.findProperty("TENCENT_MAP_SK") as? String)
    ?: ""

// 统一签名(与 inklink-controller/inklink-host 拆分仓共享同一 keystore,双端可覆盖安装):
// 来源优先级:环境变量(CI Secrets) > local.properties > 默认 keystore 路径(密码必须注入)
val inklinkEnv = System.getenv()
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.inklink.controller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.inklink.controller"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ABLY_KEY", "\"$ablyKey\"")
        buildConfigField("String", "TENCENT_MAP_KEY", "\"$tencentMapKey\"")
        buildConfigField("String", "TENCENT_MAP_SK", "\"$tencentMapSk\"")
        manifestPlaceholders["TENCENT_MAP_KEY"] = tencentMapKey
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                inklinkEnv["INKLINK_KEYSTORE_PATH"]
                    ?: signingProps.getProperty("inklinkStoreFile")
                    ?: "keystore/inklink-release.keystore"
            )
            storePassword = inklinkEnv["INKLINK_STORE_PASSWORD"]
                ?: signingProps.getProperty("inklinkStorePassword") ?: ""
            keyAlias = inklinkEnv["INKLINK_KEY_ALIAS"]
                ?: signingProps.getProperty("inklinkKeyAlias") ?: "inklink"
            keyPassword = inklinkEnv["INKLINK_KEY_PASSWORD"]
                ?: signingProps.getProperty("inklinkKeyPassword") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation("com.google.android.material:material:1.11.0")

    // 腾讯地图 SDK（含基础库 foundation，提供 LatLng 等基础类）
    implementation("com.tencent.map:tencent-map-vector-sdk:6.13.0.260731.bb0666d5.209828299")
    implementation("com.tencent.openmap:foundation:0.9.1.6875646")

    // 二维码扫码连接（受控端待机页二维码的扫码解析）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.robolectric)
}
