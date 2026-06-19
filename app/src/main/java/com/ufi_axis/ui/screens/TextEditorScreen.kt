package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiAlertDialog
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String
) {
    val state by viewModel.fileManagerState.collectAsState()
    var editText by remember { mutableStateOf("") }
    var isLoaded by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    val fileName = remember(filePath) { filePath.substringAfterLast("/") }

    // Load file content on first composition
    LaunchedEffect(filePath) {
        viewModel.readFile(filePath)
    }

    // Watch for file content changes
    LaunchedEffect(state.fileContent) {
        if (state.fileContent != null && !isLoaded) {
            editText = state.fileContent ?: ""
            isLoaded = true
        }
    }

    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, fontWeight = FontWeight.Bold, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (isLoaded) {
                        IconButton(
                            onClick = {
                                viewModel.writeFile(filePath, editText)
                                hasChanges = false
                                showSaveSuccess = true
                            },
                            enabled = hasChanges
                        ) {
                            Icon(
                                Icons.Default.Save,
                                "保存",
                                tint = if (hasChanges) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.PagePadding)
        ) {
            if (!isLoaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                OutlinedTextField(
                    value = editText,
                    onValueChange = {
                        editText = it
                        hasChanges = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }
    }

    if (showSaveSuccess) {
        UfiAlertDialog(
            title = "提示",
            text = "文件已保存",
            onDismiss = { showSaveSuccess = false }
        )
    }

    state.errorMessage?.let { msg ->
        UfiAlertDialog(
            title = "错误",
            text = msg,
            onDismiss = { viewModel.clearFileOperationMessage() }
        )
    }
}
