package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onSetupComplete: (ip: String, port: Int, token: String) -> Unit) {
    var ip by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("8088") }
    var token by remember { mutableStateOf("ufi-axis-default-token") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .padding(Spacing.XLarge)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Large)
        ) {
            Spacer(Modifier.height(Spacing.XLarge))

            Icon(Icons.Default.Router, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)

            Text("UFI-AXIS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("连接至 UFI-AXIS-Core 后端服务", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(Spacing.Medium))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Spacing.CardCorner),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(Spacing.InnerPadding), verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                    UfiTextField(value = ip, onValueChange = { ip = it; isError = false }, label = "设备 IP 地址", placeholder = "192.168.0.1",
                        isError = isError && ip.isBlank())
                    UfiDigitField(value = portText, onValueChange = { portText = it }, label = "端口")
                    UfiTextField(value = token, onValueChange = { token = it }, label = "认证 Token")
                }
            }

            if (testResult != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(Spacing.CardCorner)
                ) {
                    Text(testResult!!, modifier = Modifier.padding(Spacing.InnerPadding),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(Spacing.Medium))

            UfiButtonRow {
                UfiSecondaryButton(text = "测试连接", onClick = {
                    if (ip.isBlank()) { isError = true; return@UfiSecondaryButton }
                    isTesting = true; testResult = null; isError = false
                    scope.launch {
                        val port = portText.toIntOrNull() ?: 8088
                        val result = testConnection(ip, port, token)
                        testResult = result.first; isError = !result.second; isTesting = false
                    }
                }, enabled = !isTesting)

                UfiPrimaryButton(text = "开始使用", onClick = {
                    if (ip.isBlank()) { isError = true; return@UfiPrimaryButton }
                    val port = portText.toIntOrNull() ?: 8088
                    onSetupComplete(ip, port, token)
                }, enabled = !isTesting && ip.isNotBlank())
            }

            Spacer(Modifier.height(Spacing.XLarge))
        }
    }
}

private suspend fun testConnection(ip: String, port: Int, token: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
    try {
        val url = URL("http://$ip:$port/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        val code = conn.responseCode
        if (code == 200) {
            BufferedReader(InputStreamReader(conn.inputStream)).readText()
            "连接成功（HTTP $code）" to true
        } else {
            "连接失败（HTTP $code）" to false
        }
    } catch (e: Exception) {
        "连接失败: ${e.message}" to false
    }
}