package com.ufi_axis

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.ufi_axis.BuildConfig
import com.ufi_axis.crash.CrashHandler
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.util.ApiErrorLogger
import com.ufi_axis.util.AppFileLogger
import com.ufi_axis.util.AppHttpClient
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.OkHttpClientProvider

class UfiAxisApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        // 尽早安装崩溃日志处理器，覆盖 Activity 创建阶段及之后的所有未捕获异常
        CrashHandler.install(this)

        // 日志开关启动同步：DebugLog 的开关默认值和持久化真源并不一致。
        // masterEnabled 是总闸、appEnabled 是 app 侧子开关、enabled 是详细日志（DEBUG/INFO）。
        // 少了这一步，冷启动后不管用户关没关，d/i/json 都会照打并写满内存缓冲，
        // 直到用户手动碰一次开关或 refreshDeviceConfig() 从 core 回读配置才纠正。
        val prefs = AppPreferences(this)
        DebugLog.masterEnabled = prefs.logEnabled
        DebugLog.appEnabled = prefs.appLogEnabled
        DebugLog.enabled = prefs.debugMode

        // 初始化通信报错日志落盘（Download/UFI-AXIS/log），用于诊断「服务无法链接」
        ApiErrorLogger.init(this)

        // APP 侧日志落盘（Download/UFI-AXIS/log/app/<日期>/）：以前只有内存 500 条，
        // 杀进程即丢。放在开关同步之后，init 里不会写内容、只建目录 + 清过期。
        AppFileLogger.init(this)

        cleanStaleUpdateApks()

        // 初始化通用 HTTP 客户端（自动 Bearer 鉴权 + 401 重配对），供下载/文件模块复用
        AppHttpClient.init(this)

        // 2026-08-25: 启动独立通知保活服务
        // onCreate 在每个进程都会执行，:ufi_notify 自身无需再拉自己；
        // 是否真的启动由 NotifyService.shouldRun 决定（只看「前台服务保活」这一个开关 ——
        // 2026-09-05 前这里看的是「系统通知推送」，于是用户没开保活也会有常驻通知）。
        if (isMainProcess()) {
            com.ufi_axis.notification.NotifyService.startIfEnabled(this)
        }



        // 可观测性：仅 debug 构建开启 StrictMode，便于发现主线程 IO / 资源泄漏（release 不开启以免影响性能）
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    /** 子进程（`:ufi_notify`）的 processName 形如 `com.xxx:ufi_notify`，主进程不含冒号。 */
    private fun isMainProcess(): Boolean {
        val name = Application.getProcessName()
        return name.isEmpty() || !name.contains(":")
    }

    /**
     * 清理过期的 App 更新包（`filesDir/update/`）。
     *
     * 系统安装器装完不会通知我们删源文件，`app-update.apk`（约 60MB）装完就一直留着。
     * 超过 24h 的一律删：那时它要么已经装上了、要么用户早就放弃了这次更新；
     * 未超时的保留，`downloadFrontendApk()` 的 SHA-256 缓存复用还需要它（避免重复下载）。
     * `.part` 无条件删——半截的下载没有任何复用价值。
     */
    private fun cleanStaleUpdateApks() {
        runCatching {
            val dir = java.io.File(filesDir, "update")
            if (!dir.isDirectory) return@runCatching
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val stale = f.name.endsWith(".part") || f.lastModified() < cutoff
                if (stale) {
                    val size = f.length()
                    if (f.delete()) Log.i("UfiAxisApplication", "清理过期更新包 ${f.name} (${size / 1024}KB)")
                }
            }
        }
    }


    /**
     * 全局唯一 ImageLoader：所有 AsyncImage 默认复用，避免各页面自建实例导致多套缓存。

     * 鉴权走 [RetrofitClient.authInterceptor]（与 Retrofit / AppHttpClient 同一条实现）：
     * core 的 AuthMiddleware 对 /api 请求**强制**校验 X-Timestamp / X-Nonce / X-Signature，
     * 只带 Bearer 的图片请求会被判 444，缩略图与图片预览全部加载失败。
     */
    override fun newImageLoader(context: Context): ImageLoader {
        // 复用共享单例 OkHttpClient（保留长超时与 DEBUG 日志），叠加统一鉴权拦截器
        val okHttpClient = OkHttpClientProvider.shared.newBuilder()
            .addInterceptor(RetrofitClient.authInterceptor { AppPreferences(this@UfiAxisApplication) })
            .build()

        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(okHttpClient)) }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .build()
    }

    /**
     * WorkManager 按需初始化配置（多进程修复）。
     *
     * 通知守护服务 [com.ufi_axis.notification.NotifyService] 运行在独立进程 `:ufi_notify`，
     * 该进程内默认的 WorkManagerInitializer 未生效，导致首次调用 `WorkManager.getInstance()`
     * 抛出 "WorkManager is not initialized properly"。实现 [Configuration.Provider] 后，
     * 任意进程（含 `:ufi_notify`）在调用 getInstance() 时都会据此配置自动初始化。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()
}
