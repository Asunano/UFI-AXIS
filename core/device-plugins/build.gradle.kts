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
    // 日志出口 AppLogger（含 AT 专用审计出口 AppLogger.at()）与 service call 的
    // hex→UTF-16LE 解码器 decodeServiceCallText，都住在 :core:common。
    // 2026-09-24（阶段 4 批 F）ServiceCallAtExecutor 从 :core:collector 搬进本模块时带来了这条依赖。
    //
    // ⚠ 为什么不照 DeviceRuntime 的「注入日志回调」纪律绕开本依赖：
    //   1. `decodeServiceCallText` **绕不过去** —— 它是 service call 协议的解码器（纯函数、
    //      有自己的单测），不是日志出口，没有「注入一个回调」这种形态；
    //      为它在契约层再复制一份等于两份解码器各自演化。
    //   2. `AppLogger.at()` 是 AT 通道的**专用审计出口**（/api/at 的 console recorder 在消费），
    //      换成 `(String) -> Unit` 回调就丢掉了那条审计语义，还要把回调从装配层一路穿到每个
    //      transport 实例里。
    //   3. 那条纪律的适用范围是 **:core:device-spi**（纯契约层，单测必须不起 Android 就能跑），
    //      本模块是**实现层**：它已经依赖 :core:goform，而 goform 自己就依赖 :core:common ——
    //      AppLogger 早就在运行时 classpath 上，这一行只是把它摆到编译期 classpath 上，
    //      不新增任何运行时耦合，也不成环（:core:common 不依赖本仓任何 core 模块）。
    implementation(project(":core:common"))
    // ServiceCallAtExecutor 用 `withContext(Dispatchers.IO)` 包 ProcessBuilder 调用 ——
    // 需要协程运行时本体，不只是 stdlib 的 `suspend`。
    // 上游的 device-spi / goform 都是以 implementation 声明协程的，不会传递到本模块的编译
    // classpath 上（测试源集那边同理，见下面那条 testImplementation），所以这里显式声明一次。
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // `SprdPlatformTest` 用 `runTest` 的**虚拟时间**断言 `restartNetworkStack` 的两段等待
    // （500ms / 2000ms）—— 真跑 `runBlocking` 要等 2.5s，而改生产代码的时序换可测性是本末倒置。
    // 同一用法已在 `:core:alert` 与 `:core:api` 的单测里。
    testImplementation(libs.kotlinx.coroutines.test)
    // `PluginContractTest` 要调 `probe()`（suspend）→ 需要 `runBlocking`。
    // 主源集虽然经 device-spi / goform 拿到了协程，但那两处都是 implementation，
    // 不会传递到本模块的测试编译 classpath 上，所以这里显式声明一次。
    testImplementation(libs.kotlinx.coroutines.android)
    // `ZteGoformAdapterBandSelectionTest` 要在**不触达传输层**的前提下断言
    // 「BandSelection.All 最终传给 GoformNetworkClient 的取值就是 lteAllBands() / nrAllBands()」。
    //
    // ⚠ 刻意**不**自己写一个假 `GoformTransport`：那个符号有守门测试
    // （`GoformTransportVisibilityGuardTest`）钉着「不许跨出 core/goform」，
    // 连测试源码也算越界（它扫的是 core 目录下全部 .kt）。所以这里 mock 的是
    // `GoformNetworkClient` 本身 —— 断言点正好就在 adapter 与客户端之间的那条边界上。
    // mockk 已是本仓既有的测试依赖（`:core:api` 在用同一条）。
    testImplementation(libs.mockk)
}
