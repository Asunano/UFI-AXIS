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
    // 字段映射登记表（纯 JVM）。设备侧字段名只允许出现在 device-schema 的 profile 里，
    // 本模块的客户端方法负责在返回前调 FieldNormalizer 归一化。
    implementation(project(":core:device-schema"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
