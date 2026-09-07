package com.ufi_axis.viewmodel.module

import android.content.Context
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.AppActionRequest
import com.ufi_axis.data.model.AppInstallRequest
import com.ufi_axis.data.model.AppInstallUrlRequest
import com.ufi_axis.viewmodel.state.AppManageState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppManagerModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(AppManageState())
    val state: StateFlow<AppManageState> = _state.asStateFlow()

    /**
     * 当前 `apps` 里那份列表**成功落地时用的 filter**（`null` = 本进程还没成功读到过任何列表）。
     *
     * 与下载页的 `downloadsSuccessElapsed == 0L` 是同一条口径（"从未成功加载过"），
     * 只是本方法带 [filter] 形参，所以记的是"成功过的那个 filter"而不是时刻。
     */
    @Volatile private var loadedFilter: String? = null

    /**
     * 读应用列表（`GET /api/apps?filter=…`）。
     *
     * ## 只有「没有可显示的列表」时才写 loading 态（2026-09-05）
     * `AppScreen:109` 的 `if (state.isLoading) UfiSkeletonGroup(...)` 会把**整块内容区**
     * （筛选栏以下全部）换成骨架 —— 与列表 / 空态是互斥子树，翻一次 `isLoading` 就是一次
     * 硬子树替换。判据 [loadedFilter] `!= filter` 一条同时覆盖必须遮住的两种情况：
     * - `loadedFilter == null`：本进程从未成功读到过 ⇒ 骨架屏必须保留（设备上几百个包，
     *   `pm list` + 逐包取 label 要好几秒，此时没有骨架屏页面就是全空的）；
     * - `loadedFilter != filter`：**切筛选页签**。现有 `apps` 属于上一个 filter，
     *   不遮住它就会出现"点了系统应用、却继续列着用户应用好几秒"，比闪一下更糟。
     *
     * 判据刻意**不是** `apps.isEmpty()`：那样"该 filter 下确实一个应用都没有"（例如设备上
     * 没有第三方应用、或某个筛选结果为空）会让每一次进页面 / 手动刷新 / 写后回读都重新写
     * `isLoading = true` ⇒ `UfiEmptyState("未找到应用")` 被骨架顶掉再挂回来 = **空态闪一下**。
     * 这与下载页"进页面时空态图标和描述闪一下、添加任务后就正常"是同一个 bug 形态
     * （见 `DownloadModule.loadDownloads`）。列表非空时本来就不会闪，所以只有空结果暴露它。
     *
     * 被排除掉的还有"白付"的那两条：**顶栏手动刷新**与 `performAppAction` /
     * 安装成功后的**写后回读** —— 同一 filter 且已成功加载过，翻 `isLoading` 只会把内容区
     * 整棵换成骨架再换回来。现在是原地更新。
     *
     * ## 为什么**不**加新鲜度闸门
     * 应用集合是设备侧可变量（安装/卸载/冻结/停用随时发生，还可能由 core 之外的途径改），
     * 「进页面就该是最新的」是这一页的产品前提。写后回读同理必须真打请求。
     */
    fun loadAppList(filter: String = "user") {
        scope.launch {
            val needSkeleton = loadedFilter != filter
            _state.value = _state.value.copy(
                // 结构相等时 MutableStateFlow 不发射（AppManageState 是 data class），
                // 所以"同一 filter + 已成功加载过 + 无错误"这条常见路径在这里是零重组。
                isLoading = if (needSkeleton) true else _state.value.isLoading,
                filter = filter,
                errorMessage = null
            )
            try {
                val resp = api.getAppList(filter)
                // 只在成功落地后记 filter，失败不记 —— 否则下一次进来会以为"已经有列表了"，
                // 空着的内容区连骨架都不给。
                loadedFilter = filter
                _state.value = _state.value.copy(apps = resp.apps.sortedByDescending { it.isFrozen || !it.isEnabled }, hasRoot = resp.root, isLoading = false)
            } catch (e: Exception) { _state.value = _state.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun loadAppDetail(packageName: String) {
        scope.launch {
            try { _state.value = _state.value.copy(selectedApp = api.getAppDetail(packageName)) }
            catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "获取详情失败: ${e.message}") }
        }
    }

    fun dismissAppDetail() { _state.value = _state.value.copy(selectedApp = null) }

    fun performAppAction(action: String, packageName: String) {
        scope.launch {
            try {
                when (action) {
                    "uninstall" -> api.uninstallApp(AppActionRequest(packageName))
                    "disable" -> api.disableApp(AppActionRequest(packageName))
                    "enable" -> api.enableApp(AppActionRequest(packageName))
                    "clear" -> api.clearAppData(AppActionRequest(packageName))
                    "force-stop" -> api.forceStopApp(AppActionRequest(packageName))
                    "freeze" -> api.freezeApp(AppActionRequest(packageName))
                    "unfreeze" -> api.unfreezeApp(AppActionRequest(packageName))
                }
                _state.value = _state.value.copy(selectedApp = null, errorMessage = null)
                loadAppList(_state.value.filter)
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    /**
     * 一次授予该应用**全部**已声明的运行时权限（`POST /api/apps/grant-all-permissions`）。
     *
     * 为什么不塞进 [performAppAction]：那些动作都是「成功即关详情页 + 重拉列表」的同构操作，
     * 而这一个是**高风险且不可撤销**的 —— 调用方必须先弹红色确认弹窗，成功后还要把 core 给的
     * 「授予了几项」原文回显给用户。所以走 suspend 返回结果，而不是写进 State。
     *
     * 失败时 core 回 **HTTP 500**，`message` 是 shell 原始报错（可能是一长串英文），
     * 所以这里只把它当补充信息带回去，人话提示由 UI 自己兜。
     *
     * @return 成功 = true to core 的 message；失败 = false to 失败原因
     */
    suspend fun grantAllPermissions(packageName: String): Pair<Boolean, String> = try {
        val resp = api.grantAllAppPermissions(AppActionRequest(packageName))
        resp.success to resp.message
    } catch (e: Exception) {
        false to (e.message ?: "授予失败")
    }

    fun installAppFromUrl(url: String) {
        scope.launch {
            _state.value = _state.value.copy(installLoading = true)
            try {
                val resp = api.installAppFromUrl(AppInstallUrlRequest(url))
                _state.value = _state.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_state.value.filter)
            } catch (e: Exception) { _state.value = _state.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }

    fun installAppFromPath(path: String) {
        scope.launch {
            _state.value = _state.value.copy(installLoading = true)
            try {
                val resp = api.installApp(AppInstallRequest(path))
                _state.value = _state.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_state.value.filter)
            } catch (e: Exception) { _state.value = _state.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }
}