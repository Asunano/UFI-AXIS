// [F24] STABLE-UI-API 不适用：本模块是 2026-10-14 新建的资源中心，暂无外部消费者。
package com.ufi_axis.ui.resources

import androidx.compose.ui.graphics.vector.ImageVector
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.AppHttpClient
import com.ufi_axis.util.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.File

/**
 * 资源中心 —— **描述符层**（2026-10-14）。
 *
 * ## 这个文件解决什么问题
 * 可下载资源此前散在三处，每加一类就要动 UI：
 *  · Web 前端 ZIP —— `ServerConfigScreen` 的一颗行卡 + 一个 200 行的管理弹窗；
 *  · frpc / cloudflared —— `TunnelSettingsComponentsScreen` 整页。
 * 两者的**数据形状、状态机、按钮语义**几乎同构（查状态 → 装/卸 → 轮询进度），
 * 差异只在"取哪个端点、状态名多一档、上传上限是多少"。把差异写进**数据**而不是写进
 * `if (id == "frpc")`，新增资源就只剩"加一条描述符"。
 *
 * ## 分层（每层只做一件事，改一层不牵动别层）
 *  · 本文件 —— **描述**：一个资源长什么样、能做什么、状态怎么翻译成人话；
 *  · [ResourceRegistry] —— **登记**：本次落地的三条描述符（webZip / frpc / cloudflared）；
 *  · [ResourceTaskController] —— **执行**：下发 + 轮询 + 终态；
 *  · [ResourceCard] / [ResourcesScreen] —— **渲染**：遍历登记表，逐条画卡。
 *
 * ## 硬约束（用户点名）
 * [ResourceCard] 与 [ResourcesScreen] 里**不允许**出现 `if (id == "frpc")` 这类分支。
 * 所有差异只走下面这些字段与可选回调。本文件的 [ResourceKind] 是**唯一的**形态开关，
 * 而它表达的也是"一张卡里列一条还是列多条"这种通用差别，不是某个资源的身份。
 *
 * ## 进度为什么不是一个 percentage
 * 三类资源的进度语义不同，合并成一个数会丢信息：
 *  · 组件（frpc / cloudflared）：core 报的是**真百分比**（按 Content-Length 算）；
 *  · Web ZIP 的 core 侧：`WebUpdateManager` 只走状态机，progress 实质只有 0 / 100；
 *  · 两者之上还各叠一层**客户端 → core 的上传百分比**（本机读文件 + 写 socket）。
 * 所以进度保留 `(state, percent, message)` **三元组**，由 [StateMeta] 解释它此刻是哪一层、
 * 该画进度条还是该画对勾/错误色。
 */

/** 资源形态。**唯一的形态开关**，且表达的是"一条还是多条"，不是资源身份。 */
enum class ResourceKind {
    /** 全局唯一一份（Web 前端 ZIP）：安装 = 覆盖，没有"条目列表"。 */
    SINGLETON,

    /** 同类多份（组件：frpc / cloudflared）：一张卡里列出若干条目，逐条安装 / 卸载。 */
    COLLECTION
}

/** 状态色调：只决定颜色与"要不要画进度条"，不决定文案（文案在 [StateMeta.label]）。 */
enum class StateTone {
    /** 空闲 / 无任务：不画进度条。 */
    NEUTRAL,

    /** 正在跑：画进度条。 */
    PROGRESS,

    /** 成功终态：对勾色，不画进度条。 */
    SUCCESS,

    /** 失败终态：错误色，不画进度条。 */
    FAILURE
}

/**
 * 一个 core 状态串的**解释**：中文标签 + 色调。
 *
 * 为什么不直接在 UI 里 `when (state)`：那样每加一类资源就要往 UI 的 when 里塞新状态名
 * （web 有 `need_push`、组件有 `extracting`），正是要消灭的那种分支。改由描述符的
 * [ResourceDescriptor.stateMeta] 提供，缺省回落 [DEFAULT_STATE_META]。
 */
data class StateMeta(val label: String, val tone: StateTone)

/**
 * 缺省状态表：**两套状态机的并集**（组件 `ComponentManager.State` + Web `WebUpdateManager.State`）。
 * 两套的状态名都是小写枚举名，重合的档（idle/downloading/verifying/installing/done/failed）
 * 语义一致，各自独有的两档（`extracting` / `need_push`）在这里补齐。
 *
 * 认不出的值**原样显示**（[StateTone.NEUTRAL]）：core 加了新态时宁可露出英文，
 * 也不要静默显示成"空闲"误导用户 —— 这条口径抄自 `ServerConfigScreen.webUpdateStateLabel`。
 */
val DEFAULT_STATE_META: Map<String, StateMeta> = mapOf(
    "idle" to StateMeta("空闲", StateTone.NEUTRAL),
    "checking" to StateMeta("检查中", StateTone.PROGRESS),
    "downloading" to StateMeta("下载中", StateTone.PROGRESS),
    "verifying" to StateMeta("校验中", StateTone.PROGRESS),
    // 组件独有：tar.gz 要先把 entry 解出来再装（Web ZIP 在 core 侧没有这一档）
    "extracting" to StateMeta("解包中", StateTone.PROGRESS),
    "installing" to StateMeta("安装中", StateTone.PROGRESS),
    "done" to StateMeta("已完成", StateTone.SUCCESS),
    "failed" to StateMeta("失败", StateTone.FAILURE),
    // Web 独有：更新源不可用 → 引导走本地上传兜底
    "need_push" to StateMeta("需手动上传", StateTone.FAILURE)
)

/** 正在跑的档（画进度条 + 禁止并发操作）。与 core 两侧的 BUSY 集合并集一致。 */
val ACTIVE_STATES: Set<String> = setOf("checking", "downloading", "verifying", "extracting", "installing")

/** 落终态的档：到了这两个就不该再轮询。 */
val TERMINAL_STATES: Set<String> = setOf("done", "failed", "need_push")

/** 描述符的翻译函数：认不出返回 null，由调用方回落 [DEFAULT_STATE_META]。 */
fun ResourceDescriptor.stateMetaOf(state: String): StateMeta =
    stateMeta?.invoke(state) ?: DEFAULT_STATE_META[state] ?: StateMeta(state, StateTone.NEUTRAL)

/**
 * core 报的任务状态 —— `(state, percent, message)` **三元组**原样保留。
 *
 * 不把 percent 单独提出来做成"一个进度"：组件的 percent 是真百分比，Web 的 percent
 * 实质只有 0/100，而上传阶段那个百分比是**客户端算的**、与 core 无关。三者合并成一个数
 * 之后，"进度卡在 100% 但还在装"与"进度 100% 且已完成"在界面上就分不出来了。
 */
data class ResourceTaskStatus(
    val state: String = "idle",
    val percent: Int = 0,
    val message: String = "",
) {
    /** 正在跑（core 自报的状态在 [ACTIVE_STATES] 里）。 */
    val active: Boolean get() = state in ACTIVE_STATES

    /** 已落终态（[TERMINAL_STATES]）。 */
    val terminal: Boolean get() = state in TERMINAL_STATES
}

/**
 * 资源里的一条可操作条目（[ResourceKind.COLLECTION] 用）。
 *
 * [ResourceKind.SINGLETON] 也走这个形状（0 或 1 条），这样卡片渲染只有一条代码路径 ——
 * "单例"与"集合"的区别只体现在描述符怎么填 [ResourceSnapshot.items]。
 *
 * ## [installed] / [updateAvailable] 在单例下的含义由描述符自己定
 * 卡片只读这两个布尔、不关心它们具体指什么（这正是"没有 id 分支"的代价与收益）：
 *  · `installed` = "自定义资源正在生效"（Web 的 override）→ 决定「恢复内置」是否可用；
 *  · `updateAvailable` = "存在可切换的另一版"（Web 的回滚备份）→ 决定「回滚」是否可用。
 * 组件那种多份场景下它们就是字面意思（已装 / 有新版）。
 */
data class ResourceItem(
    val id: String,
    val title: String,
    val detail: String,
    val installed: Boolean = false,
    val updateAvailable: Boolean = false,
    /**
     * 本条**现在**可做的动作文案（`下载安装` / `更新` / `在线更新`…）；null = 本条没有可做的动作，
     * 卡片就不画那颗按钮。
     *
     * 为什么把"按钮存不存在、叫什么"从卡片挪到这里：那是**领域判断**，不是布局。
     * 组件要 `有可用直链 && (未安装 || 有新版)` 才显示，Web 单例则任何时候都能"在线更新" ——
     * 这个差异写在描述符里，卡片就只需要 `actionLabel != null` 一个条件，不必认识
     * `available` / `installed` / `updateAvailable` 的组合含义（那正是"分支长在 UI 里"的开端）。
     */
    val actionLabel: String? = null,
    /** 已安装才谈得上卸载。 */
    val uninstallable: Boolean = false,
)

/**
 * 一次读取拿到的资源快照。**只装数据，不装渲染决策** ——
 * 渲染要的"额外只读信息行"由描述符的 [ResourceDescriptor.extraRows] 从它算出来。
 */
data class ResourceSnapshot(
    val items: List<ResourceItem> = emptyList(),
    val task: ResourceTaskStatus = ResourceTaskStatus(),
    /** core 正在后台拉远端清单（组件侧 `manifest_loading`）：界面要显示"正在获取最新版本…"。 */
    val manifestLoading: Boolean = false,
    /** 清单拉取失败原因（组件侧 `manifest_error`）；空 = 没失败。 */
    val manifestError: String = "",
    /**
     * 本地上传的体积上限，**由端点下发**（`/api/web/status` 与 `GET /api/components` 的
     * `max_upload_bytes`），不在 app 侧写死。
     *
     * 为什么必须读而不是写死：core 侧的常量改一次（`WebUpdateRoutes.MAX_ZIP_UPLOAD` /
     * `ComponentRoutes.MAX_UPLOAD_BYTES`），写死的 app 就会"选文件时放行、上传后被 413"，
     * 而 413 要等 body 全推完才拿得到 —— 几十 MB 上行白烧。
     */
    val uploadLimitBytes: Long? = null,
    /**
     * 端点给的**其他只读字段**，键用稳定的英文名（`version` / `bundled_version` / `dir_bytes`…）。
     *
     * 为什么不让 `fetch` 直接产出中文行：那样"取数"和"文案"就焊在一个 lambda 里，
     * 而描述符的 [ResourceDescriptor.extraRows] 才是负责文案的那一层。分开之后，
     * 同一份数据想换个说法（或换语言）不用碰取数逻辑。
     */
    val fields: Map<String, String> = emptyMap(),
    /** 本次读取失败的原因；null = 读成功。 */
    val error: String? = null,
)

/**
 * 描述符能拿到的全部外部能力。
 *
 * 收成一个参数而不是让每个 lambda 各自去 `RetrofitClient` / `AppHttpClient` 取：
 * 描述符只该描述"这个资源怎么读写"，取数细节（baseUrl 从哪来、鉴权怎么加）留在这一层。
 *
 * @param service 强类型 Retrofit 接口（既有 `UfiAxisApi` 里 components / web 两组注解，**不新增**）。
 * @param prefs 拼裸请求 URL 用（`baseUrl` 已带尾部斜杠）。
 */
class ResourceApi(val service: UfiAxisApi, val prefs: AppPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 裸 GET 一个 JSON 对象。
     *
     * ## 为什么需要它（而不是全走 Retrofit）
     * `GET /api/web/status` 的响应里 `max_upload_bytes` 是**路由层拼上去的**（见
     * `WebUpdateRoutes.get("/status")`），而 app 侧的 `getWebUpdateStatus()` 返回的是强类型
     * [com.ufi_axis.data.model.WebUpdateStatusResponse]，那个字段在反序列化时被丢掉了。
     * 本模块**没有** `:app:data` 的写权限（描述符层的契约要求"不新增 Retrofit 注解"），
     * 所以走 [AppHttpClient]（已装好 Bearer + 设备签名拦截器，与 Retrofit 同一套凭据）读原文。
     * 组件侧不需要它：`listComponents()` 本来就返回 `JsonElement`。
     */
    suspend fun rawGetJson(path: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(prefs.baseUrl + path.removePrefix("/"))
            .get()
            .build()
        runCatching {
            AppHttpClient.instance.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                json.parseToJsonElement(body) as? JsonObject
            }
        }.getOrNull()
    }
}

/**
 * 一条资源描述符 —— **资源中心的唯一扩展点**。
 *
 * 新增一类资源 = 在 [ResourceRegistry] 里加一个本类型的实例。不需要新屏幕、不需要新分支：
 * [ResourcesScreen] 遍历登记表，[ResourceCard] 只读这些字段。
 *
 * ## 字段分两类
 *  · **必填的"是什么"**：[id] / [name] / [desc] / [icon] / [kind] / [fetch]；
 *  · **可选的"能做什么"**：其余全部。为 null 的能力**不渲染对应按钮**（而不是渲染一个
 *    点了报错的按钮）—— 本仓禁止假开关，这里同理。
 *
 * ## 为什么回调是 `suspend (ResourceApi, …) -> String?` 而不是抛出
 * 返回 `null` = 成功、返回字符串 = 失败原因（**已翻成人话**）。这与 core 的路由口径一致
 * （`ComponentManager.install` 也是"返回失败原因 / null 表示已受理"），且让 runner 里
 * 只有一处 try/catch 兜住传输层异常。抄的是 `ApiOperationController.Spec.submit` 的形状。
 */
data class ResourceDescriptor(
    val id: String,
    val name: String,
    val desc: String,
    val icon: ImageVector,
    val kind: ResourceKind,

    /**
     * SAF 选择器的 MIME 白名单。**空 = 本资源在 app 侧不支持本地上传**，卡片不画上传按钮。
     *
     * 组件（frpc / cloudflared）就是空的：core 的 `POST /api/components/{id}/upload` 存在，
     * 但 app 侧要走 SAF + 几十 MB 裸流上传，收益不足（该端点的既有结论写在 `UfiAxisApi` 的
     * 组件分组注释里），离线兜底留给 Web 面板。
     */
    val accept: List<String> = emptyList(),

    /** 读一次当前状态。**唯一的必填能力** —— 卡片的全部内容都从它的返回值渲染。 */
    val fetch: suspend (ResourceApi) -> ResourceSnapshot,

    /** 「检查更新」：让 core 重新拉远端清单（组件）/ 触发自更新（Web）。 */
    val refresh: (suspend (ResourceApi) -> Unit)? = null,

    /** 安装某一条目。**任务型**：core 立即返回，进度靠轮询 [fetch]。 */
    val install: (suspend (ResourceApi, String) -> String?)? = null,

    /**
     * [install] 下发之后怎么盯 core 的状态机（见 [PollMode]）。
     *
     * 放在**描述符**上而不是操作上：同一句"安装"在两类资源里的服务端行为不同 ——
     * 组件的 `POST /api/components/{id}/install` 返回前就把进度置成 DOWNLOADING（必然能等到），
     * 而 Web 的 `POST /api/web/check` 是异步的、且"已是最新版本"时压根不会进入进行中。
     * 知道这件事的是描述符，不是点按钮的人。
     */
    val installPoll: PollMode = PollMode.TO_TERMINAL,

    /**
     * 本地上传安装。第三个参数是**客户端 → core 的上传百分比回调**（0..100）。
     *
     * 与 core 自报的 percent 是**两层不同的进度**：这一层在请求体推完时就到 100，
     * 而 core 可能才刚开始校验。所以 UI 上这两层由 [StateMeta] 分别标注（见文件头）。
     */
    val upload: (suspend (ResourceApi, File, (Int) -> Unit) -> String?)? = null,

    /** 卸载某一条目（core 侧同步完成，请求返回即终态）。 */
    val uninstall: (suspend (ResourceApi, String) -> String?)? = null,

    /** 回滚到上一版（一次性：成功后备份就没了）。 */
    val rollback: (suspend (ResourceApi) -> String?)? = null,

    /** 清除自定义资源、恢复随包分发的版本。 */
    val clear: (suspend (ResourceApi) -> String?)? = null,

    /** 覆盖 [ResourceSnapshot.uploadLimitBytes] 的解释（缺省直接用快照里的值）。 */
    val uploadLimit: ((ResourceSnapshot) -> Long?)? = null,

    /** 覆盖 [DEFAULT_STATE_META] 的翻译（缺省 null = 用缺省表）。 */
    val stateMeta: ((String) -> StateMeta?)? = null,

    /** 额外的只读信息行（标签 → 值），画在卡片标题下方。 */
    val extraRows: ((ResourceSnapshot) -> List<Pair<String, String>>)? = null,

    /** 任一任务落终态时回调（[ok] = 是否成功）。 */
    val onFinished: ((Boolean) -> Unit)? = null,

    /** 安装/上传**成功**后回调（"有新东西落地了"）。 */
    val onInstalled: (() -> Unit)? = null,
) {
    /** 本资源的上传上限（字节）；null = 端点没下发（界面就不做前置拦截，交给 core 判）。 */
    fun uploadLimitOf(snapshot: ResourceSnapshot?): Long? =
        uploadLimit?.invoke(snapshot ?: ResourceSnapshot()) ?: snapshot?.uploadLimitBytes

    /** 是否支持本地上传（[accept] 非空**且** [upload] 非 null，两者缺一都不画按钮）。 */
    val supportsUpload: Boolean get() = accept.isNotEmpty() && upload != null
}
