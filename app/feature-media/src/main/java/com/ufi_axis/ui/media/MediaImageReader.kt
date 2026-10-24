package com.ufi_axis.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 图片编辑用的解码器（2026-10-21 从 `MediaImageEditor.kt` 抽出来）。
 *
 * ## 三件事
 * 1. **带鉴权取流**：走播放器那条共用的 OkHttp（设备签名拦截器），与灯箱取原图同一条链路；
 * 2. **降采样**：编辑过程的每一步都要在内存里多一张位图，原始照片 4000×3000 直接玩不起。
 *    长边压到 [EDIT_MAX_EDGE_PX]；需要原画质的场景本来也不该在手机上编辑；
 * 3. **EXIF 转正**（本轮新增的 bug 修复）：手机竖拍的照片像素其实是横的、靠 EXIF 的
 *    Orientation 标记"显示时转 90°"。`BitmapFactory` **不会**自动应用它 —— 不处理的话，
 *    这类照片在编辑器里就是歪的（而且导出的成品也歪）。
 */
internal const val EDIT_MAX_EDGE_PX = 2560

internal suspend fun loadEditableBitmap(context: Context, url: String): Bitmap? =
    withContext(Dispatchers.IO) { decodeWithExif(context, url) }

private fun decodeWithExif(context: Context, url: String): Bitmap? = runCatching {
    val client = UfiStreamHttpClient.get(context)
    val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) return null
        resp.body?.bytes()
    } ?: return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > EDIT_MAX_EDGE_PX) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null

    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    orientationMatrixOf(orientation)?.let { matrix ->
        runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrNull()?.let { rotated ->
            if (rotated !== decoded) decoded.recycle()
            return rotated
        }
    }
    decoded
}.getOrNull()

/** EXIF Orientation → 需要的变换矩阵（null = 不用转）。 */
private fun orientationMatrixOf(orientation: Int): Matrix? = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
    ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
    ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
    ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
        postRotate(90f)
        postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
        postRotate(270f)
        postScale(-1f, 1f)
    }
    else -> null
}
