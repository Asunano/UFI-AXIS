plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.controller.goform"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    // 设备 SPI（插件契约层）。DeviceTransport 住在那里，本模块的传输层实现它。
    // **必须是 api 而不是 implementation**：`ComponentGraph.NetworkGraph.goformClient` 与
    // `RouteContext.goformClient` 的**字段类型就是 DeviceTransport**，而那两处在 `:core` / `:core:api`，
    // 它们只声明了对本模块的依赖。用 implementation 的话 device-spi 不随本模块传递出去，
    // 那两个字段的类型就「不在 classpath 上」，直接编译失败。
    api(project(":core:device-spi"))
    // 字段映射登记表（纯 JVM）。设备侧字段名只允许出现在 device-schema 的 profile 里，
    // 本模块的客户端方法负责在返回前调 FieldNormalizer 归一化。
    //
    // 2026-09-25（批 B1）改成 `api`：`NormalizedFields` 现在出现在 `GoformSignalClient` /
    // `GoformWifiClient` 读方法的**返回类型**上，判据就是本文件上面那条「公开签名里出现的
    // 类型要让依赖方看得见」。技术上它也能经 `api(":core:device-spi")` 传过去
    // （device-spi 对本模块的登记表是 api 依赖），但那是别人的实现细节，不该靠。
    api(project(":core:device-schema"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
