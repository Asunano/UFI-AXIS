package com.ufi_axis.viewmodel

import com.ufi_axis_core.util.UiFrameGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 转场期间**暂缓发布**的状态流（2026-11-03，统一方案）。
 *
 * ## 解决什么
 *
 * 帧统计显示的规律很稳定：动画本身满帧（8.3 / 16.6ms），掉帧一律发生在
 * **"进页那一刻有数据往屏幕上砸"** —— 列表首屏、图表序列、告警、缩略图、配置回包……
 * 每一条都是"整页或半页重组"，落在转场里就是 24~66ms。
 *
 * 逐个模块加 `awaitTransitionIdle()` 能治，但那是**打补丁**：新页面、新模块只要忘了加，
 * 同一个毛病就回来。所以把闸门挪到**唯一出口**上：`MainViewModel` 暴露给 UI 的每一条
 * StateFlow 都过一道本函数 —— 转场期间攒着，落地时机统一后移。
 *
 * ## 语义
 *
 * - 闸门空闲（绝大多数时间）：**行为完全不变**，值原样透传；
 * - 闸门举起（Tab 切页 / 二级页转场）：新值先攒住，等闸门放开后**只提交最新那一份**
 *   （中间的中间态没有必要逐帧播）；
 * - 等待**有上限** [MAX_DEFER_MS]：可预测性返回是"被手指拖着的转场"，闸门可能被按住几秒，
 *   不能让开关、进度这类状态跟着冻住 —— 超时就照常发布，宁可掉一帧也不能失去响应。
 *   ⚠ 这个上限必须**大于一次正常转场的全长 + 闸门尾巴**，否则每次正常转场都会在动画
 *   中途强制放行一次（2026-11-03 复盘：原来写 300ms，而二级页转场默认 456ms、
 *   最长 600×1.2=720ms，于是"攒到 300ms 就砸下来"正好落在动画中段 —— 日志里
 *   每次 `[NavAnim]` 的最差帧都对得上这一次放行）。取值依据见 [MAX_DEFER_MS]。
 *
 * ## 为什么不是"延迟发请求"
 *
 * 那是另一种等价但更慢的做法：转场结束后才开始网络/DB 往返，用户要多等一个 RTT，
 * 看到的是"页面铺好了还空着"。这里请求照常发、只是**落地**晚一步，动画结束时数据已经在手上。
 *
 * ## 不适用
 *
 * 必须**逐帧**可见的东西不要过这道流：播放进度、录音电平、下载百分比。
 * 它们本该走高频率的独立通道，而不是这里的大状态对象。
 *
 * @param scope 收集用的作用域（`MainViewModel` 传 `viewModelScope`）。
 */
fun <T> StateFlow<T>.gatedByTransitionGate(scope: CoroutineScope): StateFlow<T> {
    val published = MutableStateFlow(value)
    scope.launch {
        collect {
            if (UiFrameGate.isBusy) {
                withTimeoutOrNull(MAX_DEFER_MS) { UiFrameGate.awaitIdle() }
            }
            // 提交时读**最新值**：等待期间可能又来了好几条，逐条补发没有意义。
            published.value = this@gatedByTransitionGate.value
        }
    }
    return published.asStateFlow()
}

/**
 * 单次最多暂缓多久（毫秒）。
 *
 * ## 为什么是 1000（2026-11-03 修正，原来是 300）
 *
 * 这个值的唯一职责是"兜住可预测性返回的长手势"，**不是**给正常转场设一个中途放行的闹钟。
 * 它必须覆盖正常转场的最坏时长，否则每一段正常转场都会在中途被执行一次强制发布：
 *
 * - 二级页：用户可把时长调到 600ms，再乘 `SHARED_AXIS_SLOWDOWN = 1.2` ⇒ 720ms；
 * - `UfiNavFrameGate` 在转场结束后还要压 [FRAME_GATE_TAIL_MS] = 150ms 才放开闸门；
 * - 两者相加 = 870ms。
 *
 * 1000ms 留出约 15% 余量。原来的 300ms 比**默认**转场（380×1.2=456ms + 150ms 尾巴）
 * 还短，于是数据固定会在动画进行到约 2/3 处落地 —— 真机日志里 `[NavAnim]` 每次
 * 16.6ms / 33.1ms 的最差帧，时间点全部对得上这一次放行。
 *
 * 超过 1000ms 的等待只可能来自"手指一直按着返回手势"：那种情况下画面本身是静止的，
 * 1000ms 后放行一次不会造成动画掉帧，同时保证开关 / 进度这类状态不会失去响应。
 */
internal const val MAX_DEFER_MS: Long = 1000L
