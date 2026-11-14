plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.lib_scheduler"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // ===== F21 预期分层边界（仅声明对齐，不搬代码）=====
    // 预期：scheduler 只依赖 controller / collector / websocket 等自身下层，不跨级依赖同层 alert，
    // 也不应直连 goform（应通过 controller 门面）。
    // 当前跳层（计划内治理）：
    //   - :core:goform  → 直连 GoformClient（F9 防腐层分阶段改为依赖 GoformGateway 接口）
    //   - :core:alert   → 同层依赖（待抽离为事件/回调解耦，避免同级耦合）
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:database"))
    implementation(project(":core:collector"))
    implementation(project(":core:controller"))
    implementation(project(":core:goform"))
    implementation(project(":core:device-schema"))
    // 插件契约层。阶段 4 的 4.3（批 I）起 `PlatformAdapter` 出现在 `DataScheduler` 的构造签名上
    // （热区读法住在适配层，本模块只做「摄氏度 → 毫摄氏度」换算与降频/熔断判定）。
    // 技术上它已经经 `:core:goform` 的 `api(":core:device-spi")` 传递到本模块的编译 classpath，
    // 但那是**别人的实现细节** —— 直接依赖的模块要显式声明，口径同 `:core:controller` 里的同一行。
    implementation(project(":core:device-spi"))
    implementation(project(":core:websocket"))
    implementation(project(":core:alert"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // 2026-09-26 起本模块有单测：`CancellationOrderGuardTest` 守 DataScheduler 里
    // 「TimeoutCancellationException 必须排在 CancellationException 之前」这条顺序纪律
    // （顺序写反会把 withTimeout 的超时当成外部取消抛出去，打断整条调度循环）。
    testImplementation(libs.junit)
}
