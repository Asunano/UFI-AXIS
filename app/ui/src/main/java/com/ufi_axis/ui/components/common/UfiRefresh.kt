// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 在宿主 [androidx.lifecycle.Lifecycle] 进入 [Lifecycle.State.RESUMED] 时触发一次 [onRefresh]。
 *
 * 实现基于 [LocalLifecycleOwner] + [LifecycleEventObserver]，通过 [DisposableEffect]
 * 注册/注销观察者；仅响应 `ON_RESUME` 事件，用于列表页“返回前台自动刷新一次”。
 *
 * 不引入任何新依赖，仅使用既有 lifecycle（`LocalLifecycleOwner` 随 navigation-compose 透传）
 * 与 Compose runtime（`DisposableEffect`）API。供 UID-007 等列表页复用。
 *
 * @param onRefresh 宿主回到前台（ON_RESUME）时回调一次，用于拉取最新数据。
 */
@Composable
fun rememberResumeRefresh(onRefresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
