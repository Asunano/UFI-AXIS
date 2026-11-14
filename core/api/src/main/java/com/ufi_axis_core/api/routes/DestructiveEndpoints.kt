package com.ufi_axis_core.api.routes

/**
 * 「开了主人密码门之后，哪些端点要密码」的登记表。
 *
 * ## 门槛口径：只盖不可逆 + 交出凭据
 *
 * 判据是：**做过之后能不能撤销 / 东西出了门能不能收回**。
 *
 * - 能重做 / 能改回来的一律不拦：装 APK、装程序、组件安装与更新、隧道增删改、程序启停、
 *   改基础配置开关。2026-10-30 那版把这些全登记了，实机反馈是「装个程序也要输密码」。
 * - 不可逆的拦：擦掉配置、覆盖成备份里的旧配置、以 root 跑任意命令、删掉全部日志证据、
 *   移除一台配对设备、解除黑名单、**卸载**（数据一并消失）。
 * - 交出凭据的也拦：程序初始口令、备份包里的 goform 口令与隧道 token。
 *
 * 例外：`PUT /api/config` 只在**值真的变了**且属于「重指向 / 打开危险开关」时才要密码，
 * 见 `ConfigRoutes` 里的条件门。它不在本表（本表是「无条件要密码」的登记），
 * 由 `OwnerGateCoverageTest` 单独扫源码守着。
 *
 * 明确**不**在门内：`/ws/terminal` 的交互式 shell（用户决定：终端是独立能力，不设密码）。
 *
 * ## 为什么留这张表
 *
 * 审计反复出现同一类事故：**门是逐个 handler 手写的**，于是「新增一个同能力的入口」
 * 时没人记得加（黑名单的批量解封就是这么漏的）。本表把「哪些端点是破坏性的」变成可执行事实，
 * 由 `OwnerGateCoverageTest` 守着：
 * ① 表里每个文件的路径字面量必须真的存在；
 * ② 每个 [DestructiveEndpoint.anchor] 在源码里**恰好出现一次** —— 这条比「数调用点」强，
 *    能发现「登记 A、门却加在 B」；
 * ③ 全仓 `requireOwner*` 调用点数量必须与表项数相等。
 *
 * ## 维护规矩
 *
 * 1. 新增不可逆端点 → 在这里加一行，handler 里那道门旁边写 `// [owner] <anchor>`；
 * 2. 可撤销的**不要**加 —— 门槛泛滥比漏门更容易让人把整个开关关掉；
 * 3. [passwordFrom] 只能是 [PasswordSource.BODY]（JSON 请求体 `password`）或
 *    [PasswordSource.HEADER]（`X-Owner-Password`，给 body 是文件流 / 没有 body 的端点用）。
 *
 * @property file `.../api/routes/` 下的文件名
 * @property method 小写的 Ktor 构建器名（`post` / `put` / `delete` / `patch`）
 * @property path 路由**字面量**；`null` 表示无路径的形式（`post { ... }` / `delete { ... }`）
 * @property occurrence 同文件内同一声明出现多次时的序号（0 起）。只有一个时保持 0
 * @property passwordFrom 门从哪里取主人凭据
 * @property note 为什么它算不可逆（给 review 的人看，不参与断言）
 * @property anchor 门的锚：handler 里那行必须写成 `// [owner] <anchor>`，测试按它逐条比对
 */
data class DestructiveEndpoint(
    val file: String,
    val method: String,
    val path: String?,
    val occurrence: Int = 0,
    val passwordFrom: PasswordSource,
    val note: String,
    val anchor: String,
)

/** 主人凭据的取法。 */
enum class PasswordSource {
    /** JSON 请求体里的 `password`。 */
    BODY,

    /** 请求头 `X-Owner-Password`（body 是文件流 / 没有 body 时用）。 */
    HEADER,
}

/**
 * 当前全部受主人密码门保护的端点（开关打开时生效）。
 *
 * 分四类：设备擦除、等价于执行代码、证据与授权销毁、凭据外发。
 */
val DESTRUCTIVE_ENDPOINTS: List<DestructiveEndpoint> = listOf(
    // ── 设备擦除：不可逆 ──
    DestructiveEndpoint("DeviceRoutes.kt", "post", "/factory-reset", passwordFrom = PasswordSource.BODY,
        note = "擦除设备全部配置，且会让设备回到「任何人可重新初始化」的状态",
        anchor = "POST /api/device/factory-reset"),
    DestructiveEndpoint("DeviceRoutes.kt", "post", "/goform/set", passwordFrom = PasswordSource.BODY,
        note = "不过 WriteSpec 值域校验的裸写；写错 LAN/口令后设备从网络消失，只能物理重置",
        anchor = "POST /api/device/goform/set"),
    DestructiveEndpoint("ConfigRoutes.kt", "post", "/reset", passwordFrom = PasswordSource.BODY,
        note = "恢复默认配置（默认全量），通知令牌 / 扫描目录 / 隧道设置一并清掉",
        anchor = "POST /api/config/reset"),
    DestructiveEndpoint("BackupRoutes.kt", "post", "/import", passwordFrom = PasswordSource.HEADER,
        note = "一次性写 60+ 配置键，覆盖成备份里的旧状态（body 是备份包流，凭据走头）",
        anchor = "POST /api/backup/import"),

    // ── 等价于执行代码：跑过就再也收不回 ──
    DestructiveEndpoint("ShellRoutes.kt", "post", "/exec", passwordFrom = PasswordSource.BODY,
        note = "root shell：全服务权限最高的入口",
        anchor = "POST /api/shell/exec"),
    DestructiveEndpoint("TaskRoutes.kt", "post", null, occurrence = 0, passwordFrom = PasswordSource.BODY,
        note = "POST /api/tasks：actionType=custom_shell 时是 root shell 的第二入口",
        anchor = "POST /api/tasks"),
    DestructiveEndpoint("TaskRoutes.kt", "put", "/{id}", occurrence = 0, passwordFrom = PasswordSource.BODY,
        note = "PUT /api/tasks/{id}：改任务等于改「将来要执行的 root 命令」",
        anchor = "PUT /api/tasks/{id}"),
    DestructiveEndpoint("TaskRoutes.kt", "post", null, occurrence = 1, passwordFrom = PasswordSource.BODY,
        note = "POST /api/rules：与任务共用执行器，custom_shell 同样是 root shell",
        anchor = "POST /api/rules"),
    DestructiveEndpoint("TaskRoutes.kt", "put", "/{id}", occurrence = 1, passwordFrom = PasswordSource.BODY,
        note = "PUT /api/rules/{id}：同上",
        anchor = "PUT /api/rules/{id}"),

    // ── 证据 / 授权销毁 ──
    // 内存缓冲里的日志同样是排障证据（落盘那份已经登记过了）。
    DestructiveEndpoint("DebugLogRoutes.kt", "delete", null, occurrence = 0, passwordFrom = PasswordSource.HEADER,
        note = "DELETE /api/debug-logs：清空内存日志缓冲（销毁排障证据）",
        anchor = "DELETE /api/debug-logs（内存缓冲）"),
    DestructiveEndpoint("DebugLogRoutes.kt", "delete", null, occurrence = 1, passwordFrom = PasswordSource.HEADER,
        note = "DELETE /api/debug-logs/files：连同崩溃现场一起删，不可恢复",
        anchor = "DELETE /api/debug-logs/files"),
    DestructiveEndpoint("PairedDevicesRoutes.kt", "delete", "/pairing/devices/{fingerprint}",
        passwordFrom = PasswordSource.BODY,
        note = "移除一台已配对设备 = 当场吊销它的独占 token",
        anchor = "DELETE /api/pairing/devices/{fingerprint}"),
    DestructiveEndpoint("PairedDeviceBlacklistRoutes.kt", "delete", "/pairing/blacklist/{fingerprint}",
        passwordFrom = PasswordSource.BODY,
        note = "解除单条黑名单 = 放一条已被拒绝的凭据重新尝试",
        anchor = "DELETE /api/pairing/blacklist/{fingerprint}"),
    // 2026-11-14 门禁审计补：批量解封与单条解封是**同一能力**，只拦单条等于留了一条绕行路。
    DestructiveEndpoint("PairedDeviceBlacklistRoutes.kt", "post", "/pairing/blacklist/clear",
        passwordFrom = PasswordSource.BODY,
        note = "清空黑名单 = 一次性放行所有被拒过的凭据",
        anchor = "POST /api/pairing/blacklist/clear"),
    // 2026-11-14 门禁审计补：卸载不可逆（应用数据 / 程序数据目录一并消失），与「安装」不是一类。
    // `pm clear` 清空应用数据，不可恢复；原先只要求调用方自己填的 confirm=true。
    DestructiveEndpoint("AppRoutes.kt", "post", "/{action}", passwordFrom = PasswordSource.BODY,
        note = "action=clear：清空应用全部数据，不可恢复（与 uninstall 同级）",
        anchor = "POST /api/apps/{action}（action=clear）"),
    DestructiveEndpoint("AppRoutes.kt", "post", "/uninstall", passwordFrom = PasswordSource.BODY,
        note = "卸载 APK：数据一并消失，不可恢复",
        anchor = "POST /api/apps/uninstall"),
    // 上传是唯一一条「字节来自客户端」的安装路径：配合 start 等于让持 Bearer 的人跑任意 ELF，
    // 与 /api/shell/exec 同级。**只有上传要密码**，从清单安装不需要（用户口径：装程序不打扰）。
    // 组件上传：字节来自客户端、随后 ProcessBuilder 直接执行（frpc / cloudflared），
    // 且这条路径跳过 sha256 校验。与程序上传同级，不能只拦后者。
    DestructiveEndpoint("ComponentRoutes.kt", "post", "/{id}/upload", passwordFrom = PasswordSource.HEADER,
        note = "上传任意组件二进制并可立即执行（与程序上传同级，跳过 sha256）",
        anchor = "POST /api/components/{id}/upload"),
    DestructiveEndpoint("ProgramRoutes.kt", "post", "/{id}/upload", passwordFrom = PasswordSource.HEADER,
        note = "上传任意 ARM64 ELF 并能直接启动：与 shell/exec 同级的执行能力",
        anchor = "POST /api/programs/{id}/upload"),
    DestructiveEndpoint("ProgramRoutes.kt", "post", "/{id}/uninstall", passwordFrom = PasswordSource.HEADER,
        note = "卸载第三方程序：默认连数据目录一起删（`delete_logs` 还会删日志）",
        anchor = "POST /api/programs/{id}/uninstall"),

    // ── 凭据外发：东西出了门就收不回 ──
    // 与 `/{id}/secret` 同能力（拿到/重置程序管理员口令），2026-11-14 补：原先只有 secret 有门，
    // reset-secret 谁都能调，还能把口令设成自己挑的值。
    DestructiveEndpoint("ProgramRoutes.kt", "post", "/{id}/reset-secret", passwordFrom = PasswordSource.HEADER,
        note = "重置程序管理员口令并返回新口令 = 接管该程序",
        anchor = "POST /api/programs/{id}/reset-secret"),
    // 日志常含程序输出的 token / 会话；代码注释自己写着"与初始口令同级"，那就得同样要密码。
    DestructiveEndpoint("ProgramRoutes.kt", "get", "/{id}/logs", passwordFrom = PasswordSource.HEADER,
        note = "程序日志可能含 token / 初始化口令（凭据外发）",
        anchor = "GET /api/programs/{id}/logs"),
    DestructiveEndpoint("ProgramRoutes.kt", "post", "/{id}/secret", passwordFrom = PasswordSource.HEADER,
        note = "返回程序初始口令（消费一次）；任何持有 Bearer 的客户端都能读走程序管理员凭据",
        anchor = "POST /api/programs/{id}/secret"),
    // 远端备份出口：改了目标与口令，下一次定时任务就把整包配置发到调用方指定的服务器。
    DestructiveEndpoint("BackupRoutes.kt", "put", "/remote-config", passwordFrom = PasswordSource.BODY,
        note = "指定远端备份的目标与加密口令 = 把整包配置（含 goform 口令）外发到任意服务器",
        anchor = "PUT /api/backup/remote-config"),
    DestructiveEndpoint("BackupRoutes.kt", "post", "/export", passwordFrom = PasswordSource.BODY,
        note = "备份包内含 goform_password / tunnel_cf_token；明文模式可直接读",
        anchor = "POST /api/backup/export"),
)
