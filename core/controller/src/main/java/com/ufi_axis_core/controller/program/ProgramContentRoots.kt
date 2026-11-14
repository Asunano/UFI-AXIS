package com.ufi_axis_core.controller.program

/**
 * 安装参数 `content_root` 的准入规则（纯函数，便于单测）。
 *
 * ## 为什么这条校验必须存在
 *
 * 内容目录会被**创建**，卸载时还会被 `deleteAll` **递归删除** `<root>/<id>`。
 * 如果允许调用方随便传一个绝对路径，那么"装一次 + 卸一次"就等于"在任意目录建目录、删目录"，
 * 而商店本身就是能执行任意二进制的高危面，不该再叠加一个任意路径写入原语。
 *
 * 所以只接受 `/api/programs/volumes` 返回过的路径 —— 那些路径由 core 自己枚举
 * （内部存储 / 公共存储 / SD 卡上的 `UFI-AXIS/content`），不是客户端说了算。
 *
 * ## 为什么抽成独立对象
 *
 * 路由层拿不到 Ktor 之外的测试便利，而这条逻辑是**安全边界**：把它放在纯函数里，
 * 单测就能覆盖"相对路径 / 不在白名单 / 合法值 / 空值（用默认位置）"四种输入，
 * 而不是靠"读代码觉得没问题"。
 */
object ProgramContentRoots {

    /**
     * @param requested 调用方传来的 `content_root`（可为 null / 空串 = 用默认位置）
     * @param allowed 卷列表里出现过的绝对路径
     * @return 有问题时返回中文原因；null 表示合法
     */
    fun issue(requested: String?, allowed: Collection<String>): String? {
        if (requested.isNullOrBlank()) return null
        if (!requested.startsWith("/")) return "content_root 必须是绝对路径"
        if (requested == "/") return "content_root 不能是根目录"
        if (requested !in allowed) {
            return "content_root 不在可选卷列表里（请用 /api/programs/volumes 返回的路径）"
        }
        return null
    }
}
