package com.ufi_axis.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.File

data class BackgroundState(
    val enabled: Boolean = false,
    val imagePath: String? = null,
    val blurRadius: Float = 20f
)

class BackgroundManager(context: Context) {
    private val prefs = context.getSharedPreferences("ufi_axis_background", Context.MODE_PRIVATE)

    var state by mutableStateOf(loadState())
        private set

    fun updateEnabled(enabled: Boolean) {
        state = state.copy(enabled = enabled)
        saveState()
    }

    fun updateImagePath(path: String?) {
        state = state.copy(imagePath = path)
        saveState()
    }

    fun updateBlurRadius(radius: Float) {
        state = state.copy(blurRadius = radius.coerceIn(0f, 50f))
        saveState()
    }

    private fun loadState(): BackgroundState {
        return BackgroundState(
            enabled = prefs.getBoolean("bg_enabled", false),
            imagePath = prefs.getString("bg_image_path", null),
            blurRadius = prefs.getFloat("bg_blur_radius", 20f)
        )
    }

    private fun saveState() {
        prefs.edit()
            .putBoolean("bg_enabled", state.enabled)
            .putString("bg_image_path", state.imagePath)
            .putFloat("bg_blur_radius", state.blurRadius)
            .apply()
    }

    fun clearBackground() {
        state = BackgroundState()
        saveState()
        state.imagePath?.let { File(it).delete() }
    }
}

@Composable
fun rememberBackgroundManager(): BackgroundManager {
    val context = LocalContext.current.applicationContext
    return remember { BackgroundManager(context) }
}
