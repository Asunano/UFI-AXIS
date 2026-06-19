package com.ufi_axis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.*

fun signalColor(rsrp: Int?): androidx.compose.ui.graphics.Color = when {
    rsrp == null -> SignalDead
    rsrp >= -80 -> SignalExcellent
    rsrp >= -90 -> SignalGood
    rsrp >= -100 -> SignalFair
    rsrp >= -115 -> SignalPoor
    else -> SignalDead
}

fun signalBars(rsrp: Int?): Int = when {
    rsrp == null -> 0
    rsrp >= -80 -> 5
    rsrp >= -90 -> 4
    rsrp >= -100 -> 3
    rsrp >= -115 -> 2
    else -> 1
}

fun signalLevelText(rsrp: Int?): String = when {
    rsrp == null -> "未知"
    rsrp >= -80 -> "极好"
    rsrp >= -90 -> "好"
    rsrp >= -100 -> "一般"
    rsrp >= -115 -> "差"
    else -> "极差"
}

fun networkTypeColor(networkType: String?): androidx.compose.ui.graphics.Color = when {
    networkType == null -> androidx.compose.ui.graphics.Color.Gray
    networkType.contains("5G", ignoreCase = true) -> Network5G
    networkType.contains("NR", ignoreCase = true) -> Network5G
    networkType.contains("4G", ignoreCase = true) -> Network4G
    networkType.contains("LTE", ignoreCase = true) -> Network4G
    networkType.contains("3G", ignoreCase = true) -> Network3G
    networkType.contains("WCDMA", ignoreCase = true) -> Network3G
    else -> Network2G
}

fun batteryColor(percent: Int): androidx.compose.ui.graphics.Color = when {
    percent >= 60 -> BatteryHigh
    percent >= 20 -> BatteryMedium
    else -> BatteryLow
}

@Composable
fun SignalBars(
    rsrp: Int?,
    modifier: Modifier = Modifier
) {
    val bars = signalBars(rsrp)
    val color = signalColor(rsrp)
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        for (i in 1..5) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height((i * 6).dp)
                    .padding(horizontal = 1.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (i <= bars) color else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall
                ) {}
            }
        }
    }
}