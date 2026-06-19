package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.ScheduledTask
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tasksState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<ScheduledTask?>(null) }

    LaunchedEffect(Unit) { viewModel.loadTaskList() }

    UfiScreenScaffold(title = "定时任务", navController = navController, showBack = true,
        actions = {
            if (state.tasks.isNotEmpty()) {
                IconButton(onClick = { viewModel.clearTasks() }) { Icon(Icons.Default.DeleteSweep, "清除全部") }
            }
            IconButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.Add, "新建任务") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.errorMessage?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(Spacing.CardCorner),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text(err, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            if (state.tasks.isEmpty() && !state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    UfiEmptyState(icon = Icons.Default.Schedule, message = "暂无定时任务")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskCard(task = task,
                            onEdit = { editingTask = it },
                            onDelete = { viewModel.deleteTask(it.id) },
                            onToggle = { viewModel.updateTask(it.id, it.copy(enabled = !it.enabled)) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        TaskEditDialog(title = "新建任务", initial = ScheduledTask(),
            onConfirm = { viewModel.createTask(it); showCreateDialog = false },
            onDismiss = { showCreateDialog = false })
    }
    editingTask?.let { task ->
        TaskEditDialog(title = "编辑任务", initial = task,
            onConfirm = { viewModel.updateTask(task.id, it); editingTask = null },
            onDismiss = { editingTask = null })
    }
}

@Composable
private fun TaskCard(task: ScheduledTask, onEdit: (ScheduledTask) -> Unit, onDelete: (ScheduledTask) -> Unit, onToggle: (ScheduledTask) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.name.ifBlank { task.command.take(30) }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text("${"%02d".format(task.hour)}:${"%02d".format(task.minute)} ${if (task.repeatDaily) "每日" else "单次"} \u00B7 ${task.command.take(40)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = task.enabled, onCheckedChange = { onToggle(task) })
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { onEdit(task) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, "编辑", Modifier.size(18.dp)) }
                IconButton(onClick = { onDelete(task) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun TaskEditDialog(title: String, initial: ScheduledTask, onConfirm: (ScheduledTask) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var command by remember { mutableStateOf(initial.command) }
    var hour by remember { mutableStateOf(initial.hour.toString()) }
    var minute by remember { mutableStateOf(initial.minute.toString()) }
    var repeatDaily by remember { mutableStateOf(initial.repeatDaily) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Spacing.CardCorner),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UfiTextField(value = name, onValueChange = { name = it }, label = "任务名称")
                UfiTextField(value = command, onValueChange = { command = it }, label = "Shell 命令", singleLine = false)
                UfiFieldRow {
                    UfiDigitField(value = hour, onValueChange = { hour = it }, label = "时", maxLength = 2, modifier = Modifier.weight(1f))
                    UfiDigitField(value = minute, onValueChange = { minute = it }, label = "分", maxLength = 2, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("每日重复"); Switch(checked = repeatDaily, onCheckedChange = { repeatDaily = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("启用"); Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hour.toIntOrNull() ?: 0; val m = minute.toIntOrNull() ?: 0
                if (command.isNotBlank()) {
                    onConfirm(initial.copy(name = name, command = command, hour = h.coerceIn(0, 23), minute = m.coerceIn(0, 59), repeatDaily = repeatDaily, enabled = enabled))
                }
            }) { Text("确定", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}