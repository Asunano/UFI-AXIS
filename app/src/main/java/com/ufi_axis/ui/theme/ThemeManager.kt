package com.ufi_axis.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages theme selection and persistence.
 * Stores the selected theme ID in SharedPreferences via the existing prefs file.
 */
class ThemeManager(context: Context) {

    private val prefs = context.getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE)

    private val _selectedThemeId = MutableStateFlow(
        prefs.getString(KEY_THEME_ID, "default") ?: "default"
    )
    val selectedThemeId: StateFlow<String> = _selectedThemeId.asStateFlow()

    private val _customAccentColor = MutableStateFlow(
        prefs.getLong(KEY_CUSTOM_ACCENT, 0L).takeIf { it != 0L }
    )
    val customAccentColor: StateFlow<Long?> = _customAccentColor.asStateFlow()

    private val _dynamicEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_DYNAMIC_ENABLED, false)
    )
    val dynamicEnabled: StateFlow<Boolean> = _dynamicEnabled.asStateFlow()

    /**
     * Set theme by preset ID. Use "dynamic" for Material You, "custom" for custom accent.
     */
    fun setTheme(id: String) {
        _selectedThemeId.value = id
        _dynamicEnabled.value = (id == "dynamic")
        prefs.edit()
            .putString(KEY_THEME_ID, id)
            .putBoolean(KEY_DYNAMIC_ENABLED, id == "dynamic")
            .apply()
    }

    /**
     * Set a custom accent color (for "custom" theme mode).
     */
    fun setCustomAccent(color: Long) {
        _customAccentColor.value = color
        _selectedThemeId.value = "custom"
        prefs.edit()
            .putLong(KEY_CUSTOM_ACCENT, color)
            .putString(KEY_THEME_ID, "custom")
            .apply()
    }

    /**
     * Enable Material You dynamic theming.
     */
    fun enableDynamic() {
        setTheme("dynamic")
    }

    /**
     * Get the current ThemePalette based on persisted state.
     */
    fun getCurrentPalette(): ThemePalette {
        val id = _selectedThemeId.value
        return when {
            id == "dynamic" -> ThemePresets.Default // dynamic palette built at composition time
            id == "custom" -> {
                val colorLong = _customAccentColor.value ?: return ThemePresets.Default
                val color = androidx.compose.ui.graphics.Color(colorLong.toULong() or 0xFF000000u)
                buildCustomPalette(color)
            }
            else -> ThemePresets.findById(id)
        }
    }

    companion object {
        private const val KEY_THEME_ID = "ufi_theme_id"
        private const val KEY_CUSTOM_ACCENT = "ufi_custom_accent"
        private const val KEY_DYNAMIC_ENABLED = "ufi_dynamic_enabled"
    }
}
