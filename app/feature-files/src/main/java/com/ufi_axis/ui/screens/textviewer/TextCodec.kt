package com.ufi_axis.ui.screens.textviewer

import com.ufi_axis.viewmodel.state.TextFileContent

/**
 * 换行符风格。
 *
 * 编辑器内部**统一用 `\n`**，只在读入时记住原始风格、写回前还原。
 * 不这么做的后果是实打实的：设备上的 `.sh`、`.conf` 多半是 CRLF，旧实现整文件覆盖写回时
 * 全部变成 LF，文件在 diff 里显示"每一行都改了"，而用户只改了一行。
 */
enum class Eol(val label: String, val text: String) {
    LF("LF", "\n"),
    CRLF("CRLF", "\r\n");

    companion object {
        /**
         * 按**第一个**换行判定整篇风格。
         *
         * 不做逐行统计：混合换行符的文件本来就没有"正确答案"，按第一个走至少是可预期的；
         * 统计投票会让改一行触发整篇换行符翻转，比不判定更糟。
         */
        fun detect(raw: String): Eol {
            val n = raw.indexOf('\n')
            return if (n > 0 && raw[n - 1] == '\r') CRLF else LF
        }
    }
}

/** 把任意换行风格的文本统一成 `\n`。 */
fun normalizeEol(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n')

/** 按 [eol] 还原换行风格，用于写回。 */
fun restoreEol(text: String, eol: Eol): String =
    if (eol == Eol.LF) text else text.replace("\n", eol.text)

/**
 * 可选编码。
 *
 * 只给这三档：UTF-8 是默认，GBK 覆盖国产设备上的绝大多数遗留文件，
 * Latin-1 是"按字节原样看"的逃生舱（任何字节序列都能解码，不会出替换字符）。
 * 再多的编码档位在移动端选择器里只会让人不知道选哪个。
 */
val EDITOR_ENCODINGS = listOf("utf-8", "gbk", "iso-8859-1")

/** 编码的展示名。 */
fun encodingLabel(name: String): String = when (name.lowercase()) {
    "utf-8", "utf8" -> "UTF-8"
    "gbk" -> "GBK"
    "iso-8859-1", "latin1" -> "Latin-1"
    else -> name.uppercase()
}

/**
 * 每一行在整篇文本里的起始下标。
 *
 * 光标行列、跳转行、只读视图的分行全部由它推导，避免各处重复 `split('\n')`
 * （那会为一份 128KB 的文本再复制一份出来）。
 */
fun lineStartOffsets(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)
    val starts = ArrayList<Int>(text.count { it == '\n' } + 1)
    starts.add(0)
    var i = text.indexOf('\n')
    while (i >= 0) {
        starts.add(i + 1)
        i = text.indexOf('\n', i + 1)
    }
    return starts.toIntArray()
}

/** 下标 [offset] 落在第几行（0 基）。二分查找，行数再多也是对数级。 */
fun lineIndexOf(starts: IntArray, offset: Int): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (starts[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

/**
 * 内容不可用时给用户看的说明。
 *
 * 三种原因文案各不相同 —— 旧实现全部复用一句「服务端已截断（>256KB）」，
 * 于是"文件不存在"和"这是二进制文件"都被说成了"文件太大"。
 */
fun unusableMessage(reason: String, maxReadKb: Int): String = when (reason) {
    TextFileContent.REASON_TOO_LARGE ->
        "文件超过 ${maxReadKb}KB，无法在线查看。可用「下载到手机」取回完整文件。"
    TextFileContent.REASON_BINARY ->
        "这是二进制文件，不是文本，无法查看或编辑。"
    TextFileContent.REASON_NOT_FILE ->
        "路径不存在，或者它不是一个文件。"
    else -> "内容不可用。"
}
