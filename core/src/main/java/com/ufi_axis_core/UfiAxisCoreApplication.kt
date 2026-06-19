package com.ufi_axis_core

import android.app.Application
import com.ufi_axis_core.util.AppLogger
import java.io.File

class UfiAxisCoreApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 创建数据目录 (优先使用 app 私有目录，root 环境下尝试 /data/ufiaxis)
        val dataDir = tryGetRootDataDir() ?: File(filesDir, "ufiaxis")
        listOf("db", "logs", "cache", "config").forEach {
            File(dataDir, it).mkdirs()
        }

        AppLogger.init(dataDir)
        AppLogger.i("App", "UFI-AXIS-Core started, dataDir=${dataDir.absolutePath}")
    }

    private fun tryGetRootDataDir(): File? {
        return try {
            val dir = File("/data/ufiaxis")
            if (dir.exists() && dir.canWrite()) dir else null
        } catch (e: Exception) {
            null
        }
    }
}
