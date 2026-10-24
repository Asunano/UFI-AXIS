package com.ufi_axis.ui.screens

/**
 * 服务器 / 设备后台地址的**离线校验**（2026-10-14）。
 *
 * 背景：App 原先只认 IPv4（`192.168.0.1` 这种），而 Web 端从来只查非空
 * （`web/src/views/login/LoginView.vue:424`），所以「域名」在 Web 上一直是能用的。
 * App 这边卡在 `SetupScreen` 的手输校验上，本文件把那条校验从「只允许 IPv4」
 * 放宽成「IPv4 或主机名」。
 *
 * 为什么抽成独立文件而不是留在 `SetupScreen.kt` 里当 private：
 * 这两条判定是**纯函数**，却决定了用户能不能走进连接流程 —— 一旦漂移（比如把
 * `0..255` 的分段校验弄丢），界面照常渲染、只在真机上连不上，没有任何异常可看。
 * 抽到同包的 internal 之后，`ServerAddressTest` 能直接覆盖每一条规则的正反面。
 *
 * 调用约定：`isValidHost` 是**裸判定**，不做任何修正（首尾空格算不合法）；
 * 要"用户输入 → 可直接拼 URL 的 host/port"请走 [checkServerEndpoint]，它在**一处**
 * 做 trim 归一 + 端口范围校验，供配对页与设置页共用（两处各写一份必然漂移）。
 */

/** IPv4 地址正则（每段 1–3 位数字），配合分段数值校验（每段 0–255）。用于手动配对输入校验（UID-011, Wave 2）。 */
private val IPV4_PATTERN = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

/** 校验字符串是否为合法 IPv4 地址（每段 0–255）。 */
internal fun isValidIpv4(ip: String): Boolean {
    if (!IPV4_PATTERN.matches(ip)) return false
    return ip.split('.').all { it.toIntOrNull() in 0..255 }
}

/**
 * 单个 DNS 标签：字母/数字开头结尾，中间允许 `-`，长度 1–63（RFC 1123 的口径）。
 *
 * 为什么不用 `[A-Za-z0-9-]{1,63}` 这种"宽松版"：那样 `-bad.com` / `bad-.com`
 * 都能过校验，而它们在任何解析器里都解析不出来 —— 校验就成了摆设。
 */
private val HOST_LABEL_PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

/** 主机名总长上限（RFC 1035：253 个字符，不含结尾的根点）。 */
private const val HOST_MAX_LENGTH = 253

/**
 * 校验字符串是否为「可以拿去拼 `http://$host:$port/` 的主机」——**IPv4 或域名**。
 *
 * 规则（每条都写清为什么）：
 *  1. **纯数字+点的输入必须先是合法 IPv4**。否则 `1.2.3`、`256.1.1.1` 会被下面的
 *     标签规则当成"多标签主机名"放过去（`1` / `256` 都是合法的单标签），
 *     而它们既不是 IP、也不是任何真实域名（顶级域不会是纯数字）。
 *  2. 其余按主机名：总长 ≤ 253，至少一个标签，每个标签 1–63 字符、不以 `-` 开头或结尾，
 *     **不连续 `..`**（空标签直接判非法）。
 *  3. **允许单标签**（`nas`、`localhost`）。这是局域网里最常见的写法，也是 mDNS /
 *     hosts 文件里的合法名字；强行要求带点会把真实场景挡在门外。
 *  4. **允许大写字母**（`NAS.local`）。主机名本身大小写不敏感，OkHttp/`java.net.URL`
 *     会自己规范化；这里拒绝大写只会制造"看起来对却连不上"的假错误。
 *  5. **拒绝**空白（含首尾，故不 trim）、`/`、`:`、`_`、`[`、`]` 等字符。
 *     - `:` 与 `/` 是"地址框不该出现的东西"（端口是另一个输入框、scheme 由代码固定）；
 *     - `_` 严格说不是合法主机名（RFC 1123 只允许字母数字连字符）。虽然部分路由器
 *       /mDNS 记录里会带下划线，但那属于**服务名**而不是主机名，且真实设备后台地址
 *       （`tplogin.cn`、`router.local`、隧道域名）都不用下划线 —— 放行它等于把
 *       "能过校验但 DNS 解析不了"的东西放进来，所以拒绝。
 *  6. **不支持 IPv6**（`[::1]` / `fe80::1`）。这是刻意的取舍，不是漏掉：App 里拼 URL
 *     全是字符串模板 `"http://$host:$port/..."`（`AppPreferences.baseUrl`、
 *     `SetupScreen` 的配对端点、`WebSocketRepository` 等），IPv6 字面量在 URL 里必须
 *     带方括号 `http://[::1]:8088/`，而所有 `"$host:$port"` 的拼法都会拼成
 *     `http://::1:8088/` —— 能过校验却一定连不上。相比之下，随身 WiFi 设备、内网
 *     后台、隧道域名都用 IPv4 / 域名，所以本轮明确不支持，并在错误文案里说清楚。
 */
internal fun isValidHost(h: String): Boolean {
    if (h.isEmpty() || h.length > HOST_MAX_LENGTH) return false

    // 规则 1：长得像 IPv4 的输入必须真的合法（见上）。
    if (h.all { it.isDigit() || it == '.' }) return isValidIpv4(h)

    // 规则 2：按标签逐段校验；`split` 会把连续的 `..` 拆出空串，空串天然不匹配。
    return h.split('.').all { label -> label.length in 1..63 && HOST_LABEL_PATTERN.matches(label) }
}

/**
 * 「主机 + 端口」这一对输入的判定结果（2026-10-14）。
 *
 * 为什么要有它：配对页（`SetupScreen.connectManual`）与设置页「连接配置」
 * （`ServerConfigScreen`）各自写过一遍 `trim → isValidHost → 端口 1..65535`，
 * 两处只差文案。域名放行之后这两份规则**必须逐字一致**——一处改了另一处没改，
 * 用户就会遇到"配对时能填 nas.local、进设置里改回去却报格式错误"这种自相矛盾的界面。
 * 所以把判定收成一个纯函数，调用方只负责把 [ServerEndpoint] 的取值翻成自己的文案。
 *
 * trim 在这里做（而不是像 [isValidHost] 那样推给调用方）：这两个调用点**都**要 trim，
 * 而「粘贴时带上的首尾空格」是最常见的输入形态；把它留在调用方就等于留一处会漂移的
 * 静默修正。注意 trim 只吃首尾空白，中间的空格仍然是格式错误（`nas .local`）。
 */
internal sealed interface ServerEndpoint {
    /** 已归一（trim 过）的主机与端口，可直接拿去拼 `http://$host:$port/`。 */
    data class Valid(val host: String, val port: Int) : ServerEndpoint

    /** 主机为空（只输了空格也算空）。 */
    data object BlankHost : ServerEndpoint

    /**
     * 主机形状不对：IPv6 字面量、带 scheme / 路径 / 端口、下划线、非法字符……
     * 判定规则见 [isValidHost]。
     */
    data object InvalidHost : ServerEndpoint

    /** 端口不是 1–65535 的十进制整数（空串、非数字、越界都落这里）。 */
    data object InvalidPort : ServerEndpoint
}

/**
 * 一次性判定「地址 + 端口」。顺序固定为 空 → 主机形状 → 端口：
 * 这是用户填错时的自然顺序（先看有没有填，再看填的是什么，最后看端口），
 * 也让两个调用点的错误文案顺序保持一致。
 */
internal fun checkServerEndpoint(hostRaw: String, portRaw: String): ServerEndpoint {
    val host = hostRaw.trim()
    if (host.isEmpty()) return ServerEndpoint.BlankHost
    if (!isValidHost(host)) return ServerEndpoint.InvalidHost
    val port = portRaw.trim().toIntOrNull()
    if (port == null || port !in 1..65535) return ServerEndpoint.InvalidPort
    return ServerEndpoint.Valid(host, port)
}
