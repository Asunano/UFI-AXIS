// 2026-10-05 P7：App 端通用确认器（原【反馈】S4/S5）。
// 状态机是业务逻辑，供各 Module 复用；UI 层只消费 UiState（viewmodel↔ui 边界干净）。
package com.ufi_axis.viewmodel.module

import com.ufi_axis_core.contract.NetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「执行 → 等待结果 → 获取结果 → 更新」通用确认器（app 端）。
 *
 * 轮询节奏与 contract 的 [NetworkMode.SwitchProbe] 同源（web 端同构：useApiOperation），
 * 节奏常量不允许各操作自己发明。
 *
 * 状态机：Idle → Running → Success（短暂停留后自动 Idle） / Failed
 *         Running --toBackground()--> Hidden（轮询继续，toast 照常）
 *
 * @param scope 宿主 Module 的 scope。宿主 onCleared 时 scope 取消 → 轮询停止
 *        （CancellationException 逐层上抛，见 run 的 catch 分支）。
 * @param notify 2026-10-14（修 G8）：**hidden 态**下失败/超时时唯一的出口（Toast / 通知）。
 *        为什么必须是构造参数而不是各 Spec 自己实现：hidden 是**状态机内部**的判断
 *        （只有 run 里知道当前 kind 是不是 HIDDEN），调用方拿不到这个上下文。
 *        默认空实现 = 没有通知通道：那时 hidden 超时会静默 —— 所以宿主应当传一个。
 */
class ApiOperationController(
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit = {},
) {

    enum class Kind { IDLE, RUNNING, HIDDEN, SUCCESS, FAILED }

    data class UiState(
        val kind: Kind = Kind.IDLE,
        val title: String = "",
        val runningText: String = "",
        val successText: String = "",
        val hint: String? = null,
        val failReason: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 宿主 Module onCleared 时调用：后台轮询立即停止（取旧起新，防两个轮询并发）。 */
    private var job: Job? = null

    /** 2026-10-05 P7：最近一次下发规格，供 failed 态「重试」复跑（E-6 重试即重新 run）。 */
    private var lastSpec: Spec? = null

    interface Spec {
        val title: String
        val runningText: String
        val successText: String
        val hint: String? get() = null
        /** 下发。返回 null = 受理成功；返回字符串 = 失败原因 */
        suspend fun submit(): String?
        /** 回读判定设备是否已生效。抛异常按当次未命中处理 */
        suspend fun verify(): Boolean
        /** 生效后的额外部动作（刷新状态源等） */
        fun onApplied() {}

        /**
         * 2026-10-14（修 G4）：本次操作**已经结束**时的收尾 —— 成功、超时、下发失败、
         * 被新操作顶掉、被 dismiss 取消，这五条路径都会调用。
         *
         * 为什么需要这个钩子：确认器只管自己的**弹窗**状态机，而调用方还有一批**行内**
         * pending 位（如 `NetworkState.mobileDataPending`，它让那一行显示「切换中…」）。
         * 这些位不属于状态机，却必须和它收敛在同一条时间线上：少了这个钩子，用户点了
         * 「后台进行」之后超时，那一行就会**永久**停在「切换中…」。
         *
         * @param applied true = 回读确认生效（紧接 [onApplied] 之后调用）；
         *        false = 超时 / 下发失败 / 被取消
         */
        fun onSettled(applied: Boolean) {}
    }

    fun run(spec: Spec) {
        // 2026-10-14（修 G4）：旧操作被新操作顶掉时，先收尾它的行内 pending。
        // 不收尾的话「点了移动数据、还没结束又去点 WiFi」会让移动数据那一行永久停在
        // 「切换中…」（旧 job 被 cancel，它自己再也没机会调用 onSettled）。
        val previous = lastSpec
        if (job?.isActive == true) previous?.onSettled(false)
        lastSpec = spec
        // 极端情况：上一个操作还在跑时用户又点了一个 → 取旧起新，避免两个轮询并发
        job?.cancel()
        job = scope.launch {
            _state.value = UiState(Kind.RUNNING, spec.title, spec.runningText, spec.successText, spec.hint)

            // 1. 下发
            val failure = try { spec.submit() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { e.message ?: "${spec.title}失败" }
            if (failure != null) {
                _state.value = _state.value.copy(kind = Kind.FAILED, failReason = failure)
                spec.onSettled(false)
                return@launch
            }

            // 2. 等待：节奏取 contract SwitchProbe（600ms 首延 + 13 次快慢档）
            delay(NetworkMode.SwitchProbe.FIRST_DELAY_MS)
            var attempt = 0
            var reached = false
            while (true) {
                attempt++
                reached = try { spec.verify() } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { false }
                if (!NetworkMode.SwitchProbe.shouldKeepProbing(attempt, reached)) break
                delay(NetworkMode.SwitchProbe.intervalMsAfter(attempt))
            }

            // 3. 结果
            if (reached) {
                spec.onApplied()
                spec.onSettled(true)
                if (_state.value.kind != Kind.HIDDEN) {
                    _state.value = _state.value.copy(kind = Kind.SUCCESS)
                    delay(300)                       // 对勾停留
                    if (_state.value.kind != Kind.HIDDEN) _state.value = UiState()   // 自动关闭
                } else {
                    // 2026-10-14：hidden 只表示"这次操作不占屏幕"，操作一旦结束它就没有意义了
                    //（web 同构：useApiOperation.ts:184 末尾把 hidden 收敛回 idle）。
                    // 留在 HIDDEN 会让下一次 toBackground() 的语义错位 —— 它只对 RUNNING 生效。
                    _state.value = UiState()
                }
                // 成功 toast 由调用方经 emitWriteNotice 发（保持既有通道，见 S5）
            } else {
                /*
                 * 2026-10-14 修（G8：hidden 态超时会把弹窗弹回来）：
                 *
                 * 原来这一支**没有** hidden 守卫，直接 `copy(kind = FAILED)`。而 HIDDEN 是用户
                 * 主动点「后台进行」的结果（"我去干别的，别挡着我"），超时那一刻却把弹窗
                 * **重新盖回界面** —— 交互语义正好相反（web 有守卫：useApiOperation.ts:164）。
                 *
                 * 现在的口径：visible 态照旧在弹窗里报（用户正看着它）；hidden 态**不碰弹窗**，
                 * 只走 [notify] 通知通道（Toast / 既有通知），并把状态收敛回 Idle。
                 * 文案与 visible 态一字不差 —— 两端与两种形态说同一句话，不各写一份。
                 */
                val reason = "${spec.title}未在预期时间内完成，设备可能仍在后台继续"
                spec.onSettled(false)
                if (_state.value.kind == Kind.HIDDEN) {
                    notify(reason)
                    _state.value = UiState()
                } else {
                    _state.value = _state.value.copy(kind = Kind.FAILED, failReason = reason)
                }
            }
        }
    }

    fun toBackground() {
        if (_state.value.kind == Kind.RUNNING) _state.value = _state.value.copy(kind = Kind.HIDDEN)
    }

    /** 2026-10-05 P7：failed 态「重试」——复跑最近一次规格（下发请求本身幂等，不加额外防抖）。 */
    fun retry() { lastSpec?.let { run(it) } }

    fun dismiss() {
        // 2026-10-14（修 G4）：取消进行中的操作时也要收尾行内 pending。
        // 只在**真的还在跑**时收尾：failed 态的「关闭」按钮走的是同一条路径，那时若再收尾一次，
        // 会把**新一次**操作（用户已经点了重试或另一个开关）的 pending 位抹掉。
        val running = job?.isActive == true
        job?.cancel()
        if (running) lastSpec?.onSettled(false)
        _state.value = UiState()
    }
}
