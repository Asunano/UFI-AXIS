// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight

/**
 * 滑块样式页签（2026-08-10 优化：背景着色 + 滑块滑动动画 + 等宽居中）。
 *
 * 外观：轨道容器内一个圆角滑块（accent 色）随选中项动画滑动，选中文字反白（onAccent），
 * 未选中文字 secondary。适合 2-4 个页签的轻量分层（弹窗/页面顶部）。
 *
 * @param badges 可选角标计数，**按下标与 [tabs] 一一对应**：`null` 或 `<= 0` 不画角标，
 *   `> 99` 显示 `99+`。长度可短于 `tabs`（缺的按 null 处理），所以只给第二个页签加角标时
 *   可以写 `listOf(null, n)`。角标是 error 色圆片 + onError 字（2026-09-03 P1c 由写死白字改为
 *   跟随主题的 onError），选中态（accent 底）与未选中态（轨道底）上都能看清，故不随选中状态换色。
 */
@Composable
fun UfiScrollableTabRow(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    tabs: List<String>,
    modifier: Modifier = Modifier,
    badges: List<Int?> = emptyList()
) {
    val palette = LocalResolvedPalette.current
    if (tabs.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tabWidth = maxWidth / tabs.size
        val sliderOffset by animateDpAsState(
            targetValue = tabWidth * selectedTabIndex,
            animationSpec = UfiMotion.tabSlider(),
            label = "tabSliderOffset"
        )

        // 轨道容器（圆角背景，滑块与轨道同圆角营造"胶囊内滑块"）
        // 背景着色：浅色主题用 textSecondary 低 alpha（明显深于原 divider@0.38 近白），
        // 深色主题用 divider 较高 alpha（营造下凹的深色轨道）。
        val trackColor = if (palette.isDark) {
            palette.divider.copy(alpha = 0.6f)
        } else {
            palette.textSecondary.copy(alpha = 0.16f)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(UfiCardDefaults.shape)
                .background(trackColor)
                .padding(3.dp)
        ) {
            // 滑块（accent 背景，动画跟随）
            Box(
                modifier = Modifier
                    .offset(x = sliderOffset)
                    .width(tabWidth - 6.dp)
                    .height(36.dp)
                    .clip(UfiCardDefaults.shape)
                    .background(palette.accent)
            )
            // 页签文字层（等宽居中）
            Row(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, title ->
                    val selected = index == selectedTabIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(UfiCardDefaults.shape)
                            .clickable { onTabSelected(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        val badge = badges.getOrNull(index)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = title,
                                fontWeight = if (selected) UfiWeight.Emphasis else UfiWeight.Regular,
                                color = if (selected) palette.onAccent else palette.textSecondary
                            )
                            if (badge != null && badge > 0) {
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                        .clip(CircleShape)
                                        .background(palette.error)
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (badge > 99) "99+" else badge.toString(),
                                        style = UfiTextStyles.badgeTiny,
                                        lineHeight = 10.sp,
                                        // 2026-09-03（P1c）：原为写死 `Color.White`。这个字铺在**上一行的
                                        // `palette.error` 圆片**上（不是 accent 滑块——滑块上的选中文字在
                                        // 第 105 行，早已是 onAccent），换配色时若某主题把 error 调浅，
                                        // 白字会糊掉。故接 onError（error 之上的内容色）。
                                        color = palette.onError
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 带方向感知切换动画的页签内容容器（2026-08-11 从「关于-更新设置」弹窗抽取为公共组件）。
 *
 * 切换方向感知：选中右侧页签（index 增大）时新内容自右滑入、旧内容向左滑出；
 * 选中左侧页签时方向相反。slide + fade 组合，避免生硬跳变。
 *
 * 用于弹窗/页面的滑块分栏内容切换（如「网关与密码」「更新设置」），
 * 与 [UfiScrollableTabRow] 搭配：TabRow 保持静止，仅内容区播放切换动画。
 *
 * @param targetState 当前选中页签索引（与 [UfiScrollableTabRow.selectedTabIndex] 对齐）
 * @param content 各页签内容（以页签索引为参数）
 */
@Composable
fun UfiAnimatedTabContent(
    targetState: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit
) {
    // clipToBounds 防止水平滑入/滑出内容溢出内容区（尤其在可滚动弹窗中触发横向滚动条）
    Box(modifier = modifier.fillMaxWidth().clipToBounds()) {
        AnimatedContent(
            targetState = targetState,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 4 } + fadeOut())
                }
            },
            label = "UfiAnimatedTabContent"
        ) { tab ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.Large)
            ) { content(tab) }
        }
    }
}
