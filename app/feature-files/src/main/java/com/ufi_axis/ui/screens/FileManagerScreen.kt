package com.ufi_axis.ui.screens

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ufi_axis.ui.screens.filemanager.FileManagerRoot
import com.ufi_axis.viewmodel.MainViewModel

/**
 * File Manager screen entry point (routing / ABI anchor).
 *
 * This composable is referenced directly by `AppScreens.kt` and MUST keep a
 * stable signature: `FileManagerScreen(viewModel: MainViewModel, navController: NavHostController)`.
 * It only forwards the file-manager module ([MainViewModel.files]) and the
 * navigation controller to the feature root [FileManagerRoot]. All business
 * logic lives in the `filemanager` sub-package and is implemented in later
 * tickets.
 */
@Composable
fun FileManagerScreen(viewModel: MainViewModel, navController: NavHostController) {
    FileManagerRoot(
        viewModel = viewModel.files,
        // 存储源的增删改查（长按存储行 → 编辑 / 测试 / 删除）在文件管理器首屏完成，
        // 所以把那个模块也传进去。2026-09-21 起独立的「外部存储」列表页已删除。
        sourceModule = viewModel.storageSources,
        navController = navController
    )
}
