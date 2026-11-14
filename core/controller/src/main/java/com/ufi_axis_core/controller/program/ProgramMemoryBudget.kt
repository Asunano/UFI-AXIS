package com.ufi_axis_core.controller.program

/**
 * 多个程序共享一台 1.5GB 设备时的**内存总量预算**。
 *
 * ## 为什么单个程序的阈值不够
 *
 * 每个清单里有 `rss_warn/restart/kill_mb`，它们是**按程序**的：三个程序各自 400MB 都"没超限"，
 * 加起来 1.2GB 会把 1.4GB 的机器连同 core 一起压死（LMK 先杀 core，用户看到的是"服务自己没了"）。
 * 多实例之前这个问题不存在（同一时刻只有一个），所以预算是随"允许并存"一起补的。
 *
 * ## 预算怎么取
 *
 * 取总内存的 55%：低于一半时程序几乎没法用（OpenList 空载就 80MB 起），高于 70% 时
 * core 自己的数据库/日志与系统缓存没有余量。上限夹到 6144MB，避免在 16GB 的方舟上
 * 变成"什么都放得下"之外的另一种失控。
 *
 * ## 为什么"淘汰 RSS 最大的那个"
 *
 * 预算超了必须减掉一些东西，而这里没有"哪个程序更重要"的信息。选最大的那个：
 * 一次释放最多内存、对用户来说也最容易理解（"最占内存的被停了"）。
 * 停完之后如果还超，下一轮看护会继续淘汰 —— 渐进式收敛，不一次性杀光。
 */
object ProgramMemoryBudget {

    /** 未拿到总内存时的保守预算（MB）。 */
    const val FALLBACK_BUDGET_MB = 512L

    const val MIN_BUDGET_MB = 384L
    const val MAX_BUDGET_MB = 6144L

    /** 预算占物理内存的比例。 */
    const val RATIO = 0.55

    fun budgetMb(totalMemMb: Long?): Long {
        val total = totalMemMb ?: return FALLBACK_BUDGET_MB
        if (total <= 0) return FALLBACK_BUDGET_MB
        val raw = (total * RATIO).toLong()
        return raw.coerceIn(MIN_BUDGET_MB, MAX_BUDGET_MB)
    }

    /**
     * 挑一个被淘汰的程序：RSS 最大的那个。
     *
     * @param rssMb id → 整棵进程树的 RSS
     * @return 没有可淘汰对象时返回 null（空表、所有值都 ≤0）
     */
    fun pickVictim(rssMb: Map<String, Long>): String? =
        rssMb.entries
            .filter { it.value > 0 }
            .maxByOrNull { it.value }
            ?.key
}
