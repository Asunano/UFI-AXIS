package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.toolsState.collectAsState()

    // showNowPlaying：首页 5 个 Tab 的标题栏才显示「正在播放」
    UfiScreenScaffold(
        title = "工具",
        navController = navController,
        showBack = false,
        showNowPlaying = true
    ) { padding ->
        // 入场动画已上移到 MainNavGraph 根节点（"app-launch"），只在冷启动播一次；
        // 页面级 blurEntrance 会在切 Tab / 从二级页返回时重播，观感是抖动，故移除。
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 2026-09-05：这里原来挂 `state.errorMessage?.let { UfiErrorBanner(...) }`。
            // 本页只有静态入口卡、不发任何请求，产不出错误 —— 显示的其实是 ToolsState
            //（整个 tools 域共享一份）里**别的页面**报的错，谁在哪报的说不清。
            // 与同文件下方那条 isLoading 漏显 bug 同一个成因。现在错误统一由 Activity 级
            // 全局浮层展示（MainActivity 读 viewModel.globalError）。

            // 工具入口：日常七项 + 「进阶工具」，八格两列正好四行。
            //
            // 2026-09-20 拆页：原来 11 张卡平铺，内网穿透 / 定时任务 / 应用管理 / 高级控制台
            // 这四项是"低频 + 需要先懂点什么才敢点"的东西，混在日常入口里既把常用的挤到下面，
            // 也让人误以为随便点都安全。它们搬到 [ToolsExtraScreen]，清单见 TOOLS_EXTRA。
            //
            // 卡片数据放在 ToolsExtraScreen.kt 的 TOOLS_PRIMARY_GRID / TOOLS_EXTRA ——
            // 之前是 11 次写死的调用，6 个用裸字符串路由、3 个用 Routes.* 常量，连同一页都不统一。
            ToolGrid(entries = TOOLS_PRIMARY_GRID, navController = navController)

            // 2026-09-04 删掉 `UfiLoadingBox(isLoading = state.isLoading) {}`
            // ——「进短信页每次都转圈」的真正来源，圆圈画在**工具页**上而不是短信页里。
            //
            // 机制：ToolsState 是 tools 域所有页面共享的一份状态，短信页首帧的
            // `loadSmsContacts()`（SmsScreen 的 LaunchedEffect(Unit)）会把 isLoading 置 true。
            // 而 detail 转场期间 NavHost 的 AnimatedContent 仍持有并绘制外层宿主页（工具页，
            // 按 detailSharedAxisExit 只平移 1/6 屏做视差，绝大部分还在屏上），于是圆圈恰好在
            // 「短信页滑进来」的这几百毫秒里出现在屏幕上 —— 用户看到的就是"进短信页有圆圈"。
            // 返回工具页同理（顶栏刷新 / rememberResumeRefresh 再置 isLoading 时也会闪）。
            //
            // 为什么是直接删而不是改判据：工具页只有 8 个静态入口卡，自己没有任何
            // 依赖 isLoading 的内容，content 槽还是空 lambda `{}` —— 这个转圈从来不代表
            // 本页在加载，纯粹是别的页面的加载状态漏到这里显示。首屏骨架也不需要
            // （入口卡是写死的，第一帧就完整）。公共组件 UfiLoadingBox 未改动。
            // 底部为胶囊导航栏留白（2026-09-17）：本页是 Tab 页，胶囊在这里是**可交互**窗口，
            // 只留 12dp 的话最后一张入口卡会落进那条带子里、点不动。与监控页同一套 Modifier。
            Spacer(Modifier.ufiCapsuleBottomInset(Spacing.Medium))
        }
    }
}



