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
        navController = navController
    )
}
