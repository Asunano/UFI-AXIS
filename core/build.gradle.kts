plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ufi_axis_core"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ufi_axis_core"
        minSdk = 31
        targetSdk = 36
        // ── 单一版本源（2026-08-10 C2）：version.json → CI -P 注入 / gradle.properties 默认 ──
        // 后端 Core 版本：version.json.backend
        versionCode = providers.gradleProperty("ufiBackendVersionCode").get().toInt()
        versionName = providers.gradleProperty("ufiBackendVersionName").get()

        // ★ P0-1 版本号注入：与 core/api/build.gradle.kts 的 VERSION_NAME 保持同步（同一数值）。
        // 本字段供 :core 模块自身读取；core/api 模块读取其自身注入的 VERSION_NAME（依赖方向相反）。
        buildConfigField("String", "VERSION_NAME", "\"${providers.gradleProperty("ufiBackendVersionName").get()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI 拆分：Release 仅保留 64 位 arm64-v8a 以减小体积
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // 性能测量专用（2026-09-01）：与 :app 的 benchmark 变体同款配方 ——
        // 代码优化程度和 release 完全一致（R8 + 资源压缩 + 非 debuggable），但用 debug 签名，
        // 因此可以直接 `adb install` 到真机，不需要正式 keystore。
        //
        // 签名与 :app:benchmark 一致（同为 debug 签名），两端之间的 signature 级权限
        // 与 AIDL 调用不受影响。
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            // 库模块（:core:* 等）没有 benchmark 变体，回落到它们的 release 变体。
            matchingFallbacks += listOf("release")
        }
        debug {
            // 模拟器调试需要 x86_64 ABI（与 :app 保持一致，便于在 x86_64 模拟器上单独验证后端 APK）
            ndk { abiFilters += "x86_64" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.*"
            // 这两个是 JavaMail 的 DataContentHandler 注册表，被排除后 javax.activation
            // 查不到 text/html、multipart/* 的 handler，发信时抛 UnsupportedDataTypeException
            // （IOException 子类），被 SMTPTransport 包成
            // “MessagingException: IOException while sending message”。
            // 排除保留（多依赖重复资源），改由 SmsForwardController.ensureMailcap() 在运行时
            // 手动注册 MailcapCommandMap；两者必须成对存在，删任一边都会重现发信失败。
            excludes += "META-INF/mailcap"
            excludes += "META-INF/mimetypes.default"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }
}

dependencies {
    // AndroidX Core (minimal)
    implementation(libs.androidx.core.ktx)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Ktor Client (for Goform)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Mail (for SMS forwarding)
    implementation(libs.javax.mail)
    implementation(libs.javax.activation)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // ★ Phase 1 模块化重构：子模块依赖
    implementation(project(":core:contract"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:goform"))
    // 设备适配层：profile 选型在 ComponentFactory 里做（计划书 3.2）
    implementation(project(":core:device-schema"))
    implementation(project(":core:collector"))
    // ★ Phase 2
    implementation(project(":core:cache"))
    implementation(project(":core:websocket"))
    implementation(project(":core:alert"))
    implementation(project(":core:scheduler"))
    implementation(project(":core:controller"))
    implementation(project(":core:api"))
    implementation(project(":core:network"))
}

// ── Web 产物与 assets 合并之间的显式依赖（2026-09-06，缺陷 4）──
// :core:network:copyWebDist 写的是 **本模块** 的 assets 源码目录
// （core/src/main/assets/web），但它只挂在 :core:network 自己的 preBuild 上，
// 与读取该目录的 :core:merge*Assets 之间没有任何依赖边。
// 目前靠 ":core 依赖 :core:network" 的传递顺序碰巧成立，但那是巧合而非约束 ——
// Gradle 有权对"隐式依赖 / 输入被其他任务写"报 validation warning，
// 并行执行 + 配置缓存下也不保证顺序。
//
// 写法选择：在 **消费侧**（本脚本）用任务路径字符串声明 dependsOn。理由：
//   1. 依赖方向与模块依赖方向一致（:core → :core:network），语义正确；
//   2. 字符串路径由 Gradle 在任务图构建阶段解析，配置期不触碰另一个 Project 对象 ——
//      不会引入跨项目配置耦合（project isolation）警告，也不需要 afterEvaluate；
//   3. 不硬编码 variant 列表（debug / release / benchmark 以及未来新增的都自动覆盖）。
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(":core:network:copyWebDist")
}
