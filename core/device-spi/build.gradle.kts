// :core:device-spi —— 设备 SPI（插件契约层）。
//
// 职责：只放「换一台设备时会一起换掉」的契约类型 —— DevicePlugin / DeviceTransport /
// TransportConfig / DeviceTuning / ProbeEnv。装配层与中间控制层只依赖本模块，
// 不 import 任何具体插件。
//
// 硬性约束：
//   1. **不得依赖任何具体协议实现**。特别是不许依赖 :core:goform / :core:collector /
//      :core:controller —— goform 反过来依赖本模块（DeviceTransport 在这里），
//      collector 又依赖 goform，把它们加进来立刻成环。
//      需要具体协议的东西一律放 :core:device-plugins（它可以依赖 goform）。
//   2. 是 Android library 而不是纯 JVM：阶段 4 的 PlatformAdapter 需要 Context，
//      提前把模块类型定下来，免得那时再搬一次。
//   3. 只定义契约，不写任何采集/装配实现（采集器归阶段 5.1，装配接线归阶段 6）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.devicespi"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 冻结区（canonical 字段名 / 值域常量）。阶段 3 的 Capability 定在这里、并会进
    // DevicePlugin 的公开签名，所以从一开始就按 api 声明：依赖方与本模块必须看到同一份冻结区，
    // 换成 implementation 的话阶段 3 加 Capability 时还要再改一次。
    api(project(":core:contract"))
    // DeviceProfile 出现在 DevicePlugin.profile() 的返回类型上 —— 必须 api，
    // 否则依赖方（device-plugins / 装配层）拿到插件后看不见返回值类型，编译不过。
    api(project(":core:device-schema"))
    // probe() 是 suspend 函数。suspend 本身只要 stdlib，这里声明协程运行时是为了
    // 后续（阶段 4 的 PlatformAdapter / 阶段 5 的 ProbeEnv 采集）不再动依赖；
    // 协程类型不出现在任何公开签名里 → implementation 够用。
    implementation(libs.kotlinx.coroutines.android)
    // JsonObject / JsonElement 出现在 DeviceTransport.read / readOne 的签名上 —— 必须 api。
    // 用 implementation 的话，依赖方调 transport.read(...) 会因为「返回类型不在 classpath 上」编译失败。
    api(libs.kotlinx.serialization.json)
    // ⚠ 刻意**不**依赖 libs.ktor.client.core：本模块最终签名里没有任何 Ktor 类型
    //   （HttpResponse 只出现在 :core:goform 自己的那一层传输接口上，没有上到 DeviceTransport）。
    //   判据就一条：只有公开签名里出现的类型才需要进依赖、才需要是 api。
    testImplementation(libs.junit)
    // `DeviceRuntimeTest` 要调 `DeviceRuntime.resolve()`（阶段 5 的 5.2 起是 suspend，
    // 因为它会调 `DevicePlugin.probe()`）→ 需要 `runBlocking`。
    // 上面那条协程依赖是 implementation，**不会**传到测试编译 classpath 上，所以这里显式声明一次
    // （同一条已经写在 `:core:device-plugins` 的 build.gradle.kts 里）。
    // 刻意不用 coroutines-test：本模块的单测里没有任何需要虚拟时间的等待 ——
    // 假插件的 probe() 是纯函数，`runBlocking` 一跑就完。
    testImplementation(libs.kotlinx.coroutines.android)
    // `DeviceHubTest` 的 signal 域假实现（批 B2）要 `JsonObject` / `JsonArray` / `JsonPrimitive`
    // 造假响应。主源集那条是 `api`，本行只是把它显式摆到测试编译 classpath 上，
    // 口径同上面那条协程依赖 —— 测试源集不吃「主源集恰好用了哪种声明」这个实现细节。
    testImplementation(libs.kotlinx.serialization.json)
}
