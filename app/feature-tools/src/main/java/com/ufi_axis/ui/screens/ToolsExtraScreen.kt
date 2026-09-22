package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiGridCard
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing

/**
 * 工具入口的一条记录。
 *
 * 入口原来是 11 次写死的 [UfiGridCard] 调用，其中 6 个用裸字符串路由、3 个用 `Routes.*`
 * 常量 —— 连"同一页的入口"都没统一。改成数据后一级页与[进阶工具页][ToolsExtraScreen]
 * 共用同一套渲染，增删只改清单，且路由必须来自 [Routes]（裸字符串写不进这里）。
 */
internal data class ToolEntry(
    val title: String,
    val icon: ImageVector,
    val description: String,
    val route: String
)

/**
 * 日常工具：一级页直接显示。
 *
 * 判据是**使用频率**，不是技术实现 —— 这七项里文件/媒体/下载/短信/流量都是隔几天就要点的，
 * 而收进进阶页的那几项（见 [TOOLS_EXTRA]）一周未必用一次。
 *
 * 视频 / 音乐 / 图片占了三格：2026-09-16 从「媒体中心」一张卡拆开的，
 * 三类各有各的扫描范围与授权状态，合在一页里要先选栏才能看。这里保留拆开的形态。
 */
internal val TOOLS_PRIMARY = listOf(
    ToolEntry("文件管理", Icons.Default.FolderOpen, "浏览/复制/移动/上传", Routes.DETAIL_FILES),
    ToolEntry("视频", Icons.Default.Videocam, "设备里的视频", Routes.MEDIA_LIBRARY_VIDEO),
    ToolEntry("音乐", Icons.Default.MusicNote, "播放 · 系统媒体控制", Routes.MEDIA_LIBRARY_AUDIO),
    ToolEntry("图片", Icons.Default.Image, "缩略图 · 缩放查看", Routes.MEDIA_LIBRARY_IMAGE),
    ToolEntry("下载管理", Icons.Default.CloudDownload, "远程下载/aria2", Routes.DETAIL_DOWNLOADS),
    ToolEntry("短信", Icons.Default.Sms, "收发短信", Routes.DETAIL_SMS),
    ToolEntry("流量管理", Icons.Default.DataUsage, "限额与统计", Routes.DETAIL_TRAFFIC_MGMT)
)

/**
 * 进阶工具：收进二级页。
 *
 * 共同点是"低频 + 需要先懂点什么"：内网穿透得先装组件再配服务器、定时任务要会写脚本、
 * 应用管理是一次性操作且误删有代价、高级控制台直接给到 AT 指令与 Shell。
 *
 * 顺序按**危险程度**从低到高 —— 最后两项一个能改系统应用、一个能执行任意命令，
 * 放在最后免得手滑。
 */
internal val TOOLS_EXTRA = listOf(
    ToolEntry("内网穿透", Icons.Default.VpnLock, "FRP · CF Tunnel", Routes.DETAIL_TUNNEL),
    ToolEntry("定时任务", Icons.Default.Schedule, "脚本调度", Routes.DETAIL_TASKS),
    ToolEntry("应用管理", Icons.Default.Apps, "安装/卸载", Routes.DETAIL_APPS),
    ToolEntry("高级控制台", Icons.Default.Terminal, "AT 指令 · Shell", Routes.DETAIL_TOOLS_ADVANCED)
)

/**
 * 「进阶工具」自己也是一张入口卡，和日常七项**同在一个网格里**。
 *
 * 一开始把它做成通栏卡（想用宽度区分"通往一组东西"和"一个功能"），实测割裂感强 ——
 * 同一页出现两种卡宽，视线在第 8 格那里断一下。统一成网格第 8 格，两列正好四行。
 *
 * description 写得短：半宽卡装不下四个全名，写全了会截断成更难读的样子。
 */
internal val TOOLS_EXTRA_ENTRY = ToolEntry(
    title = "进阶工具",
    icon = Icons.Default.Tune,
    description = "穿透 · 任务 · 应用 · 控制台",
    route = Routes.DETAIL_TOOLS_EXTRA
)

/** 一级页实际渲染的八格：日常七项 + 进阶入口。 */
internal val TOOLS_PRIMARY_GRID = TOOLS_PRIMARY + TOOLS_EXTRA_ENTRY

/** 入口卡之间的间距。两列网格，横竖同值才不会看出"行比列挤"。 */
private val TOOL_GRID_GAP = 12.dp

/**
 * 两列入口网格。一级页与进阶页共用，避免两边各调一套 padding/间距然后慢慢分叉。
 */
@Composable
internal fun ToolGrid(
    entries: List<ToolEntry>,
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = Spacing.PagePadding),
        horizontalArrangement = Arrangement.spacedBy(TOOL_GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(TOOL_GRID_GAP),
        maxItemsInEachRow = 2
    ) {
        entries.forEach { entry ->
            UfiGridCard(
                modifier = Modifier.weight(1f, fill = true),
                title = entry.title,
                icon = entry.icon,
                description = entry.description,
                onClick = { navController.navigate(entry.route) }
            )
        }
    }
}

/**
 * 进阶工具页（2026-09-20）。
 *
 * 只是一页入口网格，自己不发任何请求 —— 所以没有 loading、没有错误横幅。
 * 这两样在一级页上曾经引发过问题：`ToolsState` 是整个 tools 域共享的，
 * 别的页面置的 isLoading / errorMessage 会漏到入口页上显示（见 [ToolsScreen] 里的记录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsExtraScreen(navController: NavHostController) {
    UfiScreenScaffold(
        title = "进阶工具",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            ToolGrid(entries = TOOLS_EXTRA, navController = navController)
            // 本页是二级页，底部没有胶囊导航栏，留常规间距即可
            Spacer(Modifier.height(Spacing.Medium))
        }
    }
}
