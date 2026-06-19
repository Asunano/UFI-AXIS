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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.SmsRecord
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val toolsState by viewModel.toolsState.collectAsState()
    var showSendDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadSmsList() }

    UfiScreenScaffold(title = "短信", navController = navController, showBack = true,
        actions = {
            IconButton(onClick = { viewModel.loadSmsList() }) { Icon(Icons.Default.Refresh, null) }
            IconButton(onClick = { showSendDialog = true }) { Icon(Icons.Default.Add, null) }
        }
    ) { padding ->
        if (showSendDialog) {
            var phone by remember { mutableStateOf("") }
            var message by remember { mutableStateOf("") }
            ModalBottomSheet(onDismissRequest = { showSendDialog = false },
                shape = RoundedCornerShape(topStart = Spacing.CardCorner, topEnd = Spacing.CardCorner)) {
                Column(Modifier.padding(Spacing.InnerPadding)) {
                    Text("发送短信", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiTextField(value = phone, onValueChange = { phone = it }, label = "手机号", placeholder = "10010",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiTextField(value = message, onValueChange = { message = it }, label = "内容", singleLine = false, minLines = 2, maxLines = 4)
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiButtonRow {
                        UfiSecondaryButton(text = "取消", onClick = { showSendDialog = false }, modifier = Modifier.weight(1f))
                        UfiPrimaryButton(text = "发送", onClick = { viewModel.sendSms(phone, message); showSendDialog = false },
                            enabled = phone.isNotBlank() && message.isNotBlank(), modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(Spacing.Large))
            }
        }

        if (toolsState.isLoading && toolsState.smsList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (toolsState.smsList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                UfiEmptyState(icon = Icons.Default.MailOutline, message = "暂无短信")
            }
        } else {
            val sorted = toolsState.smsList.sortedByDescending { it.timestamp }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.PagePadding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sorted, key = { "${it.id}-${it.timestamp}" }) { sms ->
                    SmsCard(sms = sms, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun SmsCard(sms: SmsRecord, viewModel: MainViewModel) {
    val isReceived = sms.direction == "received"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.CardCorner),
        colors = CardDefaults.cardColors(
            containerColor = if (!sms.read) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isReceived) Icons.Default.ArrowBack else Icons.Default.ArrowForward,
                    null, Modifier.size(16.dp),
                    tint = if (isReceived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (isReceived) "来自: ${sms.phoneNumber}" else "发送至: ${sms.phoneNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!sms.read) FontWeight.Bold else FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (!sms.read) {
                    Text("未读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(FormatUtils.formatTimestamp(sms.timestamp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(sms.content, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (!sms.read) FontWeight.Medium else FontWeight.Normal)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!sms.read) {
                    OutlinedButton(onClick = { viewModel.markSmsRead(sms.id.toString()) },
                        modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("已读", style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedButton(onClick = { viewModel.deleteSms(sms.id.toString()) },
                    modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}