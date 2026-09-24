plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.controller"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:goform"))
    implementation(project(":core:collector"))
    // 插件契约层。阶段 4 的 4.4 起 `PlatformAdapter` 出现在 `NetworkController` 的构造签名上
    // （网络栈重启的设备知识住在适配层）。
    // 技术上它已经经 `:core:goform` 的 `api(":core:device-spi")` 传递到本模块的编译 classpath，
    // 但那是**别人的实现细节**：goform 哪天把那行改成 implementation，本模块就会莫名编译不过。
    // 直接依赖的模块要显式声明 —— 口径同 `:core:device-plugins` 里显式声明协程那一条。
    implementation(project(":core:device-spi"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // Room (SmsController 直接使用 :core:database 的 Room DAO / Entity 类型)
    implementation(libs.androidx.room.runtime)
    // mail (for SmsForwardController - javax.mail)
    implementation(libs.javax.mail)
    implementation(libs.javax.activation)
    // HTTP 客户端（WebhookChannel / HttpNotifier）。**不复用 GoformClient 那个实例**：
    // 它的 base url 绑死内网设备、强制带 goform 的 Referer/Origin/Cookie，还要过 QoS 许可闸 ——
    // 那三件事对"往用户填的公网 URL 发一条通知"全是错的。
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // SmsCodeExtractor / WebhookDelivery 是纯函数（无 Android / 无 Room 依赖），单测只需要 junit。
    testImplementation(libs.junit)
}
