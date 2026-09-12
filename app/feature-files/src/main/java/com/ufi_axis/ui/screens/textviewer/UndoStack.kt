package com.ufi_axis.ui.screens.textviewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 撤销 / 重做栈。
 *
 * Compose 的 `BasicTextField` 没有内置撤销（平台 EditText 有，Compose 是从零实现的输入栈，
 * 到 1.7 仍未提供公开的 undo 接口），所以这里自己记快照。
 *
 * ## 为什么按时间合并
 * 逐字符记一条会让「撤销」变成"退一个字"，用户点十次才回到一句话之前 —— 与预期的
 * "撤销一次操作"不符。[MERGE_WINDOW_MS] 内的连续输入并成一条：停手超过这个间隔才算一次操作边界。
 *
 * ## 为什么有深度上限
 * 每条快照都是整篇文本的一份拷贝。128KB 文本 × 50 条 ≈ 6.4MB，已经是移动端能接受的上限；
 * 超阈值文件（用户确认后强行编辑的那种）由调用方传更小的 [maxDepth]。
 *
 * ## 为什么存 [TextFieldValue] 而不只是文本
 * 撤销后光标要回到当时的位置。只存文本的话撤销完光标停在文末，紧接着输入就插错地方。
 */
class UndoStack(private val maxDepth: Int = DEFAULT_DEPTH) {

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    /** 上一条快照的记录时刻，用于 [MERGE_WINDOW_MS] 合并判定。 */
    private var lastPushAt = 0L

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** 载入新文件或重新加载：清空历史，把当前内容作为起点。 */
    fun reset(initial: TextFieldValue) {
        undoStack.clear()
        redoStack.clear()
        undoStack.addLast(initial)
        lastPushAt = 0L
        sync()
    }

    /**
     * 记录一次编辑后的状态。
     *
     * @param nowMs 由调用方传入当前时刻 —— 便于测试注入，也避免这里直接依赖系统时钟。
     * @param forceBoundary true 表示这是一次"整体操作"（全部替换、粘贴大段），
     *   不与前一次合并，撤销时应该一步回到操作前。
     */
    fun push(value: TextFieldValue, nowMs: Long, forceBoundary: Boolean = false) {
        val prev = undoStack.lastOrNull()
        if (prev != null && prev.text == value.text) {
            // 只移动光标不算编辑：否则点几下屏幕就把撤销历史填满
            undoStack.removeLast()
            undoStack.addLast(value)
            return
        }
        val merge = !forceBoundary && nowMs - lastPushAt < MERGE_WINDOW_MS && undoStack.size > 1
        if (merge) undoStack.removeLast()
        undoStack.addLast(value)
        while (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
        lastPushAt = nowMs
        sync()
    }

    /** 撤销一步，返回应当采纳的状态；已到底则返回 null。 */
    fun undo(): TextFieldValue? {
        if (undoStack.size <= 1) return null
        val current = undoStack.removeLast()
        redoStack.addLast(current)
        // 撤销之后紧接着输入不应与撤销前的时刻合并
        lastPushAt = 0L
        sync()
        return undoStack.lastOrNull()
    }

    /** 重做一步；没有可重做的则返回 null。 */
    fun redo(): TextFieldValue? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(next)
        lastPushAt = 0L
        sync()
        return next
    }

    private fun sync() {
        canUndo = undoStack.size > 1
        canRedo = redoStack.isNotEmpty()
    }

    companion object {
        const val DEFAULT_DEPTH = 50

        /** 超阈值文件强行编辑时的深度：整篇更大，快照更贵。 */
        const val HUGE_DEPTH = 10

        /** 连续输入的合并窗口。500ms 是"停手了"与"还在打字"的常用分界。 */
        const val MERGE_WINDOW_MS = 500L
    }
}
