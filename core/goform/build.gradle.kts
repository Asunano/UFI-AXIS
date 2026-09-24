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
    implementation(project(":core:device-schema"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
