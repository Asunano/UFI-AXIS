package com.ufi_axis.ui.resources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 资源中心 —— **任务型 runner**（2026-10-14）。
 *
 * 职责：`submit(op)` → 下发 → **轮询 `descriptor.fetch` 直到服务端自报终态** → 落结果。
 *
 * ## 与 `ApiOperationController` 的区别（为什么不复用）
 * `app/viewmodel/.../module/ApiOperationController.kt` 是**两段式确认器**：
 *   ① `submit()` 下发一个写请求；② `verify()` **回读目标值**，直到"目标值已生效"，
 *   轮询节奏固定为 `NetworkMode.SwitchProbe`（600ms 首延 + 13 次快慢档，总共十来秒）。
 * 它服务的是「设置类写入」（切网络模式、改配置），成功判据是**客户端自己算出来的一个布尔**。
 *
 * 本 runner 服务的是**服务端自报进度的异步任务**，两者在四个点上都对不上：
 *  1. **进度来源**：组件安装的 `percent` 由 core 按 Content-Length 算并下发（下载 37%），
 *     `verify()` 那种"是/否"形状装不下它 —— 只能显示一个转圈，用户看不到还要多久。
 *  2. **状态维度**：core 有 7 档状态（含 `extracting` / `need_push`），终态是**服务端说了算**
 *     的状态机，而不是客户端的目标值比对。
 *  3. **时长**：几十 MB 的下载在弱网上远超 SwitchProbe 那套十来秒的预算；用它的节奏会在
 *     任务真正完成前就报"未在预期时间内完成"。所以本 runner 用自己的节奏常量
 *     （[POLL_INTERVAL_MS] / [MAX_POLL_TICKS]，约 3 分钟），并在超时时明确写"core 可能仍在后台继续"。
 *  4. **生命周期**：见下。
 *
 * 共用的只是 `Kind` 的**形状**（IDLE/RUNNING/SUCCESS/FAILED），所以这里照抄了它的枚举语义，
 * 而不是去改它的状态机 —— 那会把两种语义焊死在一个类里。
 *
 * ## 为什么 scope 是进程级而不是 `rememberCoroutineScope()`
 * 「已开始下载，可离开本页」是原组件页（`TunnelSettingsComponentsScreen` 的 toast 文案）
 * 就给出的承诺，而它当时只做到"core 继续跑"：**轮询协程随页面销毁而取消**，
 * 回来时靠重新拉一次状态补上。本 runner 把轮询也挪到进程级 scope（[shared]），
 * 于是"离开本页"不再打断任务跟踪；50MB 的 ZIP 上传同理 —— 它是**在飞请求**，
 * 用页面 scope 的话返回键一按就被取消，而 core 那边已经开始写盘了。
 *
 * 这与 `MediaDownloadQueue`（`AppScreens.kt` 里注明的"进程内单例"）是同一条理由：
 * 任务的生命周期比任何一个页面都长。
 *
 * ## 页面侧仍然要有 lifecycle 门控
 * 进程级 scope 只保证"任务不被页面销毁打断"，**不保证"app 在后台时还在打网络"**。
 * 所以 [ResourcesScreen] 里那条**清单懒加载**轮询仍然照
 * `TunnelSettingsComponentsScreen.kt:78-90`（DisposableEffect 观察 ON_RESUME）+
 * `:98-110`（轮询循环以 `lifecycleResumed` 为 key）的既有正确形态写：退到后台就停，
 * 回到前台自己接着读。两者分工不同，缺一不可。
 */
class ResourceTaskController(private val scope: CoroutineScope) {

    /**
     * 一次任务的对外状态。
     *
     * [percent] 的**含义随阶段变化**，由调用方结合 [message] 与 `StateMeta` 解释：
     *  · 下发阶段（上传）：客户端读文件 + 写 socket 的百分比；
     *  · 轮询阶段（安装）：core 自报的百分比。
     * 这不是含糊，而是刻意的：把两层压成一个数就再也说不清"为什么 100% 了还在装"。
     */
    data class Task(
        val resourceId: String = "",
        val label: String = "",
        val phase: Phase = Phase.IDLE,
        val percent: Int = 0,
        val message: String = "",
        /**
         * core 自报的状态串（`downloading` / `extracting` / `done`…）；空串 = 这一步没有 core 状态
         * （下发阶段，例如本地上传时）。
         *
         * **必须留着它**：卡片靠它经描述符的 `stateMeta` 把"这一格进度说的是哪一层"翻译成人话，
         * 只留 percent 就再也分不清"客户端上传 37%"与"core 下载 37%"。
         */
        val coreState: String = "",
    ) {
        enum class Phase { IDLE, RUNNING, SUCCESS, FAILED }

        val running: Boolean get() = phase == Phase.RUNNING
    }

    /** 按资源 id 分槽：两个资源（如 Web 与组件）的任务互不覆盖。 */
    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks.asStateFlow()

    /** 每个资源一个在飞 Job：同一资源重复点击 = 忽略（core 侧本来也只允许一个安装任务）。 */
    private val jobs = mutableMapOf<String, Job>()

    /**
     * 下发一次操作。
     *
     * @param api 描述符要的取数能力（每次调用现建，持的是 `AppPreferences`，很轻）。
     */
    fun submit(api: ResourceApi, descriptor: ResourceDescriptor, op: ResourceOp) {
        if (_tasks.value[descriptor.id]?.running == true) return
        jobs[descriptor.id]?.cancel()
        jobs[descriptor.id] = scope.launch {
            put(Task(descriptor.id, op.label, Task.Phase.RUNNING, 0, "${op.label}中…"))
            val failure = try {
                runOp(api, descriptor, op)
            } catch (e: CancellationException) {
                // 取消不是失败：进程级 scope 只会被宿主进程结束取消，原样抛出即可。
                throw e
            } catch (e: Exception) {
                e.message ?: "${op.label}失败"
            }

            val ok = failure == null
            if (ok) descriptor.onInstalled?.invoke()
            // 无论成败都回读一次：失败时 core 的状态可能已经变了（例如装到一半失败），
            // 界面必须反映真实状态而不是我们以为的状态。
            descriptor.onFinished?.invoke(ok)
            val last = _tasks.value[descriptor.id]
            put(
                Task(
                    resourceId = descriptor.id,
                    label = op.label,
                    phase = if (ok) Task.Phase.SUCCESS else Task.Phase.FAILED,
                    percent = last?.percent ?: 0,
                    message = failure ?: "${op.label}完成",
                    // 终态也带上 core 状态（`done` / `failed`）：卡片据此上对勾色或错误色，
                    // 而不是所有成功都长一样 —— "已完成"与"需手动上传"必须能分开。
                    coreState = last?.coreState.orEmpty(),
                )
            )
            if (ok) {
                // 成功态短暂停留后自行消失（与 ApiOperationController 的 300ms 对勾同一条理由：
                // 它是"刚刚发生了什么"的提示，不是需要用户处理的常驻状态）。
                delay(SUCCESS_LINGER_MS)
                if (_tasks.value[descriptor.id]?.phase == Task.Phase.SUCCESS) {
                    _tasks.value = _tasks.value - descriptor.id
                }
            }
        }
    }

    /** 用户点掉失败提示。 */
    fun dismiss(resourceId: String) {
        jobs[resourceId]?.cancel()
        _tasks.value = _tasks.value - resourceId
    }

    /**
     * 在**任务 scope** 上跑一段准备动作（当前唯一用途：把 SAF 选中的文件拷进 cacheDir）。
     *
     * 为什么不放在调用点的 `rememberCoroutineScope()` 里：那是**组合**的 scope，
     * 用户按返回键就取消。50MB 的文件拷到一半被取消，后面的上传根本不会发生 ——
     * 而界面已经承诺了"可离开本页"。准备与上传必须活在同一条时间线上。
     */
    fun launchOnTaskScope(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    // ── 内部 ──

    /** 下发 + （任务型）轮询到终态。返回失败原因，null = 成功。 */
    private suspend fun runOp(
        api: ResourceApi,
        descriptor: ResourceDescriptor,
        op: ResourceOp
    ): String? {
        val failure = when (op) {
            is ResourceOp.Install -> descriptor.install?.invoke(api, op.itemId)
                ?: return "本资源不支持「${op.label}」"

            is ResourceOp.Refresh -> {
                descriptor.refresh?.invoke(api)
                    ?: return "本资源不支持「${op.label}」"
                null
            }

            is ResourceOp.Upload -> try {
                descriptor.upload?.invoke(api, op.file) { pct ->
                    put(
                        Task(
                            descriptor.id, op.label, Task.Phase.RUNNING, pct,
                            if (pct >= 100) "已推送，等待设备端处理…" else "正在上传（$pct%）"
                        )
                    )
                } ?: return "本资源不支持「${op.label}」"
            } finally {
                // 临时文件归 runner 管：它是 op 的一部分，op 结束（无论成败）就该消失，
                // 否则 cacheDir 里会留下一个几十 MB 的 ZIP 直到系统自己清。
                op.file.delete()
            }

            is ResourceOp.Uninstall -> descriptor.uninstall?.invoke(api, op.itemId)
                ?: return "本资源不支持「${op.label}」"

            ResourceOp.Rollback -> descriptor.rollback?.invoke(api)
                ?: return "本资源不支持「${op.label}」"

            ResourceOp.Clear -> descriptor.clear?.invoke(api)
                ?: return "本资源不支持「${op.label}」"
        }
        if (failure != null) return failure
        // 轮询方式由**描述符**回答（见 ResourceDescriptor.installPoll）：同一句"安装"在组件与
        // Web 上的服务端行为不同，知道这件事的是资源本身，不是点按钮的人。
        val mode = when (op) {
            is ResourceOp.Install -> descriptor.installPoll
            ResourceOp.Refresh -> PollMode.AWAIT_START
            else -> PollMode.NONE
        }
        return when (mode) {
            PollMode.NONE -> null
            PollMode.TO_TERMINAL -> pollUntilTerminal(api, descriptor, op, awaitStart = false)
            PollMode.AWAIT_START -> pollUntilTerminal(api, descriptor, op, awaitStart = true)
        }
    }

    /**
     * 轮询 `descriptor.fetch` 直到 core 自报终态。
     *
     * ## `idle` 为什么不能直接当失败，两种模式又为什么不一样
     * 组件的 `ComponentManager.install()` 在**返回前**就把 progress 置成 DOWNLOADING，
     * 所以 `TO_TERMINAL` 模式下正常情况下第一次回读就看得见任务；连续
     * [IDLE_GIVE_UP_TICKS] 次 idle 才能判定"core 没接这个任务"。
     *
     * `AWAIT_START` 模式（检查更新）面对的是另一件事：Web 的 `POST /api/web/check`
     * 是"丢进自己的 scope 就返回"，而且**当 Web 已是最新版本时 core 根本不会进入进行中**
     * （它直接把状态置成 idle + 一句"已是最新版本"）。所以这一模式把"一直没进入进行中"
     * 解释成**成功**（本次检查没有可做的事），只有"进入过进行中"才继续跟到终态。
     * 少了这个模式，用户点「检查更新」会看到一条假的失败提示。
     *
     * @param awaitStart true = 允许"始终没开始"以成功收尾（见上）。
     */
    private suspend fun pollUntilTerminal(
        api: ResourceApi,
        descriptor: ResourceDescriptor,
        op: ResourceOp,
        awaitStart: Boolean
    ): String? {
        var idleTicks = 0
        var started = false
        repeat(MAX_POLL_TICKS) {
            delay(POLL_INTERVAL_MS)
            val snapshot = try {
                descriptor.fetch(api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单次回读失败不判死：任务还在 core 那边跑，网络抖一下就放弃是最糟的选择。
                idleTicks++
                if (idleTicks > IDLE_GIVE_UP_TICKS) return "读取任务状态失败：${e.message ?: "未知错误"}"
                return@repeat
            }
            val task = snapshot.task
            if (task.terminal) {
                return if (task.state == STATE_DONE) null
                else task.message.ifBlank { "任务以 ${task.state} 结束" }
            }
            if (task.state != STATE_IDLE) started = true
            put(
                Task(
                    resourceId = descriptor.id,
                    label = op.label,
                    phase = Task.Phase.RUNNING,
                    percent = task.percent,
                    message = task.message.ifBlank { "${op.label}中…" },
                    coreState = task.state,
                )
            )
            idleTicks = if (task.state == STATE_IDLE) idleTicks + 1 else 0
            if (idleTicks > IDLE_GIVE_UP_TICKS) {
                return when {
                    // 检查更新：core 始终没进入进行中 ⇒ 没有可做的事，这不是失败
                    awaitStart && !started -> null
                    // 跑过一轮又回到空闲：core 已经收工（它会把终态写成 done/failed，走到这里说明
                    // 状态被下一次任务或卸载重置了）—— 按成功收尾，不编一个失败出来
                    started -> null
                    else -> "core 未报告任务状态（可能已拒绝或尚未开始）"
                }
            }
        }
        // 超时**不是**失败判据：core 的重试/镜像降级可能还在跑。措辞必须留给用户"再等等"的余地。
        return "${op.label}在 ${MAX_POLL_TICKS * POLL_INTERVAL_MS / 1000} 秒内未落终态，core 可能仍在后台继续"
    }

    private fun put(task: Task) {
        _tasks.value = _tasks.value + (task.resourceId to task)
    }

    companion object {
        /** 轮询间隔：组件下载是分钟级任务，1s 足够跟上百分比且不至于把 HTTP 打成风暴。 */
        private const val POLL_INTERVAL_MS = 1000L

        /** 轮询上限（约 3 分钟）。到点只报"还没落终态"，不判失败 —— 理由见 [pollUntilTerminal]。 */
        private const val MAX_POLL_TICKS = 180

        /** 连续多少次 idle 就认为 core 没接这个任务。 */
        private const val IDLE_GIVE_UP_TICKS = 5

        /** 成功态在界面上停留多久（对勾的"让人看见"时间）。 */
        private const val SUCCESS_LINGER_MS = 1500L

        /** core 两侧状态机里唯一的成功终态名（`ComponentManager.State.DONE` / `WebUpdateManager.State.DONE`）。 */
        const val STATE_DONE = "done"

        /** 空闲态名（两侧同名）。轮询时用它判断"core 还没接这个任务"。 */
        const val STATE_IDLE = "idle"

        /**
         * 进程内单例（理由见类头「为什么 scope 是进程级」）。
         *
         * `Dispatchers.Main.immediate` 而不是 IO：任务体只是"发一个 suspend 请求 + 每 1s 回读一次"，
         * 真正的网络在 OkHttp 自己的线程池上；而 `_tasks` 这个 StateFlow 的写入要在主线程，
         * 免得 Compose 侧收到跨线程写入带来的可见性时序问题。
         * `SupervisorJob`：一个资源的任务崩掉不该连坐另一个资源。
         */
        val shared: ResourceTaskController by lazy {
            ResourceTaskController(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }
    }
}

/**
 * 下发之后**要不要盯着 core 的状态机**、以及怎么盯。由 [ResourceDescriptor.installPoll]
 * 与操作类型共同决定（见 [ResourceTaskController.runOp]）。
 */
enum class PollMode {
    /** 响应即终态（core 在请求内同步做完）。 */
    NONE,

    /** core 立即返回"已受理"，真进度靠轮询到服务端自报终态（组件下载安装）。 */
    TO_TERMINAL,

    /**
     * 只"把任务丢给 core"：POST 回来时 core 可能还没开始跑，也可能**根本不会跑**
     * （例如 Web 检查更新发现已是最新版本）。先等它进入进行中，再跟着轮询到终态；
     * 一直没进入进行中 ⇒ 本次没有可做的事，按成功收尾。
     */
    AWAIT_START,
}

/**
 * 一次操作。**形态与资源身份无关** —— runner 按"操作类型"分支，不按资源 id 分支。
 *
 * 这里只有"用户想要什么"，没有"服务端会怎么表现" —— 后者是描述符的事（[PollMode]）。
 * 三个动词的服务端行为逐条读 core 源码确认过：
 *  · `Install` → `POST /api/components/{id}/install`（异步受理）或 `POST /api/web/check`（异步触发）；
 *  · `Upload` → `POST /api/web/update`，在**请求内同步**完成安装再响应
 *    （`WebUpdateRoutes` 里 `installFromZip` 是同步调用），所以响应即终态；
 *  · `Uninstall` / `Rollback` / `Clear` → 同样在请求内同步完成。
 *  · `Refresh` → 只读回读（组件侧 `listComponents(refresh=true)` 在请求内同步拉清单；
 *    Web 侧只重读 `/api/web/version`，无副作用）。
 */
sealed interface ResourceOp {
    val label: String

    /**
     * 安装 / 更新某一条目（[ResourceKind.COLLECTION] 用 [itemId]；单例传自己的 id）。
     *
     * [label] 由调用方传成卡片上那颗按钮的文案（"下载安装" / "更新" / "在线更新"）——
     * 否则用户点了「在线更新」，进度行却写"下载安装中…"，两句话对不上。
     */
    data class Install(val itemId: String, override val label: String = "下载安装") : ResourceOp

    /** 本地上传安装。 */
    data class Upload(val file: File) : ResourceOp {
        override val label = "上传"
    }

    /** 卸载某一条目。 */
    data class Uninstall(val itemId: String) : ResourceOp {
        override val label = "卸载"
    }

    /** 重新读一次（组件 = 让 core 重拉远端清单；Web = 重读版本信息）。 */
    data object Refresh : ResourceOp {
        override val label = "检查更新"
    }

    /** 回滚到上一版。 */
    data object Rollback : ResourceOp {
        override val label = "回滚"
    }

    /** 清除自定义资源，恢复随包分发的版本。 */
    data object Clear : ResourceOp {
        override val label = "恢复内置版本"
    }
}
