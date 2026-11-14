package com.ufi_axis_core.controller.carrier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.ufi_axis_core.controller.sms.SmsCodeExtractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 等移动登录验证码短信（2026-11-01 预写）。
 *
 * 不注册 SMS_RECEIVED 广播：core 已有短信 Provider 读权限，轮询 inbox 更稳，
 * 也不依赖默认短信 App。只接受请求时间之后、发件人像 10086 的 6 位码。
 */
class CarrierSmsCodeWatcher(private val context: Context) {

    suspend fun awaitCode(since: Long, timeoutMs: Long = 90_000L): String = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw CarrierAutoLoginException("缺少 READ_SMS 权限，无法自动读取验证码")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findCode(since)?.let { return@withContext it }
            delay(700)
        }
        throw CarrierAutoLoginException("90 秒内没有收到移动验证码短信")
    }

    private fun findCode(since: Long): String? {
        val projection = arrayOf("_id", "address", "body", "date")
        // 手机与运营商时钟可能有几秒差，回溯 5 秒；再早的短信不认。
        val from = (since - 5_000L).coerceAtLeast(0)
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            "date >= ?",
            arrayOf(from.toString()),
            "date DESC",
        ) ?: return null
        cursor.use { c ->
            val addrIdx = c.getColumnIndex("address")
            val bodyIdx = c.getColumnIndex("body")
            while (c.moveToNext()) {
                val address = if (addrIdx >= 0) c.getString(addrIdx).orEmpty() else ""
                val body = if (bodyIdx >= 0) c.getString(bodyIdx).orEmpty() else ""
                if (!address.contains("10086") && !body.contains("中国移动")) continue
                val match = SmsCodeExtractor.find(body) ?: continue
                if (match.code.length == 6) return match.code
            }
        }
        return null
    }
}

class CarrierAutoLoginException(message: String) : Exception(message)
