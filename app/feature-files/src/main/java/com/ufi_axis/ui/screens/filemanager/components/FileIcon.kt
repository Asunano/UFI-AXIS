package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ufi_axis.ui.screens.filemanager.fileKindOf
import com.ufi_axis.ui.screens.filemanager.icon
import com.ufi_axis.ui.screens.filemanager.label
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * 通用文件图标组件。
 *
 * 2026-09-11：图标与分类**不再由本文件定义**，改为读
 * [com.ufi_axis.ui.screens.filemanager.FileKind] 这份唯一真源 ——
 * 此前这里的扩展名表与点击分发表、菜单文案表各存一份且互不一致，
 * 于是出现「显示了图片图标却点不开」这类矛盾。
 *
 * 组件完全无状态：所有数据通过参数传入。
 *
 * @param name 文件名（含扩展名）
 * @param isDirectory 是否为目录，目录一律显示文件夹图标
 */
@Composable
fun FileIcon(
    name: String,
    isDirectory: Boolean = false,
    modifier: Modifier = Modifier
) {
    val kind = fileKindOf(name, isDirectory)
    Icon(
        imageVector = kind.icon,
        contentDescription = kind.label,
        tint = fileIconTint(isDirectory),
        modifier = modifier
    )
}

/**
 * 文件图标的着色：目录用强调色，文件用次要文字色。
 *
 * 目录之所以单独染色：文件列表里"能进去的"和"只能打开的"是两种交互，颜色是最省版面的区分。
 */
@Composable
fun fileIconTint(isDirectory: Boolean): Color {
    val palette = LocalResolvedPalette.current
    return if (isDirectory) palette.accent else palette.textSecondary
}

/** 中文分类标签。保留这个函数名是因为详情弹窗等处已在用它。 */
fun fileTypeLabel(name: String): String = fileKindOf(name).label
