// :core:device-plugins —— 设备插件实现层。
//
// 布局：**一个 module、每设备一个 package**（`zte/f50`、以后的 `xxx/yyy`）。
// 刻意**不**做「一个设备一个 Gradle module」：构建图膨胀（每加一台设备多一条 include、
// 多一轮配置与编译）换不来任何隔离收益 —— 真正的隔离靠**守门测试**
// （某设备的符号不许出现在别的设备 package 里），不靠 Gradle 边界。
//
// 依赖方向：本模块依赖 :core:device-spi（契约）+ :core:goform（具体协议实现）。
// 反过来**不成立** —— device-spi 不许知道本模块存在，装配层按注册表拿插件。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.deviceplugins"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 插件契约层。DevicePlugin / DeviceTransport / TransportConfig / DeviceTuning / ProbeEnv
    // 全部出现在本模块的公开签名上（`object ZteF50Plugin : DevicePlugin`）—— 必须 api，
    // 否则装配层拿到 PluginRegistry.ALL 之后看不见元素类型。
    api(project(":core:device-spi"))
    // profile() 返回的 ZteGoformProfile 住在这里。
    // 只需 implementation：公开签名上的返回类型是 DeviceProfile（由 device-spi 以 api 传递过来），
    // 具体的 ZteGoformProfile 是本模块内部选择。
    implementation(project(":core:device-schema"))
    // 插件要 new GoformClient（具体协议实现）。同理只需 implementation：
    // createTransport() 的返回类型是 DeviceTransport，GoformClient 不上签名。
    implementation(project(":core:goform"))
    testImplementation(libs.junit)
    // `PluginContractTest` 要调 `probe()`（suspend）→ 需要 `runBlocking`。
    // 主源集虽然经 device-spi / goform 拿到了协程，但那两处都是 implementation，
    // 不会传递到本模块的测试编译 classpath 上，所以这里显式声明一次。
    testImplementation(libs.kotlinx.coroutines.android)
}
