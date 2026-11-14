package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.api.StorageSourceInfo

// ========== External Storage Sources ==========

/**
 * 外部存储源（FTP / WebDAV）配置页的唯一状态槽。
 *
 * 与 [FileManagerState.remoteSources] 的分工：那一份是**文件管理器**为了渲染虚拟根、
 * 判定能力而缓存的"可用源"快照（只含 enabled 的），本 state 是配置页自己的全量列表
 * （含停用的，否则停用后那一行就从配置页消失、没法再打开）。两者各自拉取，
 * 不互相订阅 —— 配置页改完后文件管理器下次进页面自然会重拉。
 */
data class StorageSourceState(
    val sources: List<StorageSourceInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: StorageSourceTestResult? = null,

    /**
     * **按源归档**的最近一次连通性结果（key = sourceId）。
     *
     * 与 [testResult] 的区别：那一份是"最近一次测试"的单槽，只够给一个弹窗看；
     * 文件管理器首屏要同时给每一行挂状态标签，单槽会互相覆盖，所以另存一份 map。
     * 两份并存而不是替换 [testResult]：编辑向导里的「测未保存的表单配置」拿不到 sourceId
     * （key 为 null），只能走单槽那条路。
     */
    val testResults: Map<String, StorageSourceTestResult> = emptyMap(),

    /** 正在测的源 id 集合（首屏是串行批量测，同一时刻只会有一个，但用集合更直白）。 */
    val testingIds: Set<String> = emptySet(),

    /**
     * 上一轮批量测连的完成时刻（`elapsedRealtime`）。
     *
     * 用来做**节流**：进出文件管理器很频繁，每次都对所有远端服务器建连是实打实的开销
     * （FTP/SMB 握手能到秒级，还可能触发对方的连接频率限制），所以窗口内直接沿用上次结果。
     */
    val testedAtElapsed: Long = 0L,

    val errorMessage: String? = null,
    val operationMessage: String? = null
)

/**
 * 一次连通性测试的结果。
 *
 * @param sourceId 被测的已保存源 id；`null` = 测的是「还没保存的表单配置」
 *   （编辑弹窗里的「测试连接」）。UI 靠这个字段区分结果该显示在列表行上还是弹窗里。
 * @param latencyMs core 报告的往返耗时；失败时通常是 0，不要拿它当"成功"的判据。
 */
data class StorageSourceTestResult(
    val sourceId: String?,
    val success: Boolean,
    val message: String,
    val latencyMs: Long
)
