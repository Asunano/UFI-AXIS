// [F24] STABLE-UI-API 不适用：本包是 2026-10-14 新建的诊断中心，暂无外部消费者。
package com.ufi_axis.ui.diagnose

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.AppHttpClient
import com.ufi_axis.util.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 诊断中心 —— **描述符层**（2026-10-14）。
 *
 * ## 这个文件解决什么问题
 * 排障信息此前是"一处一屏"的：`DiagnoseScreen` 里五张手写卡（特权 shell / core 运行时 /
 * 字段覆盖率 / QoS / 响应缓存），网络连通性压根没有 app 侧入口（core 的 `/api/diagnostic/…`
 * 一直只有 web 在用），服务状态与崩溃只在 MainActivity 的弹窗里露一下。每加一项检查都要
 * 动页面、动布局、动状态 —— 而"再加一项检查"是排障页唯一会持续发生的事。
 *
 * 现在差异写进**数据**而不是写进 `if (id == "xxx")`：新增一项检查 = 在 [DiagnosticRegistry]
 * 里加一条描述符，[DiagnosticSection] 一行都不用改。
 *
 * ## 分层（每层只做一件事，改一层不牵动别层）
 *  · 本文件 —— **描述**：一项检查长什么样、跑完怎么把结果翻成人话；
 *  · [DiagnosticRegistry] —— **登记**：本次落地的 20 条描述符；
 *  · [DiagnosticSection] —— **执行 + 渲染**：跑 AUTO / 按需跑 MANUAL，遍历登记表逐条画卡；
 *  · `DiagnoseScreen` —— **页面**：本区块 + 保留的写入类卡片（字段归一化 / 缓存维护）。
 *
 * ## 与 `app/feature-resources` 的关系（同构但形状不同）
 * 资源中心是"**读状态 → 下发任务 → 轮询终态**"，一张卡对应一个长生命周期任务；
 * 诊断是"**跑一次 → 渲染结果**"，没有任务态、没有进度、可以随时重跑。所以这里没有
 * `ResourceTaskController` 那层 runner：AUTO 项在进页面 / 手动刷新时并发跑一遍，
 * MANUAL 项点了才跑，结果只是一份不可变的 [DiagnosticResult]。
 *
 * ## 硬约束
 * [DiagnosticSection] 里**不允许**出现 `if (id == "xxx")` 这类分支。所有差异只走下面这些
 * 字段与可选成员；[DiagnosticGroup] 是**唯一**的分组开关，而它表达的也只是"这一段回答什么
 * 问题"，不是某一项的身份。
 *
 * 同理，卡片头上的**归属角标**读的是 [DiagnosticItem.scope]，**不是**按 id 或 group 写死的
 * 名单（"core 类项"这件事只由那个字段回答，而它同时是跨端自检脚本的判据）。
 *
 * ## 为什么描述符里不写 try/catch
 * 一处兜底比十四处兜底可靠：[DiagnosticSection] 的 runner 把任何异常翻成
 * [DiagStatus.FAIL] + 一句人话。描述符只负责"怎么读、怎么解释"，
 * 于是每条 `run()` 都是直筒子代码，不会出现"某一条忘了兜异常 → 整页崩"。
 * 只有**部分数据也要显示**的复合项（如 root 通道 = 两个端点）才在描述符内部 `runCatching`。
 */

/**
 * 检查项分组。**唯一的分组开关**，表达的是"这一段回答什么问题"。
 *
 * 顺序即界面顺序（[DiagnosticGroup.entries] 按声明顺序）。
 *
 * 为什么是三个而不是按端点分（core / 设备 / 网络）：用户拿着手机排障时的问法是
 * 「这台设备给了我什么权限」「网通不通」「后台服务活着吗」，不是"哪个端点在报错"。
 * 同一段里的项按登记顺序排列，相关的检查自然挨在一起。
 */
enum class DiagnosticGroup(val title: String, val subtitle: String) {
    /** 设备本身的状态与权限：答"我能做什么"。 */
    ENV("设备环境", "权限、存储、媒体库授权"),

    /** 主动向外部发包：答"通不通、卡在哪"。都要填目标，所以全是手动。 */
    NETWORK("网络连通", "ping / DNS / TCP / traceroute"),

    /** core 自身：答"后台服务活着吗、出过什么事"。 */
    SERVICE("后台服务", "服务状态、崩溃、QoS、全量快照"),
}

/**
 * 何时跑。
 *
 * [AUTO] 的项必须满足两个条件：**只读、无副作用**且**够快** —— 它们会在每次进页面和
 * 每次点刷新时全部跑一遍。
 *
 * 判据**不是"碰不碰设备"**（2026-10-14 更正）：`env.shell` 会跑 `id`、`service.snapshot`
 * 的 `/api/diagnose` 会跑 4 条 root shell，两者都确实在设备上执行了命令 —— 但它们只读、
 * 秒级返回，所以仍然是 AUTO。真正的判据是三条：会不会**改**设备状态、会不会**出网**、
 * 以及**耗时**。字段覆盖率（逐分组向设备发查询）与 CPU 探测（20+ 条 shell、5~15 秒）
 * 因此留在页面下半的手动区，ping 等四条会向外部目标发包，一律 [MANUAL]。
 *
 * ⚠ 反过来说，"只读"也**不等于"可以无限并发"**：core 的 root 通道是全局信号量限流的，
 * 自动批里那几条 root shell 同时发出去会互相排队（web 侧为此把自动批改成串行队列，
 * 见 `DiagnosticRegistry` 文件头）。本页的自动批目前仍是并发，见 `DiagnosticSection`。
 */
enum class RunMode {
    /** 进页面 / 点刷新就跑。 */
    AUTO,

    /** 用户点了才跑（会打设备或要等网络超时）。 */
    MANUAL,
}

/**
 * 结论档。决定颜色，不决定文案（文案在 [DiagnosticResult.summary]）。
 *
 * [INFO] 与 [OK] 的区别是"有没有通过/不通过这回事"：调试日志入口、未装 Magisk 这类
 * 既不是成功也不是失败的事实用 [INFO]，避免整页绿色对勾里混进一个"没检测到"。
 */
enum class DiagStatus {
    /** 正常。 */
    OK,

    /** 能用，但有值得看一眼的地方（未 root、存储将满、媒体库未授权…）。 */
    WARN,

    /** 这一项坏了 / 没通。 */
    FAIL,

    /** 陈述事实，没有通过与否（入口类条目、可选组件缺失）。 */
    INFO,
}

/**
 * 一个**写入动作**（2026-10-14 补）。
 *
 * ## 为什么它挂在 [DiagnosticResult] 上，而不是像 [DiagnosticItem.action] 那样挂在描述符上
 * [DiagnosticItem] 的 KDoc 里否决过"把跳转入口放进结果"，理由是**跳转是静态的**
 * （"去日志页"与这次跑出什么无关）。写入动作恰好相反：它**只在该状态下才成立** ——
 * "core 缺权限"时才有「补全权限」。做成描述符上的常量字段，就必然有某个状态拿到一个错的
 * 按钮（权限都补齐了还在喊"补全"，点下去什么也不做）。所以它属于结果：
 * **结论本身在告诉你现在能做什么**。
 *
 * ## 硬约束：写动作一律二次确认
 * `confirmTitle` / `confirmBody` 是**必填**的（不是可空），[DiagnosticSection] 用
 * `UfiConfirmDialog` 渲染确认框，`destructive = true` 时走警示配色。
 * 文案必须说清三件事：**改什么、能不能撤销、失败了会怎样**。
 *
 * `run()` 返回一句给人看的结果文案（成功与失败都要有话说）——
 * 只弹一个"完成"等于把"到底成没成"推给用户去猜。异常由 [DiagnosticSection] 统一兜住。
 *
 * @param destructive 会改变设备状态（`pm grant` / `appops set`）。走警示配色与更强的确认框。
 */
data class DiagWriteAction(
    val label: String,
    val confirmTitle: String,
    val confirmBody: String,
    val destructive: Boolean = false,
    val run: suspend () -> String,
)

/**
 * 一项检查的结果。
 *
 * @param summary **一行结论**。折叠时只显示这一行 —— 所以它必须自洽到"不展开也读得懂"，
 *   不能写成"见下方"。
 * @param rows 展开后的逐项明细（标签 → 值），按顺序渲染。
 * @param raw 原始输出（core 的 stdout / JSON 原文）。**可折叠 + 可复制** ——
 *   排障现场要的是"能原样贴进文档做逐字比对"的东西，摘要永远代替不了它。
 * @param write 针对**这个结论**的写入动作；null = 这次没有可做的事（见 [DiagWriteAction]）。
 */
data class DiagnosticResult(
    val status: DiagStatus,
    val summary: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val raw: String? = null,
    val write: DiagWriteAction? = null,
)

/**
 * 需要用户先填一个参数才能跑的项（ping / DNS / TCP / traceroute 的目标）。
 *
 * ## 为什么参数是描述符的一个成员，而不是给 `run()` 加形参
 * [DiagnosticItem.run] 的签名保持"无参 → 结果"这一条直筒子形状（与资源中心的
 * `fetch: suspend (ResourceApi) -> ResourceSnapshot` 同一取向）：页面的运行逻辑因此对
 * "这一项要不要参数"完全无感 —— 它只看到 `input != null` 就多画一个输入框。
 *
 * ## 值从哪来
 * 页面的输入框把用户敲的字写回 `Map<itemId, String>`，再由
 * [DiagnosticRegistry.all] 把它塞进对应项的这个字段。也就是说 [value] 是**本次渲染的快照**，
 * 描述符自己不持有可变状态（与"描述符是冻结数据"这条纪律一致）。
 *
 * @param label 输入框上方的标签（"目标主机" / "host:port"）。
 * @param placeholder 提示文本；[value] 为空时显示。
 * @param value 当前值。空 = 还没填 —— 页面据此**禁用**运行按钮（而不是跑一次再报错）。
 */
data class DiagInput(
    val label: String,
    val placeholder: String,
    val value: String = "",
)

/**
 * 结果区旁边的**跳转入口**（2026-10-14 补）。
 *
 * 描述的是"这一项旁边还有一个去处"，不是"这一项不是检查" ——
 * 它**与 [DiagnosticItem.run] 可以共存**，理由见 [DiagnosticItem.action]。
 *
 * @param label 按钮文案（"去日志页"）。**必须自己说清去哪**：只写"查看"等于没说，
 *   而用户在这个位置要判断的正是"点下去会离开这一页吗、去的是哪儿"。
 * @param route 目标路由，取 `Routes.*` 常量（本仓不允许在页面里散落路由字面量）。
 */
data class DiagAction(val label: String, val route: String)

/**
 * 一项检查的**归属**（2026-10-14 补）：这一项**该不该在另一端（web）也有**。
 *
 * ## 它解决什么问题
 * 两端各维护一份登记表（本文件所在的包 / `web/src/views/diagnostics/diagnosticRegistry.ts`），
 * 而"哪些检查其实是**同一件事**"此前只存在于人脑里 —— 加一项 core 类检查时，
 * **没有任何东西会提醒你"另一端也要加"**，于是同一台设备在两个客户端上看到的结论不一样，
 * 而没人发现。这个枚举就是那条提醒：它是 [DiagnosticItem.scope]，**必填**（没有默认值）。
 *
 * ## 两档
 * · [CORE]：查的是设备 / core 自己的东西 —— **两端都必须有**。id 命名可以不同
 *   （web 的 `storage` 就是本端的 `env.monitorstorage`），映射由
 *   `scripts/check-diagnostics-scope.mjs` 的跨端映射表登记并核对。
 * · [CLIENT]：只在本端才有意义的检查（本机通知授权）—— 另一端不该有对应项。
 *
 * ## 为什么是枚举而不是 Boolean
 * 页面上要显示它（角标 `core` / `本端`），而 Boolean 会把"文案"推到渲染处按真假分支写；
 * 枚举自带 [label]，与 [DiagStatus] / [RunMode] 同一手法。
 *
 * @param label 角标文案。**两档刻意同色**（见 [DiagnosticCard] 的注释）：颜色在本页已经
 *   全部给了状态，语义只由文字承担。
 */
enum class DiagnosticScope(val label: String) {
    /** 查设备 / core 自己的东西：**两端都必须有**。 */
    CORE("core"),

    /** 查本端才有的东西：另一端没有这个概念。 */
    CLIENT("本端"),
}

/**
 * 一项检查 —— **诊断中心的唯一扩展点**。
 *
 * 新增一项检查 = 在 [DiagnosticRegistry] 里加一个本接口的实例。
 *
 * ## 为什么 [input] 与 [action] 是带默认值的成员
 * 五个必填成员描述的是"一次检查"；这两条描述的是**检查之外的两件事**，各有默认值
 * （null）所以不污染常规项：
 *  · [input]：需要参数才能跑的检查（NETWORK 那四条）；
 *  · [action]：**跳转入口**（调试日志去日志页）。做成描述符字段而不是在页面里硬编码一行，
 *    是为了让"这一页上有哪些入口"同样由登记表说了算（否则 `DiagnosticSection` 里迟早会长出第二个 `if`）。
 *
 * ## [action] 为什么不是原来的"跳转类条目"（2026-10-14 改）
 * 原先这里是一个 `route: String?`，语义是"**跳转类条目**：非空 ⇒ 卡片不画运行按钮、也不跑"。
 * 那条路在「调试日志」这一项上走不通：它既该能跳去日志页（看正文、开关），
 * 也该能读一下 `/api/debug-logs/files` 回答"日志到底有没有在落盘" —— 而后者是排障现场
 * 最常见的一类假故障（"我明明开了日志，怎么一个文件都没有"）。
 * "跳转 ⇒ 不跑"把这两件事变成互斥的，于是那张卡只能写一句"点击进入"，一点信息都不给。
 * 所以改成 [action]：**跑不跑由 [mode] 说了算，入口只决定"要不要多画一颗跳转按钮"**，
 * 两个维度各自独立。纯入口类条目现在的表达是"`mode = AUTO` + `run()` 返回一条 INFO 摘要 +
 * [action]" —— 比原来那句"点击进入"信息量更大（它顺手把目标页的状态读了一眼）。
 */
interface DiagnosticItem {
    /** 稳定 id：结果缓存、输入框状态的键，也是日志里的定位串。 */
    val id: String

    /**
     * 归属：**这一项另一端（web）有没有**。见 [DiagnosticScope]。
     *
     * 与 [id] 一样是**必填**的（没有默认值）：新增一项检查时必须显式回答"这是 core 类
     * 还是本端专有"，漏填编译就过不去 —— 跨端齐全这件事因此不靠记性。
     * 写法上紧跟在 [id] 后面：两者一起回答"这一项是谁、该在哪一端"。
     */
    val scope: DiagnosticScope

    /** 卡片标题。 */
    val name: String

    /** 一句话：这个检查回答什么问题（展开后显示）。 */
    val desc: String

    val group: DiagnosticGroup

    val mode: RunMode

    /** 需要用户先填参数才跑得起来；null = 直接跑。 */
    val input: DiagInput? get() = null

    /** 结果区旁边的跳转入口；null = 这一项没有入口。与 [run] 可以共存（见 [DiagAction]）。 */
    val action: DiagAction? get() = null

    /** 跑一次。异常由调用方兜成 [DiagStatus.FAIL]（理由见文件头）。 */
    suspend fun run(): DiagnosticResult
}

/**
 * 描述符能拿到的全部外部能力。
 *
 * 收成一个参数（而不是让每条描述符各自去 `RetrofitClient` / `AppHttpClient` 取）：
 * 描述符只该描述"这项检查怎么读"，取数细节（baseUrl 从哪来、鉴权怎么加、原文怎么解析）
 * 留在这一层。形状与 `feature-resources` 的 `ResourceApi` 同源。
 *
 * @param service 强类型 Retrofit 接口（本批**不新增任何注解**：诊断中心用到的端点
 *   `getDeviceCapabilities` / `getRootCheck` / `getShellRoot` / `getSelinuxStatus` /
 *   `getMagiskStatus` / `getStorageInfo` / `getMediaStatus` / `getServiceStatus` /
 *   `getCoreCrashReport` / `getQosStatus` / `getDiagnose` 全都已存在）。
 * @param prefs 拼裸请求 URL 用（`baseUrl` 已带尾部斜杠）。
 */
class DiagnosticApi(val service: UfiAxisApi, val prefs: AppPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 裸 GET 一个 JSON 对象。
     *
     * ## 为什么需要它
     * `/api/diagnose` 的**原文**才是排障要的东西：强类型 [com.ufi_axis.data.model.DiagnoseResponse]
     * 会把 core 新加的键静默丢掉（`ignoreUnknownKeys`），而"core 这次到底回了哪些字段"
     * 恰恰是设备适配对账时的判据。走 [AppHttpClient]（已装好 Bearer + 设备签名拦截器，
     * 与 Retrofit 同一套凭据）读原文，再由调用方按需 `decodeFromJsonElement` 取强类型字段
     * —— **一次请求同时拿到两者**，不必打两遍。
     */
    suspend fun rawGetJson(path: String): JsonObject? = rawRequest("GET", path, null)

    /**
     * 裸 POST 一个 JSON 对象（`/api/diagnostic/…` 专用）。
     *
     * ## 为什么这几条不走 Retrofit
     * `UfiAxisApi` 里没有 `api/diagnostic/…` 的注解，而本批**只允许新增 sponsors 一条**
     * （见 `UfiAxisApi` 的赞助分组注释）。更要紧的是这四个端点回的是
     * `{ok, tool, exit_code, output}` —— 其中 `output` 是 **shell stdout 原文**
     * （含换行、`*`、路径里的反斜杠），强类型 + `Json` 解析反而多一层转义风险；
     * 这里要的就是"原样把 output 交给用户复制"。
     */
    suspend fun rawPostJson(path: String, body: JsonObject): JsonObject? =
        rawRequest("POST", path, body.toString())

    private suspend fun rawRequest(method: String, path: String, body: String?): JsonObject? =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(prefs.baseUrl + path.removePrefix("/"))
            when (method) {
                "POST" -> builder.post(
                    (body ?: "{}").toRequestBody("application/json".toMediaType())
                )
                else -> builder.get()
            }
            runCatching {
                AppHttpClient.instance.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val text = resp.body?.string() ?: return@use null
                    json.parseToJsonElement(text) as? JsonObject
                }
            }.getOrNull()
        }
}

/** 缩进后的 JSON 原文（`raw` 字段用）。放文件级：它无状态，每次重组新建一个 Json 实例纯属浪费。 */
internal val prettyJson: Json = Json { prettyPrint = true }

/** [JsonElement] → 缩进文本；不是合法元素时退回 `toString()`，绝不吞掉。 */
internal fun prettyOf(element: JsonElement?): String? =
    element?.let { runCatching { prettyJson.encodeToString(JsonElement.serializer(), it) }.getOrNull() }
