package com.ufi_axis.viewmodel.module

import android.os.SystemClock
import com.ufi_axis.data.api.StorageSourceRequest
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.state.StorageSourceState
import com.ufi_axis.viewmodel.state.StorageSourceTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外部存储源（FTP / WebDAV）配置模块。
 *
 * 只管 `/api/storage/sources` 这一组 CRUD + 试连：**列目录与读写不在这里**，
 * 它们走 [FileManagerModule]（远端路径写成 `remote:<sourceId>/…`，core 自己派发）。
 * 这样分是因为"配哪些源"和"在源里翻文件"是两件事，共用一个 state 会让文件管理器
 * 的每次目录切换都拖着一份配置列表重组。
 *
 * 写 state 一律 `_state.update { it.copy(...) }`（与 [FileManagerModule] 同一口径）：
 * 试连和增删改可能并发在飞，读-改-写非原子会把先回来的那次结果吞掉。
 */
class StorageSourceModule(
    private val api: UfiAxisApi,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(StorageSourceState())
    val state: StateFlow<StorageSourceState> = _state.asStateFlow()

    /**
     * 拉取全量源列表（**含停用的** —— 配置页要能打开被停用的那一行）。
     *
     * @param silent true 时不写 `isLoading`。用于增删改之后的回读：那时列表已经在屏上，
     *   再闪一次骨架/转圈只会让刚做完的操作看起来像失败重试。
     */
    fun loadSources(silent: Boolean = false) {
        scope.launch {
            if (!silent) _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val resp = withContext(Dispatchers.IO) { api.listStorageSources() }
                _state.update { it.copy(sources = resp.sources, isLoading = false, errorMessage = null) }
            } catch (e: Exception) {
                DebugLog.w("StorageSource", "加载存储源列表失败", e)
                _state.update { it.copy(isLoading = false, errorMessage = "加载存储源失败: ${e.message}") }
            }
        }
    }

    /**
     * 新增一个源。
     *
     * @param onDone (成功?, 给用户看的文案)。回调而不是写进 `operationMessage`：
     *   调用方是弹窗，成功要关窗、失败要留在窗里改，这个分支只有调用方知道。
     */
    fun addSource(request: StorageSourceRequest, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.addStorageSource(request) }
                if (resp.success) {
                    loadSources(silent = true)
                    onDone(true, "已添加 ${request.label}")
                } else {
                    onDone(false, resp.message ?: "设备端拒绝了添加请求")
                }
            } catch (e: Exception) {
                DebugLog.w("StorageSource", "添加存储源失败", e)
                _state.update { it.copy(errorMessage = "添加失败: ${e.message}") }
                onDone(false, "添加失败: ${e.message}")
            }
        }
    }

    /**
     * 改一个源的配置。
     *
     * 密码沿用原值时 [request] 的 `password` 必须是 `"********"`（core 侧约定）——
     * 传空串会被当成"把密码清空"。这个判断在 UI 层做（编辑弹窗留空即沿用）。
     */
    fun updateSource(id: String, request: StorageSourceRequest, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.updateStorageSource(id, request) }
                if (resp.success) {
                    loadSources(silent = true)
                    onDone(true, "已保存 ${request.label}")
                } else {
                    onDone(false, "设备端拒绝了保存请求")
                }
            } catch (e: Exception) {
                DebugLog.w("StorageSource", "保存存储源失败: $id", e)
                _state.update { it.copy(errorMessage = "保存失败: ${e.message}") }
                onDone(false, "保存失败: ${e.message}")
            }
        }
    }

    fun deleteSource(id: String, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.deleteStorageSource(id) }
                if (resp.success) {
                    loadSources(silent = true)
                    onDone(true, "已删除")
                } else {
                    onDone(false, "设备端拒绝了删除请求")
                }
            } catch (e: Exception) {
                DebugLog.w("StorageSource", "删除存储源失败: $id", e)
                _state.update { it.copy(errorMessage = "删除失败: ${e.message}") }
                onDone(false, "删除失败: ${e.message}")
            }
        }
    }

    /** 测已保存的源（用设备端存的那份密码）。 */
    fun testSource(id: String) {
        scope.launch {
            _state.update { it.copy(isTesting = true, testResult = null, testingIds = it.testingIds + id) }
            val result = runTest(id)
            _state.update {
                it.copy(
                    isTesting = false,
                    testResult = result,
                    testResults = it.testResults + (id to result),
                    testingIds = it.testingIds - id
                )
            }
        }
    }

    /**
     * 把一批源**串行**测一遍，结果按 id 归档进 [StorageSourceState.testResults]。
     *
     * ## 为什么串行
     * 每个 test 在 core 侧都是一次真实握手（FTP 三次往返、SMB 协商、S3 签名请求）。
     * 并发打过去会让 core 的 QoS 闸门开始排队甚至拒绝，反而更慢；串行的总耗时对
     * 两三个源来说完全可接受，而且"一行一行亮起来"的观感比"全部卡住然后一起出现"更好。
     *
     * ## 为什么有节流
     * 文件管理器的进出非常频繁（返回、切页、从编辑页回来都会触发）。不节流就等于
     * 每次进页面都对所有远端服务器建一次连 —— 那是别人机器上的真实负载，还可能撞上
     * 连接频率限制被临时封禁。窗口内直接沿用上一轮结果。
     *
     * @param force 忽略节流（用户手动点「全部测试」时用）
     */
    fun testAllSources(ids: List<String>, force: Boolean = false) {
        if (ids.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val last = _state.value.testedAtElapsed
        // 时钟回拨（last > now）时也重测：宁可多测一次，也不要因为时间异常永久沿用旧结果
        if (!force && last > 0L && last <= now && now - last < TEST_ALL_THROTTLE_MS) return
        if (_state.value.testingIds.isNotEmpty()) return // 上一批还没测完
        scope.launch {
            _state.update { it.copy(testingIds = ids.toSet()) }
            for (id in ids) {
                val result = runTest(id)
                _state.update {
                    it.copy(
                        testResults = it.testResults + (id to result),
                        testingIds = it.testingIds - id
                    )
                }
            }
            _state.update { it.copy(testedAtElapsed = SystemClock.elapsedRealtime()) }
        }
    }

    /** 单次试连。异常也翻成一个 failure 结果 —— 调用方只关心"通不通 + 为什么"。 */
    private suspend fun runTest(id: String): StorageSourceTestResult = try {
        val resp = withContext(Dispatchers.IO) { api.testStorageSource(id) }
        StorageSourceTestResult(id, resp.success, resp.message, resp.latencyMs)
    } catch (e: Exception) {
        DebugLog.w("StorageSource", "测试存储源失败: $id", e)
        StorageSourceTestResult(id, false, "测试失败: ${e.message ?: e.javaClass.simpleName}", 0)
    }

    /** 测还没保存的表单配置（`sourceId = null`），供「先测通再保存」。 */
    fun testConfig(request: StorageSourceRequest) {
        scope.launch {
            _state.update { it.copy(isTesting = true, testResult = null) }
            try {
                val resp = withContext(Dispatchers.IO) { api.testStorageSourceConfig(request) }
                _state.update {
                    it.copy(
                        isTesting = false,
                        testResult = StorageSourceTestResult(null, resp.success, resp.message, resp.latencyMs)
                    )
                }
            } catch (e: Exception) {
                DebugLog.w("StorageSource", "测试存储源配置失败", e)
                _state.update {
                    it.copy(
                        isTesting = false,
                        testResult = StorageSourceTestResult(null, false, "测试失败: ${e.message}", 0)
                    )
                }
            }
        }
    }

    fun clearTestResult() { _state.update { it.copy(testResult = null) } }

    fun clearMessages() { _state.update { it.copy(errorMessage = null, operationMessage = null) } }

    private companion object {
        /**
         * 批量测连的节流窗口。
         *
         * 60s 的取值：远端源的可达性不会秒级变化，而进出文件管理器可能是秒级的；
         * 这个窗口既能让"刚拔网线"在一分钟内被发现，也不会把别人的服务器当压测目标。
         */
        const val TEST_ALL_THROTTLE_MS = 60_000L
    }
}
