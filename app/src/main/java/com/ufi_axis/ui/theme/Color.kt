package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color

// === Legacy color constants (kept for backward compatibility) ===
// New code should use LocalResolvedPalette.current.* instead

// Monochrome accent palette
val AccentLight = Color(0xFF222222)
val AccentDark = Color(0xFF888888)
val AccentContainerLight = Color(0xFFF0F0F0)
val AccentContainerDark = Color(0xFF2C2C2C)

// Signal strength colors (domain-specific, not theme-dependent)
val SignalExcellent = Color(0xFF2E7D32)
val SignalGood = Color(0xFF689F38)
val SignalFair = Color(0xFFFFA000)
val SignalPoor = Color(0xFFE65100)
val SignalDead = Color(0xFFC62828)

// Network type colors (domain-specific)
val Network5G = Color(0xFF7C4DFF)
val Network4G = Color(0xFF1565C0)
val Network3G = Color(0xFFEF6C00)
val Network2G = Color(0xFF616161)

// Battery level colors (domain-specific)
val BatteryHigh = Color(0xFF2E7D32)
val BatteryMedium = Color(0xFFFFA000)
val BatteryLow = Color(0xFFC62828)

// Traffic direction colors (domain-specific)
val TrafficDown = Color(0xFF1565C0)
val TrafficUp = Color(0xFFE65100)

// Page backgrounds (now derived from palette)
val PageBackgroundLight = Color(0xFFF5F5F7)
val PageBackgroundDark = Color(0xFF111318)

// Card surfaces (now derived from palette)
val CardSurfaceLight = Color(0xFFFFFFFF)
val CardSurfaceDark = Color(0xFF1E2028)

// Subtle dividers (now derived from palette)
val DividerLight = Color(0xFFE5E5EA)
val DividerDark = Color(0xFF2C2E35)
