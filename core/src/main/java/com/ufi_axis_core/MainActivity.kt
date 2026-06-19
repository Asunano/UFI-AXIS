package com.ufi_axis_core

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ufi_axis_core.service.BackendService
import com.ufi_axis_core.ui.theme.UFIAXISCoreTheme
import com.ufi_axis_core.util.AppSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UFIAXISCoreTheme {
                AppRoot()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Root: manages navigation between Main and Settings screens
// ---------------------------------------------------------------------------

@Composable
private fun AppRoot() {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
    } else {
        MainScreen(onSettingsClick = { showSettings = true })
    }
}

// ---------------------------------------------------------------------------
// Main Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onSettingsClick: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings = remember { AppSettings.getInstance(context) }
    var serviceRunning by remember { mutableStateOf(BackendService.isRunning) }
    val port = settings.port

    // Lifecycle-aware storage permission check — re-evaluates when returning from settings
    var hasStoragePermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    || Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-start service on first launch
    LaunchedEffect(Unit) {
        if (settings.autoStartOnBoot) {
            BackendService.start(context)
            serviceRunning = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UFI-AXIS-Core", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (serviceRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (serviceRunning) "后端服务运行中" else "后端服务未运行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (serviceRunning)
                            "HTTP :$port  |  WS /ws/realtime"
                        else
                            "点击下方按钮启动后端服务",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Storage permission card (Android 11+ MANAGE_EXTERNAL_STORAGE)
            if (!hasStoragePermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "需要存储权限",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "文件管理功能需要「所有文件访问」权限，请点击下方按钮前往系统设置授权",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            }
                        ) { Text("前往授权") }
                    }
                }
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        BackendService.start(context)
                        serviceRunning = true
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !serviceRunning
                ) { Text("启动服务") }

                OutlinedButton(
                    onClick = {
                        BackendService.stop(context)
                        serviceRunning = false
                    },
                    modifier = Modifier.weight(1f),
                    enabled = serviceRunning
                ) { Text("停止服务") }
            }

            // Restart hint
            if (serviceRunning) {
                Text(
                    text = "提示：修改配置后需停止并重新启动服务才能生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.weight(1f))

            // Version
            Text(
                text = "UFI-AXIS-Core v0.1",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Settings Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.getInstance(context) }

    var token by remember { mutableStateOf(settings.token) }
    var secret by remember { mutableStateOf(settings.secret) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var autoStart by remember { mutableStateOf(settings.autoStartOnBoot) }
    var rateLimitMax by remember { mutableStateOf(settings.rateLimitMax.toString()) }
    var rateLimitWindow by remember { mutableStateOf(settings.rateLimitWindowSec.toString()) }
    var goformIp by remember { mutableStateOf(settings.goformIp) }
    var goformPort by remember { mutableStateOf(settings.goformPort.toString()) }
    var goformPassword by remember { mutableStateOf(settings.goformPassword) }
    var showGoformPassword by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    fun save() {
        settings.token = token.trim()
        settings.secret = secret.trim()
        port.toIntOrNull()?.let { settings.port = it }
        settings.autoStartOnBoot = autoStart
        rateLimitMax.toIntOrNull()?.let { settings.rateLimitMax = it }
        rateLimitWindow.toIntOrNull()?.let { settings.rateLimitWindowSec = it }
        settings.goformIp = goformIp.trim()
        goformPort.toIntOrNull()?.let { settings.goformPort = it }
        if (goformPassword.isNotBlank()) settings.goformPassword = goformPassword.trim()
        saved = true
    }

    fun resetDefaults() {
        settings.resetAll()
        token = AppSettings.DEFAULT_TOKEN
        secret = AppSettings.DEFAULT_SECRET
        port = AppSettings.DEFAULT_PORT.toString()
        autoStart = AppSettings.DEFAULT_AUTO_START
        rateLimitMax = AppSettings.DEFAULT_RATE_LIMIT_MAX.toString()
        rateLimitWindow = AppSettings.DEFAULT_RATE_LIMIT_WINDOW_SEC.toString()
        goformIp = AppSettings.DEFAULT_GOFORM_IP
        goformPort = AppSettings.DEFAULT_GOFORM_PORT.toString()
        goformPassword = AppSettings.DEFAULT_GOFORM_PASSWORD
        saved = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Section: Auth ----
            Text("认证配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; saved = false },
                label = { Text("Bearer Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it; saved = false },
                label = { Text("HMAC Secret") },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showSecret = !showSecret }) {
                        Text(if (showSecret) "隐藏" else "显示")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- Section: Server ----
            Text("服务配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }; saved = false },
                label = { Text("监听端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("范围 1024 - 65535，默认 8088") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("开机自动启动服务")
                Switch(checked = autoStart, onCheckedChange = { autoStart = it; saved = false })
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- Section: Rate Limit ----
            Text("频率限制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = rateLimitMax,
                onValueChange = { rateLimitMax = it.filter { c -> c.isDigit() }; saved = false },
                label = { Text("最大请求数 / 窗口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = rateLimitWindow,
                onValueChange = { rateLimitWindow = it.filter { c -> c.isDigit() }; saved = false },
                label = { Text("窗口时长（秒）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- Section: Goform ----
            Text("Goform 设备接口", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = goformIp,
                onValueChange = { goformIp = it; saved = false },
                label = { Text("Goform IP 地址") },
                placeholder = { Text("192.168.0.1") },
                singleLine = true,
                supportingText = { Text("设备 Web 管理界面 IP，用于获取信号/短信等数据") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = goformPort,
                onValueChange = { goformPort = it.filter { c -> c.isDigit() }; saved = false },
                label = { Text("Goform 端口") },
                placeholder = { Text("8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("设备 Web 管理端口，默认 8080") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = goformPassword,
                onValueChange = { goformPassword = it; saved = false },
                label = { Text("Goform 登录密码") },
                placeholder = { Text("admin") },
                singleLine = true,
                visualTransformation = if (showGoformPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showGoformPassword = !showGoformPassword }) {
                        Text(if (showGoformPassword) "隐藏" else "显示")
                    }
                },
                supportingText = { Text("设备 Web 管理密码，默认 admin") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- Actions ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { save() },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }

                OutlinedButton(
                    onClick = { resetDefaults() },
                    modifier = Modifier.weight(1f)
                ) { Text("恢复默认") }
            }

            if (saved) {
                Text(
                    text = "已保存。修改认证或端口配置后需重启服务生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
