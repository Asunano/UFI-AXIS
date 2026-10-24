package com.ufi_axis.ui.diagnose

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ufi_axis.data.model.DiagnoseResponse
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.util.FormatUtils
import com.ufi_axis_core.contract.Capability
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * 诊断中心 —— **登记表**（2026-10-14）。
 *
 * 本批 20 条，按 [DiagnosticGroup] 三段：
 *  · **ENV（10 条）**：设备能力集 / root 与特权通道 / SELinux / Magisk / **监控数据库占用** /
 *    存储 / 媒体库权限 / **core 自身的权限** / **本机通知授权**（9 条 AUTO）+ **CPU 探针**
 *    （1 条 MANUAL，2026-10-14 补）—— 除 CPU 探针外全部只读 core 或本机状态，不打外网；
 *    ⚠ "只读"不等于"不碰设备"：`env.shell` 会跑 `id`、`service.snapshot` 的 `/api/diagnose`
 *    会跑 4 条 root shell（`id` / `getprop` / `settings get` / `ip route`）。它们**没有副作用**，
 *    所以仍然是 AUTO；但"进页面并发 15 条请求"里有 4 条会去抢 core 的 root 通道许可，
 *    设备忙时可能把彼此拖到超时（web 侧为这件事把自动批改成了串行队列，见
 *    `web/src/views/diagnostics/useDiagnosticRun.ts` 的决定二）。
 *    本页的自动批**仍是并发**：重活（字段覆盖率 / **CPU 探针**）都不在 AUTO 组里，
 *    风险比 web 小得多；要不要一并改串行是一个独立的取舍，见 `DiagnosticSection` 的注释。
 *  · **NETWORK（4 条，全 MANUAL）**：ping / DNS / TCP / traceroute，走 core 的
 *    `/api/diagnostic/…`。**必须是手动**：每一条都要用户填目标，且最慢的一条
 *    （traceroute 15 跳）会跑十几秒；
 *  · **SERVICE（6 条，全 AUTO）**：服务状态与崩溃 / QoS 与线程池 / **响应缓存健康** /
 *    **通知渠道配置** / 全量快照 / 调试日志（加粗的两条是 2026-10-14 补的）。
 *
 * ## 2026-10-14（第二批）：权限类检查 + 结果里的写动作
 * 用户报告的痛点有两个，都在这一批里补上：
 *  ① **app 开了推送却收不到系统通知** —— 四层闸门（系统权限 / 系统总开关 / 应用内总闸与分类 /
 *     五条渠道）逐层报出来，`env.notifypermission`；
 *  ② **从旧版 scripts 脚本安装的设备，升级 core 后新增的权限没人补** —— 旧脚本只在自己被
 *     重新执行时才授权，而升级路径（覆盖安装）不会重跑它，于是新版本里依赖新权限的功能
 *     静默失效。`env.corepermission` 用 core 的实测回答"现在缺哪些"，并在**真能补**时给出
 *     一颗二次确认的「补全权限」按钮（写动作）。
 * 为此 [DiagnosticResult] 多了一个 `write` 字段（理由与硬约束见 [DiagWriteAction] 的 KDoc：
 * 写动作只在该结论下才成立，所以它属于结果而不属于描述符）。
 *
 * ## 2026-10-14：跳转入口从"跳转类条目"改成描述符字段 `action`
 * 「调试日志」这一项原先只画一个 chevron、什么都不读（`route` 非空 ⇒ 不跑）。
 * 现在它读 `/api/debug-logs/files` 出结论，并带一个去日志页的入口 ——
 * 改动的动机与代价写在 [DiagnosticItem.action] 的 KDoc 里。
 *
 * ## 端点来源
 *  · 全部走**已有的** Retrofit 接口或裸请求，本批**不新增任何 Retrofit 注解**
 *    （`env.corepermission` 的两条走 [DiagnosticApi.rawGetJson] / [DiagnosticApi.rawPostJson]）；
 *  · `GET /api/device/capabilities`、`GET /api/system/root-check`、`GET /api/shell/root`、
 *    `GET /api/device/selinux`、`GET /api/device/magisk`、`GET /api/system/storage`、
 *    `GET /api/media/status`、`GET /api/system/permissions`（**2026-10-14 core 侧新增**）；
 *  · `POST /api/diagnostic/{ping,dns,tcp,traceroute}`（裸 POST，理由见 [DiagnosticApi.rawPostJson]）；
 *  · `GET /api/service/status`、`GET /api/service/crash`、`GET /api/qos/status`、`GET /api/diagnose`；
 *  · `GET /api/debug-logs/files`（2026-10-14 新收，走 [DiagnosticApi.rawGetJson] 读原文 ——
 *    强类型模型会把 core 新加的键静默丢掉，而"这次到底回了哪些字段"正是对账的判据）；
 *  · `GET /api/monitor/storage`、`GET /api/cpu-probe`、`GET /api/cache/stats`、
 *    `GET /api/sms-forward/config`、`GET /api/notify/webhook/config`、`GET /api/notify/sms/config`
 *    （**2026-10-14 补的四项 core 类检查**，全部走已有的 Retrofit 方法或裸 GET，同样不新增注解）；
 *  · `POST /api/system/permissions/grant`（**写动作**，同上 core 侧新增）。
 *
 * ## 为什么「字段覆盖率」不在本表里（而 CPU 探针 2026-10-14 进表了）
 * 字段覆盖率会**逐分组向设备发查询**（最多 10 组），且是"跑一次、前后对账"的用法，
 * 留在页面下部的「高级诊断」区（原 `DiagnoseScreen` 的卡片原样保留）。
 * 同理，「响应缓存」的**维护卡**与「字段归一化开关」也留在那一区：一个带写入动作
 * （清空 / 失效）、一个本身就是开关，都不是"检查"。
 * ⚠ **CPU 探针是个例外，它现在在表里**（`env.cpuprobe`）：它是 core 类检查，web 有、
 * 本端没有就等于同一台设备在两个客户端上看到的结论不一致（跨端自检脚本会直接报错）。
 * 但它仍然是 **MANUAL** —— 本页的自动批是并发的，20+ 条 shell 不能跟着进页面跑
 * （理由与 web 的差异写在那条描述符的 KDoc 里）。而「响应缓存」的**只读摘要**
 * （`service.cachehealth`）同样进了表：**一个"看"、一个"改"**，正是本页与下半那张卡的分工。
 *
 * 跨端说明（免得下一个人以为漏做了）：**"响应缓存"的真源两端不在同一处**。
 * app 这边真源就是本页下半那张维护卡（清空 / 按规则失效），上半只放**只读摘要**
 * （`service.cachehealth`：只读 `stale` 与 `expired_count`，**刻意不重复**条目数与体积 ——
 * 那两个数字在维护卡里已经有了，抄一份只是多一处会漂移的副本）；
 * web 那边真源在「设置 → 性能 → 缓存管理」，诊断页放的同样是只读摘要 + 跳转。
 * 同理，"申请通知权限"的真源是「通知与守护」页（全仓唯一会弹授权框的地方），
 * `env.notifypermission` 只给跳转入口、不自己再实现一套申请逻辑。
 *
 * ## 归属（`scope`，2026-10-14 补）：**改这里必须同步另一端**
 * 两端各维护一份清单，而"哪些检查其实是**同一件事**"此前只存在于人脑里 —— 加一项 core 类
 * 检查时，**没有任何东西会提醒你"另一端也要加"**，于是同一台设备在两个客户端上看到的结论
 * 不一样，而没人发现。现在每条描述符都必须声明 [DiagnosticScope]（[DiagnosticItem.scope]，
 * **没有默认值**）：
 *  · `CORE` —— 查的是设备 / core 自己的东西，**两端都必须有**（id 命名可以不同：
 *    本端的 `env.monitorstorage` 就是 web 的 `storage`）；
 *  · `CLIENT` —— 只在本端才有意义（本机通知授权）。
 * `scripts/check-diagnostics-scope.mjs` 拿 `scope` + 一张跨端映射表核对这件事，
 * 哪一项在另一端缺了会直接报错（退出码 1，CI 可跑）。页面上卡片头部的角标显示的
 * 就是这个字段（`DiagnosticSection` 的 `DiagnosticCard`），用户据此一眼看出哪些两端都有。
 * ★ 所以：新增 / 删除 / 改归属任何一条检查，都要去
 * `web/src/views/diagnostics/diagnosticRegistry.ts` 对齐，并把新的一对登记进那个脚本的映射表。
 *
 * ## 描述符里的三条纪律
 * 1. **不写 try/catch**（复合项除外）：异常由 [DiagnosticSection] 的 runner 统一翻成 FAIL，
 *    一处兜底胜过二十处；
 * 2. **不猜 core 的语义**：`/api/diagnostic/…` 的 `ok` 字段**只表示"端点收到了请求"**
 *    （core 的 `okJson` 对成功失败都写 `"ok":true`，只有参数非法才回 `"ok":false`），
 *    真正的成败在 `exit_code`；traceroute 更极端 —— core 把它硬编码成 0，
 *    所以那一条的结论只能从 output 文本里读。三处都写了注释，别再踩；
 * 3. **状态不能拿默认值冒充**：读不到就 FAIL + 说明，绝不用 `?: false` 渲染成"否"。
 */
object DiagnosticRegistry {

    /**
     * 本次渲染的全部条目。
     *
     * @param api 描述符的外部能力（强类型接口 + baseUrl）。
     * @param inputs 用户在 NETWORK 那几条的输入框里敲的字，键是 [DiagnosticItem.id]。
     *   传进来而不是让描述符自己持有：描述符是**冻结数据**，可变状态归页面
     *   （见 [DiagInput] 的说明）。
     * @param context 只有**本机状态**类检查需要（`env.notifypermission` 要读系统通知权限与
     *   五条通知渠道）。由 [DiagnosticSection] 从 `LocalContext` 取好传进来，而不是让描述符
     *   自己去某个全局拿 —— 页面本来就有它，而"描述符怎么拿到 Context"不该变成一个需要
     *   引入全局单例的问题（那也是本页唯一需要 Context 的地方）。
     *
     * ⚠ 顺序即界面顺序；页面按 [DiagnosticGroup] 分三段渲染，段内保持本列表的相对顺序。
     */
    fun all(api: DiagnosticApi, inputs: Map<String, String>, context: Context): List<DiagnosticItem> = listOf(
        // ── ENV ──
        capabilitiesItem(api),
        shellItem(api),
        selinuxItem(api),
        magiskItem(api),
        monitorStorageItem(api),
        storageItem(api),
        mediaItem(api),
        corePermissionItem(api),
        notifyPermissionItem(context),
        cpuProbeItem(api),
        // ── NETWORK ──
        pingItem(api, inputs["network.ping"].orEmpty()),
        dnsItem(api, inputs["network.dns"].orEmpty()),
        tcpItem(api, inputs["network.tcp"].orEmpty()),
        tracerouteItem(api, inputs["network.traceroute"].orEmpty()),
        // ── SERVICE ──
        serviceItem(api),
        qosItem(api),
        cacheHealthItem(api),
        notifyChannelsItem(api),
        snapshotItem(api),
        debugLogItem(api),
    )
}

// ────────────────────────── 通用条目壳 ──────────────────────────

/**
 * [DiagnosticItem] 的唯一实现：把"是什么"（字段）与"怎么跑"（[block]）拼起来。
 *
 * 做成一个类而不是十几个匿名对象：匿名对象在每个 lambda 里重复写六个 override，
 * 加一个字段（例如以后的"超时"）就要改十几处。
 */
private class RegistryItem(
    override val id: String,
    override val scope: DiagnosticScope,
    override val name: String,
    override val desc: String,
    override val group: DiagnosticGroup,
    override val mode: RunMode,
    override val input: DiagInput? = null,
    override val action: DiagAction? = null,
    private val block: suspend () -> DiagnosticResult,
) : DiagnosticItem {
    override suspend fun run(): DiagnosticResult = block()
}

/**
 * [RegistryItem] 的工厂（省掉每个登记点的 `RegistryItem(...)` 前缀，参数名与接口一一对应）。
 *
 * [scope] **没有默认值**（与接口一致）：它是跨端齐全这件事的唯一判据，
 * 给默认值就等于允许"新加一项 core 检查而另一端没有"这种状态悄悄存在。
 */
private fun item(
    id: String,
    scope: DiagnosticScope,
    name: String,
    desc: String,
    group: DiagnosticGroup,
    mode: RunMode,
    input: DiagInput? = null,
    action: DiagAction? = null,
    block: suspend () -> DiagnosticResult,
): DiagnosticItem = RegistryItem(id, scope, name, desc, group, mode, input, action, block)

/** 三态布尔：null = 还没读到（**不能拿 false 冒充"否"**，与 `DiagnoseScreen.boolLabel` 同一口径）。 */
private fun boolLabel(v: Boolean?): String = when (v) {
    true -> "是"
    false -> "否"
    null -> "未知（读取失败）"
}

/** 运行时长：core 报的是毫秒。只做"够读"的粗粒度换算，不追求秒级精度。 */
private fun formatUptime(ms: Long): String {
    if (ms <= 0) return "未知"
    val totalMinutes = ms / 60_000
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return buildString {
        if (days > 0) append("${days}天")
        if (hours > 0) append("${hours}小时")
        append("${minutes}分")
    }
}

/** 媒体类型 → 中文（只用在本项的行文案里，所以放在这里而不是塞进 `:app:data`）。 */
private fun mediaTypeLabel(type: String): String = when (type) {
    MEDIA_TYPE_VIDEO -> "视频"
    MEDIA_TYPE_AUDIO -> "音乐"
    MEDIA_TYPE_IMAGE -> "图片"
    else -> type
}

// ────────────────────────── ENV ──────────────────────────

/**
 * 设备能力集。
 *
 * 这一项回答的是"core 认得出这台设备的哪些功能域"——**不是**"设备支不支持"。
 * 缺域时 core 的对应路由一律回 501（NOT_SUPPORTED），app 侧靠 `DeviceCapabilityState`
 * 把入口置灰。所以要区分两种"缺"：
 *  · 能力集里没有它 → 设备 / 插件不支持（不可恢复）；
 *  · 能力集里有它、但本版 app 的 `Capability` 枚举里没有 → **新 core 配旧 app**
 *    （能力集是数组而不是 map，正是为了让这种组合能分辨）。
 */
private fun capabilitiesItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.capabilities",
    scope = DiagnosticScope.CORE,
    name = "设备能力集",
    desc = "core 认得出这台设备的哪些功能域。缺哪个域，对应的功能入口就会回 501（不支持）。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
) {
    val caps = api.service.getDeviceCapabilities()
    val wires = caps.capabilities
    val unknown = wires.filter { Capability.fromWire(it) == null }
    val plugin = caps.plugin_id.ifBlank { "未知" }
    DiagnosticResult(
        // 空能力集是**合法的**（"这台设备一个域都不支持"），但对用户等于功能全灭，
        // 所以给 WARN 而不是 OK —— 它是排障现场最该被一眼看到的一行。
        status = if (wires.isEmpty()) DiagStatus.WARN else DiagStatus.OK,
        summary = if (wires.isEmpty()) {
            "core 没报告任何能力（插件 $plugin）—— 各功能入口会全部按「不支持」处理"
        } else {
            "$plugin · ${wires.size} 项能力" +
                if (unknown.isNotEmpty()) " · 其中 ${unknown.size} 项本版 app 不认识" else ""
        },
        rows = buildList {
            add("插件 id" to plugin)
            add("能力数" to wires.size.toString())
            add("能力集" to wires.joinToString("、").ifBlank { "（空）" })
            if (unknown.isNotEmpty()) {
                // 新 core + 旧 app 的唯一可见信号：这些域 core 说有，但本版 app 没有对应入口。
                add("本版 app 不认识" to unknown.joinToString("、"))
            }
        },
    )
}

/**
 * root 与特权通道。
 *
 * **两个端点都要读**：`root-check` 只回一个布尔，`shell/root` 还带 `uid` 与 `method`
 * （`adb_shell` / `shell`）—— "有 root 但走的是哪条通道"只有后者能回答，而它决定了
 * 写操作会不会被 SELinux / adbd 拦掉。
 */
private fun shellItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.shell",
    scope = DiagnosticScope.CORE,
    name = "root 与特权通道",
    desc = "有没有 root、以哪种方式取得特权。决定依赖特权的功能（写节点、抓日志）能走到哪一步。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
) {
    // 两个端点独立取值：一个挂了另一个照样要显示 —— 这里要的是**部分数据也要显示**，
    // 不是"漏掉异常"（与文件头那条"不写 try/catch"的纪律不冲突）。
    val rootCheck = runCatching { api.service.getRootCheck() }.getOrNull()
    val shellRoot = runCatching { api.service.getShellRoot() }.getOrNull()
    if (rootCheck == null && shellRoot == null) {
        return@item DiagnosticResult(
            DiagStatus.FAIL,
            "root-check 与 shell/root 两个端点都读不到 —— core 可能未启动或版本过旧",
        )
    }
    val hasRoot = rootCheck?.hasRoot ?: shellRoot?.root
    DiagnosticResult(
        // 无 root 不是"坏"：设备本来就可能没 root，只是特权功能会降级。所以是 WARN。
        status = if (hasRoot == true) DiagStatus.OK else DiagStatus.WARN,
        summary = when (hasRoot) {
            true -> "已取得 root" +
                shellRoot?.method?.takeIf { it.isNotBlank() }?.let { "（通道 $it）" }.orEmpty()
            false -> "未取得 root —— 依赖特权的功能会降级或不可用"
            null -> "两个端点都没给出结论（root-check 读失败）"
        },
        rows = buildList {
            add("Root 可用" to boolLabel(hasRoot))
            shellRoot?.let {
                add("uid" to it.uid.ifBlank { "未知" })
                // method 是"特权来源"的唯一途径（adb_shell / shell）；文档里那个 api/adb/status 是幻影端点
                add("特权通道" to it.method.ifBlank { "未知" })
            }
            if (rootCheck == null) add("root-check" to "读取失败")
            if (shellRoot == null) add("shell/root" to "读取失败")
        },
    )
}

/**
 * SELinux 模式。
 *
 * core 侧就是 `getenforce`，回 `{"selinux": "Enforcing"|"Permissive"|"Disabled"|"Unknown"}`。
 *
 * 为什么 Permissive 要给 WARN：它不是"更安全"也不是"更不安全"，而是**同一段写操作的结果会
 * 随它变**。"这个写入为什么在 A 机器上成功、B 机器上失败"的答案常常就在这一行，
 * 所以它必须显眼，而不是混在一堆绿色里。
 */
private fun selinuxItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.selinux",
    scope = DiagnosticScope.CORE,
    name = "SELinux 模式",
    desc = "Enforcing 会拦下越界的写操作，Permissive 只记录不拦。同一段命令的成败常取决于它。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
) {
    val value = (api.service.getSelinuxStatus() as? JsonObject)
        ?.get("selinux")?.jsonPrimitive?.contentOrNull
        ?.trim()
        .orEmpty()
    val normalized = value.lowercase()
    DiagnosticResult(
        status = if (normalized == "enforcing") DiagStatus.OK else DiagStatus.WARN,
        summary = when (normalized) {
            "enforcing" -> "Enforcing（强制模式）"
            "permissive" -> "Permissive（宽容模式）—— 权限拦截被放宽，写操作更容易成功"
            "disabled" -> "Disabled（SELinux 已关闭）"
            else -> "读不到（getenforce 没有返回值，实际值=$value）"
        },
        rows = listOf("getenforce" to value.ifBlank { "（空）" }),
        raw = value.ifBlank { null },
    )
}

/**
 * Magisk。
 *
 * 只回 `installed` / `version`，所以结论只有两句：装了（顺带报版本）或没检测到。
 * **没装不是失败**（INFO）：没 root 的设备必然没 Magisk，把它画成红色会让"未 root"
 * 这一个事实在页面上出现两次。
 */
private fun magiskItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.magisk",
    scope = DiagnosticScope.CORE,
    name = "Magisk",
    desc = "是否装了 Magisk 及其版本。有些特权能力（su 授权、模块挂载）依赖它。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
) {
    val magisk = api.service.getMagiskStatus()
    DiagnosticResult(
        status = if (magisk.installed) DiagStatus.OK else DiagStatus.INFO,
        summary = if (magisk.installed) {
            "已安装 Magisk ${magisk.version.ifBlank { "（版本未知）" }}"
        } else {
            "未检测到 Magisk —— 未 root，或用的是别的 root 方案"
        },
        rows = listOf(
            "已安装" to boolLabel(magisk.installed),
            "版本" to magisk.version.ifBlank { "无" },
        ),
    )
}

/**
 * 监控数据库各表占用（`GET /api/monitor/storage`，2026-10-14 补，**core 类**）。
 *
 * ## 与下面 [storageItem]（`/api/system/storage`）的区别（两条都留着，别合并）
 * · 本条答"**我们自己的数据**占了多少"—— 它是可清理的（`POST /api/monitor/clean`，
 *   入口在「监控设置」页）；
 * · [storageItem] 答"**设备还有没有地方写**"—— 它是硬上限，写满只能删东西。
 * 一个数字同时回答两个问题是不可能的，合并只会让"到底哪个满了"说不清。
 * （web 侧是同样的两条：`storage` 与 `disk-usage`。）
 *
 * ## 为什么这条**没有**阈值
 * 与 web 一致：core 不报容量上限，客户端没有分母可算比例；"占了多少"本身是事实陈述，
 * 所以判 OK，而不是自己编一个"超过 X KB 就告警"。真正会写满的是数据分区，那条有阈值。
 *
 * ## 为什么用强类型而不是读原文
 * `MonitorStorageResponse` 是 core 自己的键（不经过设备 goform，不会随固件漂移），
 * 「监控设置」页早就在用同一份模型渲染同一个数字 —— 这里再用一遍不会分叉。
 * 本项也**刻意不带 raw**：web 的同名条目没有，而"表名 + 行数 + 体积"已经是全部内容。
 */
private fun monitorStorageItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.monitorstorage",
    scope = DiagnosticScope.CORE,
    name = "监控数据库占用",
    desc = "core 监控数据库各表有多少行、占多大（我们自己的数据，可清理）。注意 sms_records 不参与清理，只会涨不会降。与下面「存储空间」不是一回事：那个答的是设备还有没有地方写。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
) {
    val s = api.service.getMonitorStorage()
    DiagnosticResult(
        status = DiagStatus.OK,
        summary = "监控数据库合计 ${s.total_display}（${s.tables.size} 张表）",
        rows = s.tables.map { table ->
            table.name to "${table.count} 条 · ${String.format(Locale.US, "%.1f", table.size_kb)} KB"
        },
    )
}

/**
 * 存储空间。
 *
 * 阈值（90% / 97%）是**排障用的经验值**，不是产品规则：core 的日志、监控库、下载缓存都写
 * 在同一个分区上，写满之后的表现是"某些功能莫名其妙失败"（数据库写不进去、日志截断），
 * 而错误信息里不会出现"磁盘满"。所以要在它发生之前就报出来。
 *
 * ⚠ 与上面 [monitorStorageItem] 是**两件事**（那条答"我们自己的数据占了多少"）。
 */
private fun storageItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.storage",
    scope = DiagnosticScope.CORE,
    name = "存储空间",
    desc = "设备数据分区的占用。写满时表现为「某些功能莫名失败」，而不是一条明确的报错。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
) {
    val s = api.service.getStorageInfo()
    DiagnosticResult(
        status = when {
            s.usage_percent >= 97.0 -> DiagStatus.FAIL
            s.usage_percent >= 90.0 -> DiagStatus.WARN
            else -> DiagStatus.OK
        },
        summary = "已用 ${FormatUtils.formatPercent(s.usage_percent)} · " +
            "剩余 ${FormatUtils.formatBytes(s.available)}",
        rows = listOf(
            "总量" to FormatUtils.formatBytes(s.total),
            "已用" to FormatUtils.formatBytes(s.used),
            "剩余" to FormatUtils.formatBytes(s.available),
            "使用率" to FormatUtils.formatPercent(s.usage_percent),
        ),
    )
}

/**
 * 媒体库授权。
 *
 * 三类（视频 / 音乐 / 图片）各有一份授权，**任一类未授权都必须报出来**：
 * 未授权时对应页面显示的是空列表，而空列表在用户眼里等于"设备里没有歌 / 视频" ——
 * 这是"假空态"，排障页有责任把它和真的空库区分开。
 */
private fun mediaItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.media",
    scope = DiagnosticScope.CORE,
    name = "媒体库权限",
    desc = "三类媒体各自的读取授权与扫描范围。未授权时对应页面显示空列表，看着像「设备里没有文件」。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
) {
    val status = api.service.getMediaStatus()
    val types = listOf(MEDIA_TYPE_VIDEO, MEDIA_TYPE_AUDIO, MEDIA_TYPE_IMAGE)
    val denied = types.filter { status.granted[it] != true }
    DiagnosticResult(
        status = if (denied.isEmpty()) DiagStatus.OK else DiagStatus.WARN,
        summary = if (denied.isEmpty()) {
            "三类媒体库均已授权" + if (status.all_files_access) "（含全文件访问）" else ""
        } else {
            "未授权：${denied.joinToString("、") { mediaTypeLabel(it) }} —— 对应页面会显示授权引导"
        },
        rows = buildList {
            add("全文件访问" to boolLabel(status.all_files_access))
            add("Android SDK" to status.sdk_int.toString())
            for (type in types) {
                val granted = status.granted[type]
                val dirs = status.scan_dirs[type].orEmpty()
                add(
                    mediaTypeLabel(type) to buildString {
                        append(boolLabel(granted))
                        append(" · ")
                        // 空扫描范围 = 这一类不限目录（core 的既有语义），不是"没配所以什么都扫不到"
                        append(if (dirs.isEmpty()) "不限目录" else "${dirs.size} 个目录")
                    }
                )
            }
        },
    )
}

// ────────────────────────── 权限（2026-10-14 新增，本批重点） ──────────────────────────

/**
 * 兜底授权命令（**不依赖任何权限清单**）。
 *
 * 为什么写成一条而不是一串固定权限名：清单的唯一真源是 core 已安装的 manifest，
 * 客户端抄一份就是第 4 份会漂移的镜像（安装脚本、Android 安装器、AppManager 三份
 * 已经互不一致了）。这条命令在设备上**现读** `dumpsys package` 的权限名再逐条 `pm grant`
 * （对没声明的权限 grant 只会静默失败，无害），最后把四条 appop 类单独放行 ——
 * 它们 `pm grant` 必然失败，只能 `appops set`（其中 `PACKAGE_USAGE_STATS` 的 appop
 * 叫 `GET_USAGE_STATS`，与权限短名不同名）。
 *
 * 可以直接粘进「高级控制台 / 终端」页执行：那条通道就是 uid 2000 的 adb shell，
 * 与安装脚本用的是同一个身份。
 */
private const val FALLBACK_GRANT_COMMAND =
    "dumpsys package com.ufi_axis_core | grep -o \"android\\.permission\\.[A-Z_]*\" | sort -u | " +
        "while read p; do pm grant com.ufi_axis_core \$p 2>/dev/null; done; " +
        "appops set com.ufi_axis_core MANAGE_EXTERNAL_STORAGE allow; " +
        "appops set com.ufi_axis_core REQUEST_INSTALL_PACKAGES allow; " +
        "appops set com.ufi_axis_core GET_USAGE_STATS allow; " +
        "appops set com.ufi_axis_core SCHEDULE_EXACT_ALARM allow"

/** 权限类别 → 中文（只用于本项明细行，不参与任何判定）。 */
private val PERMISSION_KIND_LABEL = mapOf(
    "runtime" to "运行时权限",
    "appop" to "appop 控制",
    "install" to "安装时授予",
    "signature" to "签名级",
    "superseded" to "本机已取代",
    "unknown" to "本机没有",
)

/**
 * 缺哪些权限 → 会坏在哪。
 *
 * 为什么值得单写一张小表：`READ_MEDIA_AUDIO 未授权` 对用户是零信息，而"音乐页会是空列表"
 * 是他一眼能对上的现象。**这张表只影响文案，不参与任何判定**（判定永远来自 core 的实测）。
 * 表里没有的权限回落成中性说法，不编一个可能错的功能名。
 */
private fun permissionEffectHint(shorts: List<String>): String {
    val hints = listOf(
        "READ_MEDIA_AUDIO" to "音乐页",
        "READ_MEDIA_VIDEO" to "视频页",
        "READ_MEDIA_IMAGES" to "图片页",
        "POST_NOTIFICATIONS" to "状态栏通知",
        "SCHEDULE_EXACT_ALARM" to "服务自恢复",
        "USE_EXACT_ALARM" to "服务自恢复",
        "PACKAGE_USAGE_STATS" to "流量统计",
        "MANAGE_EXTERNAL_STORAGE" to "文件管理",
        "REQUEST_INSTALL_PACKAGES" to "应用内安装 / 更新",
        "READ_SMS" to "短信列表",
        "SEND_SMS" to "本机短信渠道",
        "ACCESS_FINE_LOCATION" to "基站信息采集",
        "ACCESS_COARSE_LOCATION" to "基站信息采集",
        "READ_PHONE_STATE" to "SIM 与网络状态",
        "READ_PHONE_NUMBERS" to "本机号码",
    )
    val hit = hints.filter { it.first in shorts }.map { it.second }.distinct()
    return if (hit.isEmpty()) "相关功能" else hit.joinToString("、")
}

/**
 * **core 自身的权限**（`/api/system/permissions`，2026-10-14 新增，本批重点）。
 *
 * ## 它回答什么
 * "core 现在缺哪些它自己声明过、但没拿到的权限？"—— 这是"新版本 core 装了、功能却静默失效"
 * 的第一嫌疑。真源是 core 已安装的 manifest（不是任何手抄清单），判据是 core 侧的
 * `checkPermission` / `AppOpsManager`（不是"能不能用"的间接猜测）。
 *
 * ## 为什么这件事客户端自己做不了
 * 客户端看不见 core 的 manifest，也读不到 appop 模式。解析 `dumpsys package` 是另一条路，
 * 但输出格式随版本漂移、而且 appop 状态根本不在它里面（要另跑 `appops get` 再解析一遍）——
 * 判据会变成"解析出来像什么"。所以这条必须由 core 端点回答。
 *
 * ## 补全按钮只在"真能补"时才出现
 * 条件是 **`shell_privileged`（adb uid 2000 可用）且确实有可补的缺失**。缺一条就退化成
 * 纯指引（`missing` 里每条都带原文 `fix_command`，页面上直接能看到要执行什么）——
 * 给出一个必然失败的按钮是本页最该消灭的东西。
 */
private fun corePermissionItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.corepermission",
    scope = DiagnosticScope.CORE,
    name = "core 自身的权限",
    desc = "core 声明过、但实际没拿到的权限。清单来自已安装的 manifest，判据是系统自己的 checkPermission / AppOpsManager。缺权限时功能会「点了没用」且不报错 —— 升级 core 后新增的权限没人补，是这件事最常见的成因。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
) {
    val obj = api.rawGetJson("/api/system/permissions")
        ?: return@item DiagnosticResult(
            DiagStatus.WARN,
            "读不到 /api/system/permissions —— core 未启动，或版本过旧（这个端点 2026-10-14 才有）。" +
                "可先用下面的命令手动补全",
            rows = listOf(
                "手动兜底" to FALLBACK_GRANT_COMMAND,
                "在哪执行" to "粘进「高级控制台 / 终端」页即可（那条通道就是 uid 2000 的 adb shell）",
            ),
        )
    val missing = (obj["missing"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val blocked = (obj["blocked"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val notApplicable = (obj["not_applicable"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val privileged = obj.boolOf("shell_privileged") == true
    val pkg = obj.textOf("package").ifBlank { "com.ufi_axis_core" }
    val uid = obj.textOf("uid")
    val requested = obj.textOf("requested_count").toIntOrNull() ?: 0
    val grantedCount = obj.textOf("granted_count").toIntOrNull() ?: 0
    val shorts = missing.map { it.textOf("short").ifBlank { it.textOf("name") } }

    val rows = buildList {
        add("包名" to pkg)
        add("声明 / 已授" to "$requested / $grantedCount 项")
        add(
            "特权通道" to if (privileged) {
                "adb_shell（uid $uid）—— 可以补权限"
            } else {
                "普通 shell（uid $uid）—— 补不了权限，先去修「root 与特权通道」那一项"
            }
        )
        for (m in missing) {
            val kind = PERMISSION_KIND_LABEL[m.textOf("kind")] ?: m.textOf("kind")
            val op = m.textOf("appop").takeIf { it.isNotBlank() }?.let { " · appop $it" }.orEmpty()
            add("缺失 · ${m.textOf("short")}" to kind + op)
        }
        for (b in blocked) {
            add("补不了 · ${b.textOf("short")}" to b.textOf("note"))
        }
        if (notApplicable.isNotEmpty()) {
            // 「本机不适用」必须显式出现：否则用户会问"为什么没检查 WRITE_EXTERNAL_STORAGE"，
            // 而答案是"这台 Android 已经不用它了"—— 不算缺失，也补不了。
            add(
                "本机不适用" to notApplicable.joinToString("、") { it.textOf("short") } +
                    " —— 本机 Android 版本已不需要（不算缺失，不用管）"
            )
        }
        if (missing.isNotEmpty()) {
            // "具体到命令"是硬要求：按钮补不了（或用户更信命令）时，原文命令必须在页面上看得到。
            add("手动兜底" to FALLBACK_GRANT_COMMAND)
        }
    }

    when {
        missing.isNotEmpty() -> DiagnosticResult(
            DiagStatus.WARN,
            "缺 ${missing.size} 项权限：${shorts.joinToString("、")} —— " +
                "依赖它们的${permissionEffectHint(shorts)}会「点了没用」，而且不会报错",
            rows = rows,
            raw = prettyOf(obj),
            write = if (privileged) {
                DiagWriteAction(
                    label = "补全 ${missing.size} 项权限",
                    confirmTitle = "补全 core 权限",
                    confirmBody = "将对 $pkg 执行 ${missing.size} 条授权命令（走设备内置 adb，uid 2000）：" +
                        missing.joinToString("；") { it.textOf("fix_command").ifBlank { it.textOf("short") } } +
                        "。这会真实改变该应用的权限。" +
                        "⚠ 本页**无法撤销**（core 没有 revoke 端点）：要回退只能在终端里执行 " +
                        "`pm revoke <包名> <权限>` 或 `appops set <包名> <OP> default`。" +
                        "顺带会把「长期未使用的权限自动回收」设为 ignore（可逆，防止补完过一阵又被系统收走）。确认执行？",
                    destructive = true,
                ) {
                    val res = api.rawPostJson("/api/system/permissions/grant", JsonObject(emptyMap()))
                    if (res == null) {
                        "补全请求没有得到响应 —— core 未启动，或版本过旧（这个端点 2026-10-14 才有）"
                    } else {
                        val ok = res.textOf("granted").toIntOrNull() ?: 0
                        val failed = res.textOf("failed").toIntOrNull() ?: 0
                        val still = (res["permissions"] as? JsonObject)
                            ?.let { (it["missing"] as? JsonArray)?.size } ?: 0
                        val tail = res.textOf("auto_revoke").takeIf { it.isNotBlank() }
                            ?.let { "自动回收保护：$it" }.orEmpty()
                        "补全结果：成功 $ok 项" + (if (failed > 0) "、失败 $failed 项" else "") +
                            "；重新探测后仍缺 $still 项。" + tail
                    }
                }
            } else {
                null
            },
        )

        blocked.isNotEmpty() -> DiagnosticResult(
            DiagStatus.INFO,
            "core 声明的权限都已就位；另有 ${blocked.size} 项签名级权限拿不到" +
                "（需要以系统应用方式安装，不影响功能）",
            rows = rows,
            raw = prettyOf(obj),
        )

        else -> DiagnosticResult(
            DiagStatus.OK,
            "core 声明的 $requested 项权限全部就位",
            rows = rows,
            raw = prettyOf(obj),
        )
    }
}

/**
 * 本机通知授权（**app 侧独有**：这是"开了推送却收不到系统通知"在手机上的第一现场）。
 *
 * ## 四层闸门，逐层报出来（"收不到"的答案必在其中之一）
 * 1. **系统权限** `POST_NOTIFICATIONS`（Android 13+ 才有这条运行时权限）；
 * 2. **系统总开关** `areNotificationsEnabled()` —— 权限给了、用户在系统设置里把本应用的通知
 *    关掉，同样一条都发不出去（权限仍是 granted）。这两层必须分开报，因为**修法不同**；
 * 3. **应用内总闸** `KEY_NOTIFY_MASTER` 与**分类开关**数（总闸开着但一个分类都没开 = 一条都不会来，
 *    这是"假空态"在通知上的版本）；
 * 4. **五条通知渠道**：`IMPORTANCE_NONE` = 用户/系统把这条渠道关了，落到它的通知会被直接丢掉。
 *    渠道的显示名与重要性都**从系统读**（`getNotificationChannel`），不抄一份
 *    `ensureChannels` 的声明表 —— 抄一份就会漂移。
 *
 * ## 两种"未授权"必须分清（用户明确要求）
 * · **还没申请过**：还能再申请 —— 全仓**唯一**会弹授权框的地方是「通知与守护」页
 *   （`NotificationsGuardScreen` 的 `permissionLauncher`），所以指引写的是去那一页；
 * · **已被拒绝过两次 / 系统里关掉了**：**系统不会再弹框**（Android 13+ 二次拒绝后
 *   `requestPermission` 立即回调 denied），只能在系统「应用通知」里手动允许。
 *
 * 为什么本项**不给写按钮**：请求运行时权限需要 Activity 与 launcher，而这两样都在
 * 「通知与守护」页（那也是它自己的真源）；本页再实现一遍就是第二套申请逻辑 ——
 * 与 web 侧"缓存维护的真源在性能面板、诊断页只读"是同一条取舍。所以给的是**跳转入口**
 * （描述符的 `action`，与 `run()` 共存），并把两种状态的修法写进结论。
 */
private fun notifyPermissionItem(context: Context): DiagnosticItem = item(
    id = "env.notifypermission",
    // 本端专有：查的是**这台手机**的系统通知权限与渠道。web 有它自己的「浏览器通知授权」
    // （`notify-permission`），但那查的是浏览器状态 —— 两者**不是同一件事**，别配对。
    scope = DiagnosticScope.CLIENT,
    name = "本机通知授权",
    desc = "状态栏通知能不能真的发出来：系统权限、系统总开关、应用内总闸与分类开关、以及五条通知渠道有没有被关掉。四层里任何一层关着，通知都会静默消失。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.AUTO,
    action = DiagAction("去通知与守护页", Routes.DETAIL_NOTIFICATIONS_GUARD),
) {
    val sdk = Build.VERSION.SDK_INT
    val compat = NotificationManagerCompat.from(context)
    val systemAllowed = compat.areNotificationsEnabled()
    // Android 13+ 才有 POST_NOTIFICATIONS；13 以下 checkSelfPermission 恒为 GRANTED，
    // 所以显式按 SDK 判，而不是把"老系统没有这条权限"报成"未授权"。
    val permGranted = sdk < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val masterOn = NotifyPrefs.switchOn(context, NotificationCenter.KEY_NOTIFY_MASTER, false)
    val prefs = context.getSharedPreferences(NotificationCenter.PREFS_NAME, Context.MODE_PRIVATE)
    val categoriesOn = NotificationCenter.CATEGORY_KEYS.count { prefs.getBoolean(it, false) }
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    // 渠道清单来自 NotificationCenter 的公开常量（唯一真源），显示名与重要性都问系统要。
    val channelIds = listOf(
        NotificationCenter.CHANNEL_ALERTS,
        NotificationCenter.CHANNEL_CONNECTIVITY,
        NotificationCenter.CHANNEL_SMS,
        NotificationCenter.CHANNEL_DOWNLOADS,
        NotificationCenter.CHANNEL_EVENTS,
    )
    val channels = channelIds.map { id -> id to runCatching { nm?.getNotificationChannel(id) }.getOrNull() }
    val muted = channels.filter { it.second?.importance == NotificationManager.IMPORTANCE_NONE }
    val notCreated = channels.filter { it.second == null }

    val rows = buildList {
        add("系统权限" to if (sdk < Build.VERSION_CODES.TIRAMISU) "本机不需要（Android 13 以下）" else boolLabel(permGranted))
        add("系统总开关" to boolLabel(systemAllowed))
        add("应用内总闸" to boolLabel(masterOn))
        add("分类开关" to "$categoriesOn / ${NotificationCenter.CATEGORY_KEYS.size} 类开启")
        for ((id, channel) in channels) {
            val name = channel?.name?.toString()?.takeIf { it.isNotBlank() } ?: id
            add(name to channelImportanceLabel(channel?.importance))
        }
        if (notCreated.isNotEmpty()) {
            add(
                "尚未创建" to notCreated.joinToString("、") { it.first } +
                    " —— 首次发对应通知时才创建（不是故障）"
            )
        }
    }

    when {
        !permGranted -> DiagnosticResult(
            DiagStatus.FAIL,
            "系统通知权限未授予 —— 通知一条都发不出去。可以去「通知与守护」页打开总闸申请（那里是全仓唯一会弹授权框的地方）；" +
                "**若之前已拒绝过两次，系统不会再弹框**，只能在系统「应用通知」里手动允许",
            rows = rows +
                ("怎么修" to "打开「通知与守护」页的总闸：能申请时它弹系统授权框；弹不出来时它会直接把你送到系统「应用通知」设置"),
        )

        !systemAllowed -> DiagnosticResult(
            DiagStatus.FAIL,
            "权限已授予，但系统里本应用的通知被关掉了 —— 通知一条都发不出去，且权限框不会再出现，只能在系统「应用通知」里打开",
            rows = rows + ("怎么修" to "系统设置 → 应用 → 本应用 → 通知：把总开关打开（「通知与守护」页也会把你送到那一页）"),
        )

        !masterOn -> DiagnosticResult(
            DiagStatus.WARN,
            "系统侧都放行了，但应用内的通知总闸是关的 —— 通知会被本应用自己丢掉（日志里只有一行 DEBUG）",
            rows = rows + ("怎么修" to "去「通知与守护」页打开「全局通知」总闸"),
        )

        muted.isNotEmpty() -> DiagnosticResult(
            DiagStatus.WARN,
            "${muted.joinToString("、") { it.second?.name?.toString() ?: it.first }} 渠道被设为「关闭」—— " +
                "落到这些渠道的通知会被系统直接丢掉（总闸开着也没用）",
            rows = rows + ("怎么修" to "系统设置 → 应用 → 本应用 → 通知 → 对应渠道：重新打开"),
        )

        categoriesOn == 0 -> DiagnosticResult(
            DiagStatus.WARN,
            "总闸与系统都放行了，但一个分类开关都没开 —— 一条通知都不会来（这是「假空态」在通知上的版本）",
            rows = rows + ("怎么修" to "去「通知与守护」页勾选你需要的分类（告警 / 连接状态 / 短信 / 验证码…）"),
        )

        else -> DiagnosticResult(
            DiagStatus.OK,
            "通知可以送达：权限已授予、系统放行、总闸与 $categoriesOn 类分类开着、五条渠道没有被关闭",
            rows = rows,
        )
    }
}

/** 通知渠道重要性 → 中文。读的是**系统里的实际值**，不是创建时的声明值。 */
private fun channelImportanceLabel(importance: Int?): String = when (importance) {
    null -> "不存在"
    NotificationManager.IMPORTANCE_NONE -> "关闭（IMPORTANCE_NONE）—— 落到这里的通知会被丢掉"
    NotificationManager.IMPORTANCE_MIN -> "最低（静默，不出现在状态栏）"
    NotificationManager.IMPORTANCE_LOW -> "低（无声，无横幅）"
    NotificationManager.IMPORTANCE_DEFAULT -> "默认（有声，无横幅）"
    NotificationManager.IMPORTANCE_HIGH -> "高（有横幅）"
    NotificationManager.IMPORTANCE_MAX -> "最高（横幅 + 提示音）"
    else -> "未知（$importance）"
}

/**
 * CPU 控制能力探测（`GET /api/cpu-probe`，2026-10-14 补，**core 类**）。
 *
 * ## 判据与 web 的 `cpu-probe` 逐条对齐（`diagnosticRegistry.ts` 的 `runCpuProbe`）
 * · **失败也是 200**：core 的失败形态是 `{ok:false, error}`，不能按 HTTP 码判 ——
 *   所以先读 `ok`，为 false 时直接给 FAIL + core 的原话；
 * · `freq_writable` / `gov_writable` 用 **all**（全部 policy 都可写才算"可写"），
 *   与 web 的 `policies.every(...)` 一致；`policies` 为空时它们是 false（不是"未知"）；
 * · 在线核数 = `online_nodes` 里 `online == true` 的条数；热区数 = `thermal_zones` 的长度；
 * · 一条 policy 都没探到 ⇒ WARN（"这台设备可能不支持调频控制"），而不是 FAIL。
 *
 * ## 为什么本端是 MANUAL，而 web 是 auto
 * 它是**唯一一条慢检查**（core 要发 20+ 条 shell，5~15 秒）。web 敢放进自动批，是因为
 * web 的自动批已经改成了**串行队列**；本页的自动批仍是**并发**（理由见 [DiagnosticSection]
 * 文件头：重活此前压根不在 AUTO 组里，串行的代价是确定的、并发的风险是不确定的）。
 * 把这条塞进并发的自动批，等于让每次进页面都同时压上 20+ 条 shell —— 那正是 web 侧
 * "进页面一片红"的成因。所以这里跟着页面下半那张「CPU 控制能力」卡的口径走：**点了才跑**。
 * （归属仍是 `CORE`：两端都有这一项，只是"什么时候跑"各按各的批处理策略。）
 *
 * ## 为什么读原文而不是强类型
 * `getCpuProbe()` 回的就是 [JsonElement]（core 的 `UfiAxisApi` 注释写了原因：policy 数与
 * 键集合随内核漂移，强类型解析遇新键就空白），诊断场景要"能认的都显示出来"，原文进 raw。
 */
private fun cpuProbeItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "env.cpuprobe",
    scope = DiagnosticScope.CORE,
    name = "CPU 探针",
    desc = "这台设备能不能调频：cpufreq 节点是否存在、频率与 governor 能不能写、哪些核在线、有几个热区。★ 唯一一条慢检查（core 要发 20+ 条 shell，5~15 秒），全部命令只读或写回原值。",
    group = DiagnosticGroup.ENV,
    mode = RunMode.MANUAL,
) {
    val probe = api.service.getCpuProbe()
    val obj = probe as? JsonObject ?: return@item DiagnosticResult(
        DiagStatus.FAIL,
        "core 回的 /api/cpu-probe 不是 JSON 对象 —— 读不出结论，原文见下",
        raw = prettyOf(probe),
    )
    // 失败也是 200（见 KDoc）：判据是 ok 字段，不是 HTTP 码。
    if (obj.boolOf("ok") == false) {
        return@item DiagnosticResult(
            DiagStatus.FAIL,
            "探测失败：${obj.textOf("error").ifBlank { "未知原因" }}",
            raw = prettyOf(obj),
        )
    }
    val policies = (obj["policies"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val online = (obj["online_nodes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val zones = (obj["thermal_zones"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val onlineCount = online.count { it.boolOf("online") == true }
    // 空 policies 时 all{} 恒 true，会把"一个调频域都没有"说成"可写" —— 所以显式与 isNotEmpty() 相与。
    val freqWritable = policies.isNotEmpty() && policies.all { it.boolOf("freq_writable") == true }
    val govWritable = policies.isNotEmpty() && policies.all { it.boolOf("gov_writable") == true }
    val policyText = policies.joinToString("；") { p ->
        val cpus = (p["cpus"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.joinToString(",") ?: "?"
        "cpu$cpus · ${p.textOf("governor").ifBlank { "?" }} · ${p.textOf("max_freq").ifBlank { "?" }} kHz"
    }
    DiagnosticResult(
        status = if (policies.isNotEmpty()) DiagStatus.OK else DiagStatus.WARN,
        summary = if (policies.isNotEmpty()) {
            "${policies.size} 个调频域 · $onlineCount/${online.size} 核在线 · " +
                "频率节点${if (freqWritable) "可写" else "不可写"}"
        } else {
            "没有探测到任何 cpufreq 节点：这台设备可能不支持调频控制"
        },
        rows = listOf(
            "内核" to obj.textOf("kernel").ifBlank { "--" },
            "平台" to obj.textOf("board").ifBlank { "--" },
            "声明核数" to obj.textOf("cores_declared").ifBlank { "--" },
            "shell 身份" to obj.textOf("shell_uid").ifBlank { "--" },
            "调频域" to policyText.ifBlank { "--" },
            "频率节点可写" to boolLabel(freqWritable),
            "governor 可写" to boolLabel(govWritable),
            "在线核" to "$onlineCount / ${online.size}",
            "热区" to "${zones.size} 个",
        ),
        raw = prettyOf(obj),
    )
}

// ────────────────────────── NETWORK ──────────────────────────

/**
 * ping。
 *
 * ⚠ **`ok` 不是结论**：core 的 `okJson` 对成功与失败都写 `"ok":true`（只有参数非法才回
 * `ok:false` + 400），真正的成败在 `exit_code`。拿 `ok` 当结论会把"ping 不通"渲染成绿色对勾。
 */
private fun pingItem(api: DiagnosticApi, value: String): DiagnosticItem = item(
    id = "network.ping",
    scope = DiagnosticScope.CORE,
    name = "ping",
    desc = "从设备发出的 ICMP 回显请求（/system/bin/ping，4 个包）。看链路通不通、丢不丢包。",
    group = DiagnosticGroup.NETWORK,
    mode = RunMode.MANUAL,
    input = DiagInput("目标主机", "223.5.5.5", value),
) {
    val host = value.trim()
    val result = api.rawPostJson("/api/diagnostic/ping", buildJson("host" to host, "count" to "4"))
        ?: return@item unavailable()
    val code = result.exitCode()
    val output = result.outputText()
    val loss = Regex("""(\d+)% packet loss""").find(output)?.groupValues?.get(1)
    DiagnosticResult(
        status = if (code == 0) DiagStatus.OK else DiagStatus.FAIL,
        summary = if (code == 0) {
            "ping 通（exit=0" + loss?.let { " · 丢包 $it%" }.orEmpty() + "）"
        } else {
            "ping 失败（exit=$code）—— 目标不可达或链路不通"
        },
        rows = buildList {
            add("目标" to host)
            add("退出码" to code.toString())
            loss?.let { add("丢包率" to "$it%") }
        },
        raw = output.ifBlank { null },
    )
}

/**
 * DNS 解析。
 *
 * core 侧走系统 resolver（`InetAddress.getAllByName`），**不是**向某个 DNS 服务器发查询 ——
 * 所以它验证的是"这台设备能不能把域名解析出来"，解析不出来时 `exit_code=1`。
 */
private fun dnsItem(api: DiagnosticApi, value: String): DiagnosticItem = item(
    id = "network.dns",
    scope = DiagnosticScope.CORE,
    name = "DNS 解析",
    desc = "用设备自己的 resolver 解析一个域名。答「域名能不能变成 IP」——解析失败时 ping 域名必然也失败。",
    group = DiagnosticGroup.NETWORK,
    mode = RunMode.MANUAL,
    input = DiagInput("域名", "www.baidu.com", value),
) {
    val host = value.trim()
    val result = api.rawPostJson("/api/diagnostic/dns", buildJson("host" to host))
        ?: return@item unavailable()
    val code = result.exitCode()
    val output = result.outputText()
    val addresses = Regex("""^address: (.+)$""", RegexOption.MULTILINE)
        .findAll(output).map { it.groupValues[1] }.toList()
    DiagnosticResult(
        status = if (code == 0) DiagStatus.OK else DiagStatus.FAIL,
        summary = if (code == 0) {
            "$host 解析到 ${addresses.size} 个地址"
        } else {
            "$host 解析失败 —— 域名写错，或设备侧 DNS 不可用"
        },
        rows = buildList {
            add("域名" to host)
            add("退出码" to code.toString())
            add("地址" to addresses.joinToString("、").ifBlank { "（无）" })
        },
        raw = output.ifBlank { null },
    )
}

/**
 * TCP 端口探测。
 *
 * 一个输入框收 `host:port`（而不是两个框）：这两者只有**一起**才有意义（"通了"永远是
 * "到 host 的 port 通了"），拆成两个框会让"只填了主机"变成一个需要额外校验的中间态。
 * 解析不出来时不发请求、直接 FAIL —— 而不是把 `host:0` 丢给 core 换一个 400。
 */
private fun tcpItem(api: DiagnosticApi, value: String): DiagnosticItem = item(
    id = "network.tcp",
    scope = DiagnosticScope.CORE,
    name = "TCP 端口",
    desc = "从设备向某个 host:port 建一次 TCP 连接（5 秒超时）。确认「服务在跑但连不上」到底卡在哪。",
    group = DiagnosticGroup.NETWORK,
    mode = RunMode.MANUAL,
    input = DiagInput("host:port", "192.168.0.1:80", value),
) {
    val raw = value.trim()
    val host = raw.substringBeforeLast(':', "").trim()
    val port = raw.substringAfterLast(':', "").trim().toIntOrNull()
    if (host.isBlank() || port == null || port !in 1..65535) {
        return@item DiagnosticResult(
            DiagStatus.FAIL,
            "请按 host:port 填写（例如 192.168.0.1:80），端口要在 1~65535 之间",
            rows = listOf("当前填写" to raw.ifBlank { "（空）" }),
        )
    }
    val result = api.rawPostJson(
        "/api/diagnostic/tcp",
        buildJson("host" to host, "port" to port.toString()),
    ) ?: return@item unavailable()
    val code = result.exitCode()
    DiagnosticResult(
        status = if (code == 0) DiagStatus.OK else DiagStatus.FAIL,
        summary = if (code == 0) {
            "TCP $host:$port 连接成功"
        } else {
            "TCP $host:$port 连不上 —— 端口没开、被拦，或路由不通"
        },
        rows = listOf(
            "目标" to "$host:$port",
            "退出码" to code.toString(),
        ),
        raw = result.outputText().ifBlank { null },
    )
}

/**
 * traceroute（无 root 的 TTL 递增法）。
 *
 * ⚠ **core 把这一条的 `exit_code` 硬编码成 0**（`DiagnosticRoutes` 里 traceroute 分支的
 * `okJson("traceroute", 0, out)`），所以结论只能从 output 文本里读：
 *  · 出现 `reached <host> at hop N` → 到达目标；
 *  · 只有若干 `N: xx ms` 行 → 探到了路径但没走到目标（很多设备会拦 TTL 探测）；
 *  · 全是 `N: *` → 一跳都没回包。
 * 这一条也是本组最慢的（15 跳 × 最多 2 秒），所以只有手动才跑。
 */
private fun tracerouteItem(api: DiagnosticApi, value: String): DiagnosticItem = item(
    id = "network.traceroute",
    scope = DiagnosticScope.CORE,
    name = "traceroute",
    desc = "TTL 逐跳递增（最多 15 跳，无 root）。看包走到第几跳没了 —— 慢，所以只有手动才跑。",
    group = DiagnosticGroup.NETWORK,
    mode = RunMode.MANUAL,
    input = DiagInput("目标主机", "223.5.5.5", value),
) {
    val host = value.trim()
    val result = api.rawPostJson(
        "/api/diagnostic/traceroute",
        buildJson("host" to host, "max_hops" to "15"),
    ) ?: return@item unavailable()
    val output = result.outputText()
    val reached = Regex("""reached \S+ at hop (\d+)""").find(output)?.groupValues?.get(1)
    val responded = Regex("""^(\d+): (?!\*)""", RegexOption.MULTILINE)
        .findAll(output).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
    DiagnosticResult(
        status = when {
            reached != null -> DiagStatus.OK
            responded.isNotEmpty() -> DiagStatus.WARN
            else -> DiagStatus.FAIL
        },
        summary = when {
            reached != null -> "在第 $reached 跳到达 $host"
            responded.isNotEmpty() ->
                "只探到第 ${responded.max()} 跳（共 ${responded.size} 跳有回包），未到达目标"
            else -> "没有任何一跳回包 —— 链路上没有可用的探测响应"
        },
        rows = listOf(
            "目标" to host,
            "有回包的跳数" to responded.size.toString(),
            // 明写 core 的 exit_code 不可用，省得下一个人看到"退出码 0"以为一切正常
            "core 退出码" to "${result.exitCode()}（本端点硬编码为 0，不代表成功）",
        ),
        raw = output.ifBlank { null },
    )
}

// ────────────────────────── SERVICE ──────────────────────────

/**
 * 后台服务状态与崩溃记录。
 *
 * 两个端点合一条：`service/status` 说"现在怎么样"，`service/crash` 说"上一次怎么死的"。
 * 分开看会漏掉最要紧的那一问 —— "数据断了 5 分钟"到底是服务被停了，还是进程崩了又拉起。
 *
 * 判据优先级（崩溃 > 开关 > 采集循环）：崩溃是**已经发生过的事实**，
 * 它比"此刻开关是开的"更值得先说。
 */
private fun serviceItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "service.status",
    scope = DiagnosticScope.CORE,
    name = "服务状态与崩溃",
    desc = "后台采集开关、采集循环、HTTP 服务，以及上一次 core 崩溃的原因（崩溃后会自动拉起，只表现为数据断一下）。",
    group = DiagnosticGroup.SERVICE,
    mode = RunMode.AUTO,
) {
    val status = api.service.getServiceStatus()
    // 崩溃端点是后加的：老 core 会 404。读不到按"没有崩溃记录"处理（与 MainActivity 同口径），
    // 但下面会显式写一行"读取失败"，不让它静默消失。
    val crash = runCatching { api.service.getCoreCrashReport() }.getOrNull()
    val crashed = crash?.crashed == true
    DiagnosticResult(
        status = when {
            crashed -> DiagStatus.FAIL
            !status.enabled -> DiagStatus.WARN
            !status.collecting -> DiagStatus.WARN
            else -> DiagStatus.OK
        },
        summary = when {
            crashed -> "core 上次运行崩溃过：" +
                crash?.summary?.take(60)?.ifBlank { "无摘要" }.orEmpty()
            !status.enabled -> "后台采集服务已停止（HTTP 仍在跑，所以这一页还能打开）"
            !status.collecting -> "服务开关是开的，但采集循环没在跑"
            else -> "运行中 · 已运行 ${formatUptime(status.uptimeMs)}"
        },
        rows = buildList {
            add("后台采集开关" to boolLabel(status.enabled))
            add("采集循环" to boolLabel(status.collecting))
            add("HTTP 服务" to boolLabel(status.httpRunning))
            add("开机自启" to boolLabel(status.autoStartOnBoot))
            add("已运行" to formatUptime(status.uptimeMs))
            if (crash == null) {
                add("崩溃记录" to "读取失败（core 版本可能过旧）")
            } else {
                add("上次崩溃" to if (crash.crashed) "有" else "无")
                if (crash.crashed) {
                    add("崩溃时间" to FormatUtils.formatTimestamp(crash.timestamp))
                    if (crash.summary.isNotBlank()) add("摘要" to crash.summary)
                    // 详情文件在设备私有目录里，app 不读它，只告诉用户去哪找
                    if (crash.file.isNotBlank()) add("详情文件" to crash.file)
                }
            }
        },
    )
}

/**
 * QoS 与线程池。
 *
 * 两个坑照抄 `QosStatusResponse` 的 KDoc：`cpu_temp` 是**毫摄氏度**（读失败为 0，当摄氏度
 * 显示会得到 45000 ℃），`enabled` 是 core 硬编码的 true、**不是真开关**（真值在
 * `GET /api/config`），所以这里不展示它。
 */
private fun qosItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "service.qos",
    scope = DiagnosticScope.CORE,
    name = "QoS 与线程池",
    desc = "shell 并发许可、缓存与线程池占用。「命令排队很久」的答案通常在这一行。",
    group = DiagnosticGroup.SERVICE,
    mode = RunMode.AUTO,
) {
    val q = api.service.getQosStatus()
    val temp = q.cpuTempCelsius
    val rootPermits = q.shell_qos?.root
    val pool = q.dynamic_pool
    DiagnosticResult(
        // 温度只做参考：读不到（null）与偏高都给 WARN —— "排障页少了一行"本身就是信息
        status = when {
            temp == null -> DiagStatus.WARN
            temp >= 75.0 -> DiagStatus.WARN
            else -> DiagStatus.OK
        },
        summary = buildString {
            append(temp?.let { "CPU ${FormatUtils.formatTemperature(it)}" } ?: "CPU 温度读不到")
            rootPermits?.let { append(" · root 许可 ${it.available}/${it.total}") }
            pool?.let { append(" · 线程池 ${it.current}/${it.max}") }
        },
        rows = buildList {
            add("CPU 温度" to (temp?.let { FormatUtils.formatTemperature(it) } ?: "不可用（读失败为 0）"))
            rootPermits?.let {
                add(
                    "root 并发许可" to
                        "${it.available} / ${it.total}" + (it.target?.let { t -> " · 目标 $t" } ?: "")
                )
            }
            q.shell_qos?.normal?.let { add("普通并发许可" to "${it.available} / ${it.total}") }
            q.shell_qos?.cache?.let { add("shell 缓存" to "${it.entries} 条 · TTL ${it.ttl_ms}ms") }
            pool?.let { add("线程池" to "当前 ${it.current} · 核心 ${it.core} · 上限 ${it.max}") }
        },
    )
}

/**
 * 响应缓存健康（`GET /api/cache/stats`，2026-10-14 补，**core 类**）—— **只读摘要，
 * 不是第二套维护入口**。
 *
 * ## 真源在哪
 * 缓存维护的真源是**本页下半**那张「响应缓存」卡：条目数、体积、**清空**与**按规则失效**
 * 两个写入动作全在那边（`DiagnoseScreen`）。本项一个写入动作都不放 —— 诊断登记表的主业是
 * "看"（见文件头），而同一个数字在同一页出现两次只会让人怀疑哪个才是真的。
 * （web 侧形状相同：它的真源在「设置 → 性能 → 缓存管理」，诊断页只放只读摘要 + 跳转。）
 *
 * ## 那为什么还要在这里放一条
 * 下半那张卡只看得到"有多少条、占多大"，而**排障要看的是另外两个字段**，它们在本端此前
 * 一处都没被展示过（下半那张卡的两行文案是给日常看的，没有阈值判定，也不进诊断包）：
 * · `stale` —— 缓存被标记为"可能过期"（WS 客户端全部断开、内部刷新失败）。
 *   "我看到的数字为什么是旧的"通常就是这一行；
 * · `expired_count` —— 已过期但**惰性删除还没触发**的条目数，健康态应远小于 `count`。
 * 条目数与体积**刻意不在这里重复**（与 web 逐字一致的理由）：那在维护卡里已经有了。
 *
 * ## 阈值：`expired / count ≥ 0.5` 判"滞留"（与 web 的 `runCacheHealth` 同一口径）
 * 用**比例**而不是绝对条数：`count` 本身可大可小，"5 条里 5 条过期"与"500 条里 5 条过期"
 * 不是一回事，拿绝对数当阈值必然在两种规模下各错一次。`stale` 优先于它（它更严重：
 * 整个缓存的数字都可能是旧的）。
 */
private fun cacheHealthItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "service.cachehealth",
    scope = DiagnosticScope.CORE,
    name = "响应缓存健康",
    desc = "过期滞留（惰性删除没触发的条目）与 stale 标记（缓存可能过期）。只读摘要 —— 维护（清空 / 按规则失效）的真源是本页下半的「响应缓存」卡，本项刻意不放写入动作。",
    group = DiagnosticGroup.SERVICE,
    mode = RunMode.AUTO,
) {
    // 读原文而不是强类型：core 会回 `entries`（前 50 条明细）等一堆本项用不到的键，
    // 而"这次到底回了哪些字段"正是对账要的 —— 与 [debugLogItem] 同一手法。
    val obj = api.rawGetJson("/api/cache/stats")
        ?: return@item DiagnosticResult(
            DiagStatus.FAIL,
            "读不到 /api/cache/stats —— core 未启动，或版本过旧",
        )
    val count = obj.textOf("count").toIntOrNull() ?: 0
    val expired = obj.textOf("expired_count").toIntOrNull() ?: 0
    val stale = obj.boolOf("stale") == true
    val stuck = count > 0 && expired.toDouble() / count >= 0.5
    DiagnosticResult(
        status = when {
            stale -> DiagStatus.WARN
            stuck -> DiagStatus.WARN
            else -> DiagStatus.OK
        },
        summary = when {
            stale -> "缓存被标记为可能过期 —— 页面上的数字可能是旧的，去下面那张卡清一次再看"
            stuck -> "响应缓存过期滞留 $expired/$count 条 —— 回收几乎全靠读时惰性删除"
            else -> "响应缓存 $count 条，没有过期滞留，也没有 stale 标记"
        },
        rows = listOf(
            "过期滞留" to "$expired / $count 条（过期条目要等下一次读才被删掉）",
            "可能过期" to if (stale) {
                "是 · 缓存被标记为 stale（WS 曾全部断开或内部刷新失败）"
            } else {
                "否"
            },
        ),
        raw = prettyOf(obj),
    )
}

/**
 * 三条外部通知渠道的配置完整性（邮件 / Webhook / 本机短信，2026-10-14 补，**core 类**）。
 *
 * ## 它回答的是"看到功能在、点了没用、又不知道为什么"
 * 三条渠道的开关是**独立**的，而 core 侧的投递判据是 `开关 && 配置完整`：开关打开、
 * 但必填项没填完时，通知会被**静默跳过**（只在投递记录里留一行"配置不完整，未发送"，
 * 用户看不到）。所以"我开了邮件通知，一封也没收到"的答案通常就在这里。
 *
 * ## 判据直接读 core 的 `sendable` / `configured`，不自己复现
 * 三个字段名不一样是**core 的历史命名**：邮件是 `sendable`，另两条是 `configured`
 * （core 的注释写明"口径与 /api/notify/{sms,webhook}/config 的 configured 对齐，
 * 字段名沿用 /diagnose 已在用的 sendable"）。客户端自己算"填没填全"必然会分叉 ——
 * 那正是 core 加这两个字段要消掉的东西。渠道 → 完整性字段名的对应表**跟数据走**
 * （而不是另开一张按下标对齐的数组：那样一旦插入一条渠道就会静默错位）。
 *
 * ## 与 web 的唯一差异：部分读不到时**仍然显示其余渠道**
 * web 用 `Promise.all`，一条失败整项就 FAIL。本端的描述符纪律是"部分数据也要显示"
 * （见 [shellItem]），而"某条渠道的端点在旧 core 上不存在"恰恰是最常见的一种 ——
 * 整项 FAIL 会把另外两条能读到的渠道一起吞掉。所以：三条全读不到 ⇒ FAIL；
 * 只缺一条 ⇒ WARN 并**点名**是哪一条（绝不把它渲染成"未启用"：读不到 ≠ 没开）。
 */
private fun notifyChannelsItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "service.notifychannels",
    scope = DiagnosticScope.CORE,
    name = "通知渠道配置",
    desc = "邮件 / Webhook / 本机短信三条外部渠道的开关与配置完整性。判据直接读 core 的 sendable / configured，不自己复现「填没填全」——开关开着但配置不全时通知会被静默跳过。",
    group = DiagnosticGroup.SERVICE,
    mode = RunMode.AUTO,
) {
    // 三条渠道彼此独立，并发读（与 web 的 Promise.all 同一取向）；rawGetJson 在 IO 上跑，
    // 这里用 async 让三个请求真的同时在飞。
    val (mail, hook, sms) = coroutineScope {
        val mailTask = async { api.rawGetJson("/api/sms-forward/config") }
        val hookTask = async { api.rawGetJson("/api/notify/webhook/config") }
        val smsTask = async { api.rawGetJson("/api/notify/sms/config") }
        Triple(mailTask.await(), hookTask.await(), smsTask.await())
    }
    /** 渠道 → (名字, 配置, **完整性字段名**)。 */
    val channels = listOf(
        Triple("邮件", mail, "sendable"),
        Triple("Webhook", hook, "configured"),
        Triple("本机短信", sms, "configured"),
    )
    /** 一条渠道的三种状态。`readyKey` 在三条渠道上不同（core 的历史命名），由上面那张表带过来。 */
    fun channelText(cfg: JsonObject?, readyKey: String): String = when {
        cfg == null -> "读取失败（core 没有响应这个端点）"
        cfg.boolOf(readyKey) == true -> "已启用 · 配置完整"
        cfg.boolOf("enabled") == true -> "已启用但配置不完整 —— 投递会被静默跳过（$readyKey=false）"
        else -> "未启用"
    }

    val rows = channels.map { (name, cfg, readyKey) -> name to channelText(cfg, readyKey) }
    // `cfg != null` 写在最前面：后面两处要读它的字段，而"读不到"必须与"没开"分开
    //（读不到 → 上面那行已经渲染成"读取失败"，这里不能把它算成 broken 或未启用）。
    val broken = channels.filter { (_, cfg, readyKey) ->
        cfg != null && cfg.boolOf("enabled") == true && cfg.boolOf(readyKey) != true
    }
    val enabledCount = channels.count { (_, cfg, _) -> cfg?.boolOf("enabled") == true }
    val unreadable = channels.filter { (_, cfg, _) -> cfg == null }.map { it.first }
    val raw = JsonObject(
        mapOf(
            "mail" to (mail ?: JsonNull),
            "webhook" to (hook ?: JsonNull),
            "sms" to (sms ?: JsonNull),
        )
    )

    when {
        unreadable.size == channels.size -> DiagnosticResult(
            DiagStatus.FAIL,
            "三条外部渠道的配置都读不到 —— core 未启动，或版本过旧（这三个端点分别属于邮件 / Webhook / 本机短信）",
            rows = rows,
            raw = prettyOf(raw),
        )

        broken.isNotEmpty() -> DiagnosticResult(
            DiagStatus.WARN,
            broken.joinToString("、") { it.first } +
                " 已启用但配置不完整 —— 通知会被静默跳过（界面上看不出原因）" +
                if (unreadable.isNotEmpty()) "；另有 ${unreadable.joinToString("、")} 读取失败" else "",
            rows = rows,
            raw = prettyOf(raw),
        )

        unreadable.isNotEmpty() -> DiagnosticResult(
            DiagStatus.WARN,
            "${unreadable.joinToString("、")}的配置读取失败 —— core 没有响应对应端点（其余渠道照常显示）",
            rows = rows,
            raw = prettyOf(raw),
        )

        enabledCount > 0 -> DiagnosticResult(
            DiagStatus.OK,
            "已启用 $enabledCount/3 条外部渠道，配置均完整",
            rows = rows,
            raw = prettyOf(raw),
        )

        else -> DiagnosticResult(
            DiagStatus.INFO,
            "三条外部渠道都没启用 —— 只有状态栏通知这一条路（本机通知 / 页面内提示）",
            rows = rows,
            raw = prettyOf(raw),
        )
    }
}

/**
 * `/api/diagnose` 全量快照。
 *
 * **走裸 GET 读原文**（[DiagnosticApi.rawGetJson]），再 `decodeFromJsonElement` 取强类型字段：
 * 一次请求同时拿到"能读的行"与"逐字原文"。原文才是这一项的主要产出 ——
 * 强类型模型会把 core 新加的键静默丢掉，而"这次到底回了哪些字段"正是设备适配对账的判据。
 *
 * 默认**不带 `fields=1`**：带上会让 core 逐分组向设备发查询（最多 10 组），
 * 那是「字段覆盖率」的事（在页面下部的高级区，独立按钮、独立 loading）。
 */
private fun snapshotItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "service.snapshot",
    scope = DiagnosticScope.CORE,
    name = "全量诊断快照",
    desc = "/api/diagnose 的完整原文（服务器时间、网关、adbd、设备 profile…）。展开可复制，用于逐字对账。",
    group = DiagnosticGroup.SERVICE,
    mode = RunMode.AUTO,
) {
    val rawObject = api.rawGetJson("/api/diagnose")
        ?: return@item DiagnosticResult(
            DiagStatus.FAIL,
            "读不到 /api/diagnose —— core 未启动或版本过旧",
        )
    // 解析失败不影响原文展示：原文已经拿到了，强类型只是"顺便多几行好读的"。
    val typed = runCatching {
        JSON.decodeFromJsonElement(DiagnoseResponse.serializer(), rawObject)
    }.getOrNull()
    val profile = typed?.device_profile
    val fallback = profile?.status == "fallback"
    // 用 if/else 而不是 when：`else` 分支要读 typed 的字段，而 if/else 的智能转换最直观可靠
    val summary = if (typed == null) {
        "拿到原文，但结构不符合本版模型（core 版本可能更新）—— 请看原始输出"
    } else if (fallback) {
        "设备 profile 已回落（配置的「${profile?.configured.orEmpty()}」不在注册表里）" +
            " —— 型号大概率填错了"
    } else {
        "服务器时间 ${FormatUtils.formatTimestamp(typed.server_time)} · 网关 ${typed.gateway}"
    }
    DiagnosticResult(
        status = when {
            typed == null -> DiagStatus.WARN
            fallback -> DiagStatus.WARN
            else -> DiagStatus.OK
        },
        summary = summary,
        rows = buildList {
            if (typed != null) {
                add("服务器时间" to FormatUtils.formatTimestamp(typed.server_time))
                // 三级兜底全失败时 core 回硬编码 192.168.0.1，不代表真探到了网关
                add("网关" to typed.gateway)
                add("adbd" to typed.adbd)
                add("移动数据开关" to typed.mobile_data)
                add("root" to boolLabel(typed.root))
                profile?.let {
                    add("profile 状态" to it.status.ifBlank { "未知" })
                    add("生效 profile" to it.active.ifBlank { "无" })
                    add("归一化运行时" to boolLabel(it.normalization_enabled))
                }
                // app_version 在 core 里硬编码 "0.1"，明写出来省得有人拿它当版本号
                add("app_version" to typed.app_version + "（core 硬编码，不是版本号）")
            }
            add("原文字段数" to rawObject.size.toString())
        },
        raw = prettyOf(rawObject),
    )
}

/**
 * 调试日志：**落盘文件清单（读） + 去日志页（入口）**（2026-10-14 改）。
 *
 * ## 为什么从"跳转类条目"改成"检查 + 入口"
 * 原先这一项 `route` 非空 ⇒ 卡片只画一个 chevron、什么都不读，文案是"点击进入"。
 * 但排障现场最常见的一类假故障恰恰是"**我明明开了日志，怎么一个文件都没有**" ——
 * 那要看的就是 `/api/debug-logs/files` 的文件数 / 大小 / 最后写入时间，
 * 而"跳转 ⇒ 不跑"这条规则让这一项永远给不出这个结论。
 * 现在它既是检查（AUTO，一次 GET，无副作用），也带一个入口（[DiagAction]），
 * 形状与 web 的 `debug-logs` 那条完全一致。
 *
 * ## 为什么"没有文件"是 INFO 而不是 FAIL
 * 日志总开关或「后端日志」关着时就是没有文件 —— 那是**配置的结果**，不是故障。
 * 但必须把那句可能的原因写进 summary：只说"没有文件"的话，用户会去查磁盘、查权限，
 * 而真实原因只是某个开关没开（这是"假空态"在日志上的版本，与媒体库那条同一个道理）。
 */
private fun debugLogItem(api: DiagnosticApi): DiagnosticItem = item(
    id = "service.debuglog",
    scope = DiagnosticScope.CORE,
    name = "调试日志",
    desc = "core 落盘日志有几个文件、多大、最后一次写是什么时候。这里只看清单，正文与开关在调试日志页。",
    group = DiagnosticGroup.SERVICE,
    mode = RunMode.AUTO,
    action = DiagAction("去日志页", Routes.DETAIL_DEBUG_LOG),
) {
    val obj = api.rawGetJson("/api/debug-logs/files")
        ?: return@item DiagnosticResult(
            DiagStatus.FAIL,
            "读不到 /api/debug-logs/files —— core 未启动，或版本过旧（这个端点 2026-08-28 才有）",
        )
    val files = obj["files"] as? JsonArray ?: JsonArray(emptyList())
    // core 侧已按 modified 倒序（新 → 旧），这里不再排一次；只取前几条 ——
    // 本页要回答的是"有没有在落盘"，全量清单属于日志页（那里能删、能读尾部）。
    val recent = files.mapNotNull { it as? JsonObject }.take(5)
    val totalBytes = obj.textOf("total_bytes").toLongOrNull() ?: 0L
    DiagnosticResult(
        status = if (files.isEmpty()) DiagStatus.INFO else DiagStatus.OK,
        summary = if (files.isEmpty()) {
            "还没有落盘日志文件 —— 日志总开关或「后端日志」可能关着（不是磁盘或权限问题）"
        } else {
            "落盘日志 ${files.size} 个文件，合计 ${FormatUtils.formatBytes(totalBytes)}"
        },
        rows = buildList {
            for (f in recent) {
                val name = f.textOf("name").ifBlank { "?" }
                val size = f.textOf("size").toLongOrNull() ?: 0L
                val modified = f.textOf("modified").toLongOrNull() ?: 0L
                // modified 缺失时**不要**把 0 交给 formatTimestamp —— 那会渲染成 1970 附近的
                // 一个假时刻（本机时区下是 "01-01 08:00:00"），比"时间未知"糟得多。
                val stamp = if (modified > 0) FormatUtils.formatTimestamp(modified) else "时间未知"
                add(name to "${FormatUtils.formatBytes(size)} · $stamp")
            }
            if (files.size > recent.size) {
                add("…" to "另有 ${files.size - recent.size} 个文件（去日志页看全部）")
            }
            add("日志目录" to obj.textOf("dir").ifBlank { "未知" })
        },
        // 原文也带上：web 的同名条目就是这么做的，而"core 这次到底回了哪些键"是对账要的。
        raw = prettyOf(obj),
    )
}

// ────────────────────────── 小工具 ──────────────────────────

/** 描述符侧解析 JSON 用的 Json（与 [prettyJson] 同一配置，避免两处 ignoreUnknownKeys 不一致）。 */
private val JSON = Json { ignoreUnknownKeys = true }

/** 构造 `{...}` 请求体。`/api/diagnostic/…` 的取值都用字符串（core 侧一律走 `contentOrNull`）。 */
private fun buildJson(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

/** `exit_code`。**不是 `ok`** —— 理由见 [pingItem]。 */
private fun JsonObject.exitCode(): Int = this["exit_code"]?.jsonPrimitive?.intOrNull ?: -1

/** 取一个标量键的文本（数字与字符串都吃，core 的 `size` / `modified` 是数字）。缺键回空串。 */
private fun JsonObject.textOf(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** 取一个布尔键。缺键 / 不是布尔回 **null**（不能拿 false 冒充"否"，与 [boolLabel] 同口径）。 */
private fun JsonObject.boolOf(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** `output`（shell stdout 原文）。 */
private fun JsonObject.outputText(): String =
    this["output"]?.jsonPrimitive?.contentOrNull.orEmpty()

/** 端点整体不可用（core 没回 / 不是 JSON / 404）时的统一结论。 */
private fun unavailable(): DiagnosticResult = DiagnosticResult(
    DiagStatus.FAIL,
    "core 没有响应这个诊断端点 —— 服务未启动，或 core 版本过旧（需要 /api/diagnostic/…）",
)
