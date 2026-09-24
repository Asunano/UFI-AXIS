plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.lib_collector"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:database"))
    implementation(project(":core:goform"))
    // 设备 SPI（插件契约层）。ATChannel 的字段与 init() 参数类型是 AtTransport，
    // 2026-09-24（阶段 4 批 F）它从本模块的 at/ 包上移到了那里。
    //
    // ⚠ 严格说这一行**不加也能编** —— :core:goform 是用 api 声明 device-spi 的，
    //   经它传递过来本模块已经看得见 AtTransport。显式声明是为了**有约束力**：
    //   本模块是直接使用者，依赖不该寄生在「goform 恰好用了 api」这个事实上
    //   （goform 哪天把它改成 implementation，本模块会莫名编译失败）。
    implementation(project(":core:device-spi"))
    // 字段映射登记表：SignalCollector 第 1 层的 goform 别名链已搬进 profile
    implementation(project(":core:device-schema"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
