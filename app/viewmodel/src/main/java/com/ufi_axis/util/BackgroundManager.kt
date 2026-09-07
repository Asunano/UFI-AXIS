package com.ufi_axis.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class BackgroundState(
    val enabled: Boolean = false,
    val imagePath: String? = null,
    val blurRadius: Float = 20f
)

class BackgroundManager(context: Context) {
    private val prefs = context.getSharedPreferences("ufi_axis_background", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<BackgroundState> = _state.asStateFlow()

    fun updateEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
        saveState()
    }

    fun updateImagePath(path: String?) {
        _state.value = _state.value.copy(imagePath = path)
        saveState()
    }

    fun updateBlurRadius(radius: Float) {
        _state.value = _state.value.copy(blurRadius = radius.coerceIn(0f, 50f))
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
        val s = _state.value
        prefs.edit()
            .putBoolean("bg_enabled", s.enabled)
            .putString("bg_image_path", s.imagePath)
            .putFloat("bg_blur_radius", s.blurRadius)
            .apply()
    }

    fun clearBackground() {
        val oldPath = _state.value.imagePath
        _state.value = BackgroundState()
        saveState()
        oldPath?.let { File(it).delete() }
    }
}

@Composable
fun rememberBackgroundManager(): BackgroundManager {
    val context = LocalContext.current.applicationContext
    return remember { BackgroundManager(context) }
}
