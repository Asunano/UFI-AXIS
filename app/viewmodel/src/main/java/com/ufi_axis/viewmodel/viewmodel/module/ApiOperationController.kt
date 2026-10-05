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
 */
class ApiOperationController(private val scope: CoroutineScope) {

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
    }

    fun run(spec: Spec) {
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
                if (_state.value.kind != Kind.HIDDEN) {
                    _state.value = _state.value.copy(kind = Kind.SUCCESS)
                    delay(300)                       // 对勾停留
                }
                if (_state.value.kind != Kind.HIDDEN) _state.value = UiState()   // 自动关闭
                // 成功 toast 由调用方经 emitWriteNotice 发（保持既有通道，见 S5）
            } else {
                _state.value = _state.value.copy(
                    kind = Kind.FAILED,
                    failReason = "${spec.title}未在预期时间内完成，设备可能仍在后台继续"
                )
            }
        }
    }

    fun toBackground() {
        if (_state.value.kind == Kind.RUNNING) _state.value = _state.value.copy(kind = Kind.HIDDEN)
    }

    /** 2026-10-05 P7：failed 态「重试」——复跑最近一次规格（下发请求本身幂等，不加额外防抖）。 */
    fun retry() { lastSpec?.let { run(it) } }

    fun dismiss() { job?.cancel(); _state.value = UiState() }
}
