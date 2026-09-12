// :core:contract —— app / web / core 三端共享的契约常量。
//
// 硬性约束：
//   1. 纯 JVM 模块，**不得**引入任何 Android 依赖，也**不得**依赖 :core:common
//      （contract 是最底层，反向依赖会构成循环）；
//   2. 只放"双端都要用同一份"的常量与枚举，不放业务逻辑、不放 DTO 实现；
//   3. 任何取值必须能在 core 源码里指到唯一权威来源，否则在注释里显式说明"无白名单"。
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
    // 仅序列化运行时：契约里的枚举需要能直接被两端的 kotlinx.serialization 使用
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
