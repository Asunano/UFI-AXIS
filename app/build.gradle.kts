plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ufi_axis"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ufi_axis"
        minSdk = 31
        targetSdk = 36
        // ── 单一版本源（2026-08-10 C2）：version.json → CI -P 注入 / gradle.properties 默认 ──
        // 前端 App 版本：version.json.frontend
        versionCode = providers.gradleProperty("ufiFrontendVersionCode").get().toInt()
        versionName = providers.gradleProperty("ufiFrontendVersionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI 拆分：Release 仅保留 64 位 arm64-v8a 以显著减小体积（见 plan C1，需本地真机验证）
        ndk { abiFilters += listOf("arm64-v8a") }
        // 矢量图使用支持库，避免为各密度生成 PNG
        vectorDrawables { useSupportLibrary = true }
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
        // 性能测量专用（2026-08-31）：代码优化程度与 release 完全一致（R8 + 资源压缩 + 非 debuggable），
        // 但用 debug 签名签，因此可以直接 `adb install` 到真机。
        //
        // 为什么需要它：debug 包没有 R8、且 Compose 代码首次执行走解释执行 + 后台 JIT，
        // 会产生「随机、突发、高频」的掉帧尖峰 —— 在 debug 包上量转场帧率得不到有效结论。
        // release 又因为没有配置 signingConfig 而产出未签名 APK 装不上，故单独开一个变体。
        //
        // 签名沿用 debug：与同样用 debug 签名的 core 包签名一致，
        // 两端之间的 signature 级权限与 AIDL 调用不受影响。
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            // 库模块（:app:ui / :app:data / :core:* 等）没有 benchmark 变体，回落到它们的 release 变体。
            matchingFallbacks += listOf("release")
        }
        debug {
            // 模拟器调试需要 x86_64 ABI
            ndk { abiFilters += "x86_64" }
        }
    }
    compileOptions {
        // 统一为 Java 17（与 core 模块保持一致）
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // 仅保留用到的语言资源，剔除其余 locale 字符串（减小体积）
        // 注意：AGP 8.8 起 resourceConfigs 已移除，改用 localeFilters（语言资源白名单）
        localeFilters += listOf("zh", "en")
    }
}

dependencies {
    // ★ Phase 3 UI 核心层（已合并为单一 :app:ui 模块）
    implementation(project(":app:ui"))

    // ★ Phase 4 数据层子模块（data + util）
    implementation(project(":app:data"))

    // ★ 共享 ViewModel 层（MainViewModel + module/* + state/* + BackgroundManager）
    implementation(project(":app:viewmodel"))

    // ★ Phase 4 Feature 模块（UI 页面，依赖 :app:viewmodel + :app:data + :app:ui）
    implementation(project(":app:feature-settings"))
    implementation(project(":app:feature-network"))
    implementation(project(":app:feature-dashboard"))
    implementation(project(":app:feature-tools"))
    implementation(project(":app:feature-files"))
    implementation(project(":app:feature-download"))
    implementation(project(":app:feature-sms"))
    implementation(project(":app:feature-apps"))
    implementation(project(":app:feature-monitor"))

    // ★ 共享数据工具（DownsampledPoint 等，经 :app:data 的 api 透传引用）
    implementation(project(":core:common"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // 仅用 20 个常用图标(均来自 core 包),移除 extended 避免上千个未用图标生成代码撑大 debug DEX
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    // WorkManager（多进程 :ufi_notify 内 GuardScheduler 需 WorkManager.getInstance；
    // 经 Configuration.Provider 在 Application 层按需初始化，避免独立进程未初始化崩溃）
    implementation(libs.androidx.work)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Network
    implementation(libs.retrofit.core)
    implementation(libs.okhttp.core)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Media playback (Media3 / ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.extractor)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    // kotlinx.serialization（:app:data 的 WebSocketMessage.data 已切到 kotlinx JsonElement，
    // 供 :app 单测构造/解析 JsonElement；仅 test 作用域，不影响主包与 release）
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
