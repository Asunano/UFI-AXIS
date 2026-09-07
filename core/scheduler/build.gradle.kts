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
    implementation(project(":core:websocket"))
    implementation(project(":core:alert"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
}
