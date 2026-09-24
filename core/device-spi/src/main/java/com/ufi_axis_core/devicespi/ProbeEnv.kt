package com.ufi_axis_core.devicespi

/**
 * 插件选型用的**廉价指纹**。
 *
 * ## 谁来采、采几次
 *
 * 由**装配层** `ComponentFactory.build()` 在选型之前**采一次后共享**给所有插件
 * （5.1 落地时的实际形态；批 A 的原话是「由中间控制层 `DeviceRuntime` 采」——
 * 采集需要 `Context` / `AppSettings` / 网络，而 [DeviceRuntime] 在纯契约层拿不到这些，
 * 所以采集点落在装配层，`DeviceRuntime` 只负责**拿到**它）—— 不让每个插件各自去读。
 * 理由有两条：
 * 1. 每个插件各读一遍 `/proc/cpuinfo` / 各探一次设备，插件数一多选型就变成一串 I/O；
 * 2. 各插件读到的值必须**一致**，否则打分之间没有可比性。
 *
 * 对应的纪律写在 [DevicePlugin.probe] 上：插件只许读本对象，**不许自己发起任何 I/O**。
 *
 * ## 采集器（阶段 5 的 5.1）已落地，**不在本模块**
 *
 * 实现是 `ProbeEnvCollector`（`:core:device-plugins` 的 `probe/`）：读 `/proc/cpuinfo`、
 * 填 [BuildInfo]、裸 HTTP 探一次 `LD`。**刻意不放本模块**：那三件事全是 I/O
 * （其中两件要 Android 与网络），而本模块是纯契约层，单测必须不起 Android 就能跑。
 * 所以这里仍然没有任何 `fun collect()`。
 *
 * 唯一留在本模块的是**判据常量与纯函数** [CpuInfoPlatform]（零 I/O），
 * 理由见它自己的 KDoc：它有两个消费者，分处两个互不可见的 module。
 */
class ProbeEnv(
    /**
     * `/proc/cpuinfo` 的内容，**已 trim 并转小写**；读不到时为 null。
     *
     * ⚠ 取值是**全文**而不是抽出来的某一行 —— 判据必须与 `ATChannel.detectPlatform()`
     * 改造前的「对全文做 contains」逐位等价，理由写在 [CpuInfoPlatform.normalize] 上。
     * 它只进内存，**不进日志、不下发**。
     *
     * 判据不要自己写：用 [CpuInfoPlatform.isSpreadtrum] / [CpuInfoPlatform.isQualcomm]
     * （4.6 之前这里有两份不一致的 marker 列表，那正是本字段最容易被抄错的地方）。
     */
    val cpuInfoPlatform: String?,

    /** Android `Build` 的几个字段快照，见 [BuildInfo]。 */
    val androidBuild: BuildInfo,

    /**
     * `GET /goform/...?cmd=LD` 是否返回 200。
     *
     * **这一条是整套自动选型的关键**：`DeviceProfiles` 的注释记了「鸡生蛋」问题 ——
     * 想靠读设备版本字段（`cr_version` / `wa_inner_version`）来认设备，
     * 可**读这些字段本身就需要一个 profile**（字段名是设备侧的）。
     * `LD` 不一样：它**免登录、免 profile**，一个固定的 cmd 名就能取，
     * 足以判断「这是不是一台 goform 后台的设备」，于是绕开了那个死循环。
     */
    val goformLdReachable: Boolean,
)

/**
 * Android `Build` 字段快照。
 *
 * 只取这 5 个：它们是 `SystemController.getDeviceInfo()` 已经在对外下发的那几个
 * （`brand` / `model` / `device` / `manufacturer`）加一个 `SDK_INT`
 * （`ServiceCallAtExecutor` 就是按 API 等级在两套展锐 HAL 之间切换的）。
 * 做成 data class 而不是直接读 `Build`：单测里要能构造任意组合。
 */
data class BuildInfo(
    val brand: String,
    val model: String,
    val device: String,
    val manufacturer: String,
    val sdkInt: Int,
)
