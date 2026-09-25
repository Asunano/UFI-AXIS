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
    // ⚠ 2026-09-25（批 B2）**移除** `implementation(project(":core:goform"))`。
    //   本模块对 goform 的唯一类型引用是 `SignalCollector` 的构造参数
    //   `signalClient: GoformSignalClient?`，批 B2 换成了 `:core:device-spi` 的
    //   `SignalSource`。移除前逐个核过：全模块（main 源集 4 个文件）不再 import 任何
    //   `com.ufi_axis_core.controller.goform.*`，剩下的 37 处 "goform" 命中全是注释与
    //   局部变量名（`goformSignal` / `preFetchedGoform`）。
    //   留着这条依赖等于让「collector 认识具体协议」这件事继续在构建图上成立。
    // 设备 SPI（插件契约层）。ATChannel 的字段与 init() 参数类型是 AtTransport，
    // 2026-09-24（阶段 4 批 F）它从本模块的 at/ 包上移到了那里。
    //
    // 批 B2 起本模块与它的关系更紧：`SignalCollector` 的取数口类型 `SignalSource` 也在那里，
    // 而上面那条 goform 依赖已经去掉 —— 本行现在是**必需**的，不再是「不加也能编」。
    implementation(project(":core:device-spi"))
    // 字段映射登记表：SignalCollector 第 1 层的 goform 别名链已搬进 profile
    implementation(project(":core:device-schema"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ===== 单元测试（批 B2 新增：本模块此前没有 test 源集） =====
    testImplementation(libs.junit)
    // `SignalCollectorTest` 用 `runBlocking` 跑 `collect()`（suspend）。主源集那条协程依赖是
    // implementation，不传到测试编译 classpath 上，所以显式声明一次（口径同 `:core:device-spi`）。
    testImplementation(libs.kotlinx.coroutines.android)
    // 夹具解析要 `Json` / `JsonObject`，同上显式声明。
    testImplementation(libs.kotlinx.serialization.json)
    // `TelephonyCollector` 是 final class 且构造要 `Context` —— 纯 JVM 单测里造不出真的。
    // 用 mockk 绕开构造函数（它的 4 个读方法在本用例里全部 stub 掉）。
    //
    // ⚠ 刻意**不**引 Robolectric：本用例断言的是 `SignalCollector` 的**输出 key 集合**，
    //   一条 Android API 都不需要真跑；起 Robolectric 只会把一个纯 map 断言变成几秒的
    //   Android 环境初始化，还得给本模块加 `unitTests.isIncludeAndroidResources`。
    testImplementation(libs.mockk)
}
