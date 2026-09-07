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
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.toolsState.collectAsState()

    UfiScreenScaffold(title = "工具", navController = navController, showBack = false) { padding ->
        // 入场动画已上移到 MainNavGraph 根节点（"app-launch"），只在冷启动播一次；
        // 页面级 blurEntrance 会在切 Tab / 从二级页返回时重播，观感是抖动，故移除。
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 2026-09-05：这里原来挂 `state.errorMessage?.let { UfiErrorBanner(...) }`。
            // 本页只有静态入口卡、不发任何请求，产不出错误 —— 显示的其实是 ToolsState
            //（整个 tools 域共享一份）里**别的页面**报的错，谁在哪报的说不清。
            // 与同文件下方那条 isLoading 漏显 bug 同一个成因。现在错误统一由 Activity 级
            // 全局浮层展示（MainActivity 读 viewModel.globalError）。

            // 工具入口：7 个 UfiGridCard 单组平铺（用户要求去掉分组标题，卡片保留）。
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.PagePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 2
            ) {
                UfiGridCard(modifier = Modifier.weight(1f, fill = true), title = "文件管理", icon = Icons.Default.FolderOpen, description = "浏览/复制/移动/上传", onClick = { navController.navigate("detail/files") })
                UfiGridCard(modifier = Modifier.weight(1f, fill = true), title = "下载管理", icon = Icons.Default.CloudDownload, description = "远程下载/aria2", onClick = { navController.navigate("detail/downloads") })
                UfiGridCard(modifier = Modifier.weight(1f, fill = true), title = "高级控制台", icon = Icons.Default.Terminal, description = "AT 指令 · Shell", onClick = { navController.navigate("detail/tools-advanced") })
                UfiGridCard(modifier = Modifier.weight(1f, fill = true), title = "应用管理", icon = Icons.Default.Apps, description = "安装/卸载", onClick = { navController.navigate("detail/apps") })
                UfiGridCard(modifier = Modifier.weight(1f, fill = true), title = "定时任务", icon = Icons.Default.Schedule, description = "脚本调度", onClick = { navController.navigate("detail/tasks") })
                UfiGridCard(modifier = Modifier.weight(1f, fill = true), title = "短信", icon = Icons.Default.Sms, description = "收发短信", onClick = { navController.navigate("detail/sms") })
                UfiGridCard(modifier = Modifier.weight(1f, fill = true), title = "流量管理", icon = Icons.Default.DataUsage, description = "限额与统计", onClick = { navController.navigate("detail/traffic-management") })
                UfiGridCard(modifier = Modifier.weight(1f, fill = true), title = "内网穿透", icon = Icons.Default.VpnLock, description = "FRP · CF Tunnel", onClick = { navController.navigate("detail/tunnel") })
            }

            // 2026-09-04 删掉 `UfiLoadingBox(isLoading = state.isLoading) {}`
            // ——「进短信页每次都转圈」的真正来源，圆圈画在**工具页**上而不是短信页里。
            //
            // 机制：ToolsState 是 tools 域所有页面共享的一份状态，短信页首帧的
            // `loadSmsContacts()`（SmsScreen 的 LaunchedEffect(Unit)）会把 isLoading 置 true。
            // 而 detail 转场期间 NavHost 的 AnimatedContent 仍持有并绘制外层宿主页（工具页，
            // 按 detailExit 只平移 1/4 屏做视差，绝大部分还在屏上），于是圆圈恰好在
            // 「短信页滑进来」的这几百毫秒里出现在屏幕上 —— 用户看到的就是"进短信页有圆圈"。
            // 返回工具页同理（顶栏刷新 / rememberResumeRefresh 再置 isLoading 时也会闪）。
            //
            // 为什么是直接删而不是改判据：工具页只有 8 个静态入口卡，自己没有任何
            // 依赖 isLoading 的内容，content 槽还是空 lambda `{}` —— 这个转圈从来不代表
            // 本页在加载，纯粹是别的页面的加载状态漏到这里显示。首屏骨架也不需要
            // （入口卡是写死的，第一帧就完整）。公共组件 UfiLoadingBox 未改动。
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}



