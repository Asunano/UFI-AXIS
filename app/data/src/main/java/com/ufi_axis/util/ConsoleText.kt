package com.ufi_axis.util

/**
 * 去掉 Shell 消息开头的提示符（2026-10-31）。
 *
 * 高级控制台的 Shell 页把用户输入渲染成 `$ <command>`（root 是 `# <command>`），
 * 但那两个字符是**界面提示符**，不是命令的一部分。长按复制时如果原样复制，粘到终端里
 * 就变成 `$ ls` —— shell 会把它当成「执行名为 `$` 的命令」。
 *
 * 只去掉**开头的一个**提示符，且要求提示符后跟一个空格：
 * - `$ ls -la` → `ls -la`
 * - `# id` → `id`（root 提示符同理）
 * - `$ # comment` → `# comment`（命令自身以 `#` 开头时，只去掉外层的 `$`）
 * - `echo $HOME` / `AT+CSQ` → 原样（没有开头的提示符）
 *
 * 与 `ToolsModule` 里「重新发送」的历史命令剥离逻辑（`removePrefix("# ").removePrefix("$ ")`）
 * 保持同一判据，避免复制和重发对同一条消息给出两种结果。
 */
fun stripShellPrompt(text: String): String = when {
    text.startsWith("# ") -> text.removePrefix("# ")
    text.startsWith("$ ") -> text.removePrefix("$ ")
    else -> text
}
