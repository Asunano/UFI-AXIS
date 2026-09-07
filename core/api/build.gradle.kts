plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.lib_api"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        // ★ P0-1 版本号注入：与 :core 应用模块 defaultConfig.versionName 保持同步（同一数值）
        // 注意：core/api 库模块无法引用 :core 应用模块生成的 BuildConfig（依赖方向相反），
        // 故在本模块内注入同一份 VERSION_NAME，UpdateManager.currentVersionName() 与
        // ConfigRoutes /version 统一读取本字段，保证两处一致（根治版本恒 0.1）。
        // ── 单一版本源（2026-08-10 C2）：version.json → CI -P 注入 / gradle.properties 默认 ──
        // 后端 Core 版本：version.json.backend（与 :core 应用模块 versionName 保持同步）
        buildConfigField("String", "VERSION_NAME", "\"${providers.gradleProperty("ufiBackendVersionName").get()}\"")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // ===== F21 预期分层边界（仅声明对齐，不搬代码）=====
    // 预期：api 模块只直接依赖稳定门面 controller / scheduler / alert，
    // 上层经这三个门面访问下层能力，避免跳层直连 goform / collector / cache / database。
    // 当前仍直接依赖以下模块（属跳层，计划内迁移到上述门面后移除，见重构计划）：
    //   - :core:goform     → RouteContext / 各 Routes 直接使用 Goform*Client（F9 防腐层分阶段治理）
    //   - :core:collector  → 部分 Route 直接调用采集器
    //   - :core:cache      → ResponseCache 直连
    //   - :core:database   → AppDatabase 直连
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:database"))
    implementation(project(":core:goform"))
    implementation(project(":core:collector"))
    implementation(project(":core:cache"))
    implementation(project(":core:controller"))
    implementation(project(":core:scheduler"))
    implementation(project(":core:alert"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // Ktor Server (for routing DSL)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Room Database (for AppDatabase subclasses)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // ===== 单元测试（QA 新增，仅 test 作用域，不影响正式构建） =====
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.server.test.host)
    // 测试用 ContentNegotiation json() 安装路由（与 core:network HttpServer 一致），此前缺失导致测试源集编译失败
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
