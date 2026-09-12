package com.ufi_axis.ui.screens.textviewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ufi_axis.viewmodel.state.TextFileContent

/**
 * 文本编辑页的全部状态。
 *
 * ## 2026-09-11：删除"查看 / 编辑"双模式
 * 这个页面的**唯一用途是编辑**，不再有只读浏览态：
 * - 旧版两态是两套互不相干的渲染实现（`LazyColumn` 逐行只读 vs 单个 `BasicTextField`），
 *   能力长期不对等 —— 编辑态没有行号，而查找与跳转行只对只读态生效，只读态又恰恰是
 *   唯一不能编辑的那一态，于是这两个按钮在能编辑的小文件里点了没反应；
 * - 只读态的唯一价值（看大文件）已由「整份下载到手机缓存后再编辑」覆盖，见 [localPath]。
 *
 * 所以 `EditorMode` 枚举与 `mode` 字段整体删除，正文只剩一条渲染路径。
 * 保留的一道闸门是 [canEdit]：单个 `BasicTextField` 面对超大文本会明显卡顿，超过
 * [EDITABLE_MAX_BYTES] / [EDITABLE_MAX_LINES] 时必须由用户显式确认（[hugeEditConfirmed]）。
 * 未确认时正文仍以 `readOnly = true` 呈现 —— 那是**权限守卫**，不是另一种渲染模式。
 *
 * ## 为什么是一个类而不是十几个 `remember`
 * 收成一个类之后，能力是**算出来的**（[canEdit] / [editable]），不再各处 if 各自判断。
 *
 * ## 正文用 [TextFieldValue] 而不是 String
 * 光标位置是这一页的一等公民：底栏要显示行列、跳转行要移动光标、查找要选中命中区间。
 * 旧实现用 String，所以底栏那个「第 N 行」其实一直是总行数。
 */
class TextViewerState(
    /** 设备端路径。本地副本模式下它是**来源**路径，被编辑的其实是 [localPath]。 */
    val path: String,
    /** 服务端单次可读上限（KB），仅用于文案 —— 真实上限由服务端决定。 */
    val maxReadKb: Int,
    initialValue: TextFieldValue = TextFieldValue(""),
    initialDirty: Boolean = false,
    initialEncoding: String = "utf-8",
    initialEol: Eol = Eol.LF,
    initialSoftWrap: Boolean = true,
    initialLineNumbers: Boolean = true,
    /**
     * 非空 = 正在编辑手机上的**本地副本**（大文件下载后再打开）；空 = 直接编辑设备端文件。
     *
     * 两种来源的读写分别落到 `FileManagerModule.readTextFile/writeTextFile`（走 HTTP）
     * 与 `readLocalTextFile/writeLocalTextFile`（走 java.io），除此之外整页行为完全一致。
     */
    initialLocalPath: String? = null
) {
    var value by mutableStateOf(initialValue)
        private set

    var loading by mutableStateOf(true)

    /** 本页私有的读取错误。刻意不放进共享的 `FileManagerState.errorMessage`（见 readTextFile 的注释）。 */
    var loadError by mutableStateOf<String?>(null)

    /** 内容不可用的原因，见 [TextFileContent.reason]。非空时正文区展示说明而不是内容。 */
    var reason by mutableStateOf<String?>(null)
    var fileSize by mutableStateOf(0L)
    var encoding by mutableStateOf(initialEncoding)
    var encodingSuspect by mutableStateOf(false)
    var eol by mutableStateOf(initialEol)

    /** 本地副本的绝对路径，见构造参数 [initialLocalPath]。 */
    var localPath by mutableStateOf(initialLocalPath)

    var dirty by mutableStateOf(initialDirty)
    var saving by mutableStateOf(false)

    /**
     * 最后一次「已同步」基准正文（读取/保存成功那一刻的内容）。
     * 撤销/重做回来后据此判断是否已经回到干净状态，而不是像旧实现那样无条件 `dirty = true`
     * （那样撤销到原内容仍显示"未保存"，脏标记无法回 false）。
     */
    var cleanSnapshot by mutableStateOf("")

    var showLineNumbers by mutableStateOf(initialLineNumbers)
    var softWrap by mutableStateOf(initialSoftWrap)

    /** 用户已确认"知道会卡，仍要编辑大文件"。 */
    var hugeEditConfirmed by mutableStateOf(false)

    // ── 查找 / 替换 ──
    var findVisible by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var replaceExpanded by mutableStateOf(false)
    var matches by mutableStateOf<List<IntRange>>(emptyList())
    var matchIndex by mutableStateOf(0)

    var undo = UndoStack()
        private set

    private val lineStartsState = derivedStateOf { lineStartOffsets(value.text) }

    /** 每行起始下标。文本变化时才重算（[derivedStateOf] 兜住重组）。 */
    val lineStarts: IntArray get() = lineStartsState.value

    val lineCount: Int get() = lineStarts.size

    /** 光标所在行（1 基）。选区状态下取选区起点所在行。 */
    val cursorLine: Int get() = lineIndexOf(lineStarts, value.selection.start) + 1

    /** 光标所在列（1 基，按字符计）。 */
    val cursorColumn: Int
        get() = value.selection.start - lineStarts[cursorLine - 1] + 1

    val selectedChars: Int get() = value.selection.length

    /** 内容可用（服务端给的是真内容，不是说明文字）。 */
    val usable: Boolean get() = reason == null

    /** 是否在编辑手机上的本地副本（大文件下载后再打开）。 */
    val localCopy: Boolean get() = localPath != null

    /** 文件大小是否在"直接可编辑"的范围内。 */
    val withinEditableSize: Boolean get() = fileSize <= EDITABLE_MAX_BYTES && lineCount <= EDITABLE_MAX_LINES

    /**
     * 是否允许输入：内容可用，且（体量够小 或 用户已确认强行编辑）。
     *
     * 这是唯一的闸门 —— 没有第二个"模式"维度（见类注释）。
     */
    val canEdit: Boolean get() = usable && !loading && (withinEditableSize || hugeEditConfirmed)

    /** 当前正文是否可输入。 */
    val editable: Boolean get() = canEdit && !saving

    /** 采纳一次读取结果。会重置脏标记、撤销栈与查找态。 */
    fun adopt(content: TextFileContent) {
        val normalized = normalizeEol(content.content)
        eol = Eol.detect(content.content)
        encoding = content.encoding
        encodingSuspect = content.encodingSuspect
        reason = content.reason
        fileSize = content.size
        val next = TextFieldValue(normalized, TextRange.Zero)
        value = next
        cleanSnapshot = next.text
        dirty = false
        loading = false
        loadError = null
        hugeEditConfirmed = false
        matches = emptyList()
        matchIndex = 0
        undo = UndoStack(if (withinEditableSize) UndoStack.DEFAULT_DEPTH else UndoStack.HUGE_DEPTH)
        undo.reset(next)
    }

    /** 用户输入。[nowMs] 由调用方给（撤销栈的合并窗口需要时钟）。 */
    fun onInput(next: TextFieldValue, nowMs: Long) {
        val textChanged = next.text != value.text
        value = next
        if (textChanged) {
            dirty = true
            undo.push(next, nowMs)
        } else {
            // 只动了光标/选区：同步进撤销栈的栈顶，撤销回来时光标位置才是对的
            undo.push(next, nowMs)
        }
    }

    /** 程序性地整体替换正文（替换全部、以其它编码重开）。记为一次操作边界。 */
    fun replaceAll(next: TextFieldValue, nowMs: Long, markDirty: Boolean = true) {
        value = next
        if (markDirty) dirty = true
        undo.push(next, nowMs, forceBoundary = true)
    }

    /** 只移动光标 / 选区，不触碰脏标记与撤销栈。 */
    fun moveSelection(range: TextRange) {
        value = value.copy(selection = range)
    }

    fun applyUndo() { undo.undo()?.let { value = it }; dirty = value.text != cleanSnapshot }
    fun applyRedo() { undo.redo()?.let { value = it }; dirty = value.text != cleanSnapshot }

    /** 保存成功：记下干净基线、清脏标记并更新字节数（编辑后大小变了，顶栏不该继续显示读取时的旧值）。 */
    fun onSaved(bytes: Long) {
        cleanSnapshot = value.text
        dirty = false
        fileSize = bytes
    }

    companion object {
        /**
         * 可直接编辑的字节上限。
         *
         * `BasicTextField` 是单个 TextLayout，超过这个量级每次输入都要重排整篇文本。
         * 128KB 是保守取值 —— 真实的卡顿门槛取决于设备与行长分布，所以留成常量便于调。
         */
        const val EDITABLE_MAX_BYTES = 128 * 1024

        /** 行数上限。字节数没超但行数极多（例如每行两个字符的日志）同样会卡。 */
        const val EDITABLE_MAX_LINES = 4000
    }
}

/**
 * 按 [path] 分桶的状态，并在配置变更 / 进程恢复后带回未保存的编辑。
 *
 * 只存必要字段：正文、光标、脏标记、本地副本路径与几个显示开关。文件元信息（大小 / 编码 / 原因）
 * 不存 —— 恢复后会重新读一次文件拿到最新值，而正文若是脏的则以内存里这份为准。
 */
@Composable
fun rememberTextViewerState(
    path: String,
    maxReadKb: Int,
    localPath: String? = null
): TextViewerState =
    rememberSaveable(path, saver = TextViewerStateSaver(path, maxReadKb, localPath)) {
        TextViewerState(path, maxReadKb, initialLocalPath = localPath)
    }

private fun TextViewerStateSaver(
    path: String,
    maxReadKb: Int,
    localPath: String?
) = listSaver<TextViewerState, Any>(
    save = {
        listOf(
            it.value.text,
            it.value.selection.start,
            it.value.selection.end,
            // 本地副本路径：空串代表"这不是本地副本"（listSaver 不收 null）
            it.localPath.orEmpty(),
            it.dirty,
            it.encoding,
            it.eol.name,
            it.softWrap,
            it.showLineNumbers
        )
    },
    restore = { saved ->
        TextViewerState(
            path = path,
            maxReadKb = maxReadKb,
            initialValue = TextFieldValue(
                text = saved[0] as String,
                selection = TextRange(saved[1] as Int, saved[2] as Int)
            ),
            initialDirty = saved[4] as Boolean,
            initialEncoding = saved[5] as String,
            initialEol = Eol.valueOf(saved[6] as String),
            initialSoftWrap = saved[7] as Boolean,
            initialLineNumbers = saved[8] as Boolean,
            // 优先用恢复出来的那份：转屏时 localPath 已是运行期结果，覆盖构造参数才不会丢
            initialLocalPath = (saved[3] as String).takeIf { it.isNotEmpty() } ?: localPath
        )
    }
)

/**
 * 全文扫描出所有命中区间。
 *
 * 必须在后台线程调用：对 128KB 文本做一次全扫是几毫秒，但每敲一个字符扫一次就会掉帧，
 * 调用方还要再叠一层 debounce。
 */
fun findMatches(text: String, query: String, caseSensitive: Boolean): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val result = ArrayList<IntRange>()
    var from = 0
    while (from <= text.length - query.length) {
        val at = text.indexOf(query, from, ignoreCase = !caseSensitive)
        if (at < 0) break
        result.add(at until at + query.length)
        // 从命中之后继续找，不允许重叠命中 —— 「aa」在「aaa」里算 1 处而不是 2 处，
        // 与替换全部的行为保持一致（替换是不重叠的）。
        from = at + query.length
        if (result.size >= MAX_MATCHES) break
    }
    return result
}

/**
 * 命中数上限。
 *
 * 一个字符的关键字在大文件里能有几万处命中，全都存下来对定位毫无帮助，
 * 只会让列表本身变成内存与滚动的负担。到上限就停，UI 显示「500+」。
 */
const val MAX_MATCHES = 500
