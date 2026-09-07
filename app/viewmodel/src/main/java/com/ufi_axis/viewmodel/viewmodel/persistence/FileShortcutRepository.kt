package com.ufi_axis.viewmodel.persistence

import android.content.Context
import com.ufi_axis.util.AppJson
import com.ufi_axis.viewmodel.state.Bookmark
import com.ufi_axis.viewmodel.state.QuickPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * 书签 / 快捷路径的持久化仓储。
 *
 * 持久化方案：SharedPreferences（与 [com.ufi_axis.viewmodel.module.FileManagerModule]
 * 中 phoneDownloadHistory 的存储模式完全一致：`AppJson.encodeToString` /
 * `AppJson.decodeFromString` 序列化整个 `List<T>`，零新增依赖）。架构文档 §6 提到的 DataStore
 * 在本仓库无对应依赖，此处采用与既有代码同构的 SharedPreferences 实现，功能等价（重启保留）。
 *
 * 所有读写均为小 IO，统一包裹在 [withContext] + [Dispatchers.IO] 中执行；
 * 方法签名均为 `suspend`，由调用方在协程作用域内调用，避免阻塞主线程。
 * 解析做防御性 try/catch，失败一律返回空列表，保证主流程不被持久化异常阻断。
 */
class FileShortcutRepository(
    private val appContext: Context
) {
    private val prefs = appContext.getSharedPreferences("file_shortcuts", Context.MODE_PRIVATE)

    // ───────────────────────── Bookmarks ─────────────────────────

    suspend fun loadBookmarks(): List<Bookmark> = withContext(Dispatchers.IO) {
        val raw = prefs.getString("bookmarks", null) ?: return@withContext emptyList()
        try {
            AppJson.decodeFromString<List<Bookmark>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveBookmarks(list: List<Bookmark>) = withContext(Dispatchers.IO) {
        try {
            prefs.edit().putString("bookmarks", AppJson.encodeToString(ListSerializer(Bookmark.serializer()), list)).apply()
        } catch (e: Exception) {
            // 序列化 / 落盘失败仅丢弃，不影响主流程
        }
    }

    // ───────────────────────── QuickPaths ─────────────────────────

    suspend fun loadQuickPaths(): List<QuickPath> = withContext(Dispatchers.IO) {
        val raw = prefs.getString("quick_paths", null) ?: return@withContext emptyList()
        try {
            AppJson.decodeFromString<List<QuickPath>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveQuickPaths(list: List<QuickPath>) = withContext(Dispatchers.IO) {
        try {
            prefs.edit().putString("quick_paths", AppJson.encodeToString(ListSerializer(QuickPath.serializer()), list)).apply()
        } catch (e: Exception) {
            // 序列化 / 落盘失败仅丢弃，不影响主流程
        }
    }
}
