import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val ablyKey: String = run {
    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    local.getProperty("ABLY_KEY")
        ?: (project.findProperty("ABLY_KEY") as? String)
        // 密钥不入库:来源为 local.properties(本地)或 ORG_GRADLE_PROJECT_ABLY_KEY(CI Secrets)
        ?: ""
}

// 统一签名(与 inklink-controller/inklink-host 拆分仓共享同一 keystore,双端可覆盖安装):
// 来源优先级:环境变量(CI Secrets) > local.properties > 默认 keystore 路径(密码必须注入)
val inklinkEnv = System.getenv()
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.inklink.host"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.inklink.host"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ABLY_KEY", "\"$ablyKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
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
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("nl.dionsegijn:konfetti-xml:2.0.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 二维码生成（待机页展示连接信息）
    implementation("com.google.zxing:core:3.5.3")

    // Room 持久化（V1.1 游戏化：宠物实体 + 事件日志）
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
