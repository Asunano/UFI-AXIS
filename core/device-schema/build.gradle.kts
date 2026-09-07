// :core:device-schema —— 设备字段映射注册表。
//
// 职责：把「设备后台（goform 等）实际返回/接受的字段名与值编码」翻译成
// `:core:contract` 里冻结的 canonical key。适配新设备 = 新增一个 DeviceProfile 实现。
//
// 硬性约束：
//   1. **纯 JVM 模块**，不得引入任何 Android 依赖。字段映射是纯数据逻辑，
//      放纯 JVM 才能用普通 JUnit 跑测试（golden 断言量大，测试速度决定它会不会被跳着跑）。
//   2. 只依赖 :core:contract。不得依赖 :core:common / :core:goform
//      （goform 反过来依赖本模块，反向依赖会构成循环）。
//   3. 不做网络 I/O、不碰缓存。输入是已解析的 JsonObject，输出是归一化后的 JsonObject。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:contract"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
