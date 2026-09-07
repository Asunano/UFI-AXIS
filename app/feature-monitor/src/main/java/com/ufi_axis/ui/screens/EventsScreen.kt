package com.ufi_axis.ui.screens

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiPageBackgroundBox
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.viewmodel.MainViewModel

/**
 * v26（2026-08-20）：事件中心独立全屏页（方案 A）。
 *
 * 由 [com.ufi_axis.ui.navigation.Routes.DETAIL_EVENTS] 承载。进入该路由时底部胶囊自动隐藏
 * （MainNavGraph 仅在 MAIN 路线显示胶囊），顶部返回栏 ‹ 事件中心 返回 MAIN 监控 Tab 后
 * 胶囊恢复 —— 分页条因此有了固定归宿，不再被胶囊遮挡。
 *
 * 内容委托给同包的 [EventsCenterContent]（从原 MonitorScreen 的 EVENTS 子 Tab 抽取）。
 */
@Composable
fun EventsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    UfiScreenScaffold(
        title = "事件中心",
        navController = navController,
        showBack = true
    ) {
        // UfiScreenScaffold 外层已处理导航栏 inset（content 收到 PaddingValues(0)），
        // 无需再包 padding；UfiPageBackgroundBox 自身 fillMaxSize + 背景。
        UfiPageBackgroundBox {
            EventsCenterContent(viewModel = viewModel)
        }
    }
}
