package com.ufi_axis.ui.animation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Centralized animation specifications matching UFITOOLSWidget's motion design.
 */
object UfiAnimSpecs {
    /** Page-level staggered entrance for list items */
    val staggerEnter: TweenSpec<Float> =
        tween(durationMillis = 600, easing = FastOutSlowInEasing)

    /** Dialog spring entrance with overshoot */
    val dialogEnter: AnimationSpec<Float> =
        spring<Float>(dampingRatio = 0.6f, stiffness = 380f)

    /** Toast water-drop entrance */
    val toastDrop: AnimationSpec<Float> =
        spring<Float>(dampingRatio = 0.45f, stiffness = 300f)

    /** Click/press scale feedback */
    val clickScale: AnimationSpec<Float> =
        spring<Float>(dampingRatio = 0.5f, stiffness = 600f)

    /** Generic smooth entrance */
    val fadeEnter: TweenSpec<Float> =
        tween(durationMillis = 400, easing = FastOutSlowInEasing)
}
