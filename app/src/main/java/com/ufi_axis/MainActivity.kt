package com.ufi_axis

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.repository.UfiAxisRepository
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.ui.components.UpdateDialog
import com.ufi_axis.ui.navigation.MainNavGraph
import com.ufi_axis.ui.screens.SetupScreen
import com.ufi_axis.ui.theme.UFIAXISTheme
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.viewmodel.MainViewModel
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = AppPreferences(this)
        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()

        setContent {
            UFIAXISTheme {
                var isSetupComplete by remember { mutableStateOf(prefs.isSetupComplete) }

                if (!isSetupComplete) {
                    SetupScreen(
                        onSetupComplete = { ip, port, token ->
                            prefs.serverIp = ip
                            prefs.serverPort = port
                            prefs.token = token
                            prefs.isSetupComplete = true
                            isSetupComplete = true
                        }
                    )
                } else {
                    val api = remember { RetrofitClient.getApiService(prefs) }
                    val repository = remember { UfiAxisRepository(api) }
                    val webSocketRepository = remember {
                        WebSocketRepository(prefs.baseUrl.replace("http", "ws").trimEnd('/'), prefs.token)
                    }
                    val viewModel: MainViewModel = viewModel(
                        factory = MainViewModel.provideFactory(repository, webSocketRepository, networkMonitor, this@MainActivity)
                    )

                    val updateState by viewModel.updateState.collectAsState()
                    var showUpdateDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(updateState.hasUpdate) {
                        if (updateState.hasUpdate) showUpdateDialog = true
                    }

                    if (showUpdateDialog && updateState.serverVersion != null && updateState.updateUrl != null) {
                        UpdateDialog(
                            serverVersion = updateState.serverVersion!!,
                            updateUrl = updateState.updateUrl!!,
                            onDismiss = { showUpdateDialog = false }
                        )
                    }

                    UFIAXISTheme {
                        MainNavGraph(
                            viewModel = viewModel,
                            onServerConfigChanged = {
                                RetrofitClient.recreate(prefs)
                                webSocketRepository.reconnect()
                                viewModel.refreshDashboard()
                            }
                        )
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                copyImageToInternal(uri)
            }
        }
    }

    private fun copyImageToInternal(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val file = File(filesDir, "background.jpg")
            FileOutputStream(file).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            // Update background manager through view model
            val viewModel: MainViewModel by viewModels()
            viewModel.backgroundManager.updateImagePath(file.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::networkMonitor.isInitialized) {
            networkMonitor.stopMonitoring()
        }
    }
}
