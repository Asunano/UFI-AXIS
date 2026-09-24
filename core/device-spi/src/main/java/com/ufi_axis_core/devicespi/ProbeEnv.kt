package com.ufi_axis_core.devicespi

/**
 * 插件选型用的**廉价指纹**。
 *
 * ## 谁来采、采几次
 *
 * 由中间控制层（`DeviceRuntime`，阶段 4 落地）**采一次后共享**给所有插件 ——
 * 不让每个插件各自去读。理由有两条：
 * 1. 每个插件各读一遍 `/proc/cpuinfo` / 各探一次设备，插件数一多选型就变成一串 I/O；
 * 2. 各插件读到的值必须**一致**，否则打分之间没有可比性。
 *
 * 对应的纪律写在 [DevicePlugin.probe] 上：插件只许读本对象，**不许自己发起任何 I/O**。
 *
 * ## 本批（阶段 2 批 A）只定义类型，**不写采集器**
 *
 * 采集实现（读 `/proc/cpuinfo`、填 [BuildInfo]、探一次 `LD`）是**阶段 5 的 5.1**。
 * 本批落的是契约，所以这里没有任何 `fun collect()`。
 */
class ProbeEnv(
    /**
     * `/proc/cpuinfo` 里的平台串，**已转小写**；读不到时为 null。
     *
     * 现成判据参考 `ATChannel.detectPlatform()`（`:core:collector`）：它就是读这个文件，
     * 按 `Spreadtrum` / `sprd` 判展锐、按 `Qualcomm` / `qcom` 判高通。
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
