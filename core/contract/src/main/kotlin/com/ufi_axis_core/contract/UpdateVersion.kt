package com.ufi_axis_core.contract

/**
 * 更新判定：**版本名优先，名字相同再看 versionCode**。
 *
 * ## 为什么要看 versionCode（2026-11-15 线上问题）
 *
 * 只比版本名时，"同名重发"完全检测不到：v0.1.1 因为带上了导航修复，把内部 versionCode
 * 从 5 抬到 6 重新出包，已安装 0.1.1(5) 的用户点「检查更新」永远显示「已是最新」——
 * 版本名都是 `0.1.1`，比较结果恒为 0。
 *
 * ## 为什么名字优先、而不是 code 优先
 *
 * 两者可能互相矛盾（清单写 0.1.2 但 code 反而更小，或反之）。此时以**版本名**为准：
 * 它对用户可见、也是发布流程的 tag 依据；用 code 覆盖会放行"名字降级"的包。
 * 只有名字完全相同时，才用 code 区分「同版本的第几次构建」。
 *
 * ## 不降级
 *
 * 名字更旧 → 一律不提示，即使 code 更大（防止回滚包被当成更新推给用户）。
 *
 * @param latestName 清单里的版本名（`frontend.version`）
 * @param latestCode 清单里的 versionCode（`frontend.versionCode`；旧清单可能没有 → 0）
 * @param currentName 本机版本名（PackageManager 读取）
 * @param currentCode 本机 versionCode（PackageManager 读取）
 */
fun hasUpdate(
    latestName: String,
    latestCode: Int,
    currentName: String,
    currentCode: Int,
): Boolean {
    // 任一为空都不可信：空串解析成 [] → 全按 0 → 恒判"有新版"，会让用户反复重装同一个包
    if (latestName.isBlank() || currentName.isBlank()) return false

    val byName = compareVersionNames(latestName, currentName)
    if (byName != 0) return byName > 0

    // 名字相同：只有两边都拿得到 code 时才用它判"同名重发"
    if (latestCode <= 0 || currentCode <= 0) return false
    return latestCode > currentCode
}

/**
 * 版本名比较：返回 >0 表示 [a] 新，<0 表示旧，0 表示等价。
 *
 * 解析规则与 core 的 `UpdateManager.compareVersions` 保持一致 —— 按 `.`/`-`/`_`/`+`
 * 分段、每段取前导数字（`v1.2` 与 `1.2` 等价）。**不能**用
 * `split(".").mapNotNull { toIntOrNull() }`：那会把非数字段**丢弃**而不是当 0，
 * 于是 `v1.2` 变成 `[2]`、与本机 `1.2`→`[1,2]` 比较时判成"有新版"。
 */
fun compareVersionNames(a: String, b: String): Int {
    fun parse(v: String): List<Int> = v.trim()
        .removePrefix("v").removePrefix("V")
        .split('.', '-', '_', '+')
        .map { seg -> seg.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    val pa = parse(a)
    val pb = parse(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}
