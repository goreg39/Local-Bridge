package io.github.goreg39.localbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val uiState = mutableStateOf(BridgeServiceState())

    private lateinit var clipboardBridge: ClipboardBridge
    private var bridgeService: BridgeService? = null
    private var bindRequested = false

    private val serviceStateListener: (BridgeServiceState) -> Unit = { state ->
        runOnUiThread {
            uiState.value = state
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? BridgeService.LocalBinder ?: return
            bridgeService = localBinder.service
            bridgeService?.addStateListener(serviceStateListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bindRequested = false
            uiState.value = uiState.value.copy(
                status = BridgeServerStatus.ERROR,
                address = null,
                detail = "Связь с фоновым сервисом потеряна.",
            )
        }
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startBridgeService()
        } else {
            uiState.value = uiState.value.copy(
                status = BridgeServerStatus.PERMISSION_REQUIRED,
                address = null,
                detail = "Без доступа к локальной сети браузер другого устройства не сможет подключиться к телефону.",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        clipboardBridge = ClipboardBridge(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { innerPadding ->
                        LocalBridgeScreen(
                            state = uiState.value,
                            onToggleServer = ::toggleServer,
                            onSendClipboardToPc = ::sendClipboardToPc,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )
                    }
                }
            }
        }

        // Сохраняем удобное поведение MVP: при открытии приложения сервер стартует сам.
        // После старта Activity больше не владеет жизненным циклом сервера.
        ensureLocalNetworkAccessAndStart()
    }

    override fun onStart() {
        super.onStart()
        bindToRunningService(autoCreate = false)
    }

    override fun onStop() {
        unbindFromBridgeService()
        super.onStop()
    }

    private fun toggleServer() {
        when (uiState.value.status) {
            BridgeServerStatus.RUNNING,
            BridgeServerStatus.RUNNING_NO_ADDRESS,
            BridgeServerStatus.STARTING,
            -> stopBridgeService()

            BridgeServerStatus.STOPPED,
            BridgeServerStatus.PERMISSION_REQUIRED,
            BridgeServerStatus.ERROR,
            -> ensureLocalNetworkAccessAndStart()
        }
    }

    private fun ensureLocalNetworkAccessAndStart() {
        if (Build.VERSION.SDK_INT < ANDROID_17_API) {
            startBridgeService()
            return
        }

        if (checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startBridgeService()
        } else {
            uiState.value = uiState.value.copy(
                status = BridgeServerStatus.PERMISSION_REQUIRED,
                address = null,
                detail = "Нужно разрешение Android «Устройства поблизости / локальная сеть».",
            )
            localNetworkPermissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }

    private fun startBridgeService() {
        uiState.value = uiState.value.copy(
            status = BridgeServerStatus.STARTING,
            address = null,
            detail = "Запускаю фоновый сервер…",
        )

        val intent = Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_START)

        try {
            startForegroundService(intent)
            bindToRunningService(autoCreate = true)
        } catch (error: Exception) {
            uiState.value = uiState.value.copy(
                status = BridgeServerStatus.ERROR,
                address = null,
                detail = "Не удалось запустить сервис: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun stopBridgeService() {
        bridgeService?.stopBridge()
            ?: stopService(Intent(this, BridgeService::class.java))

        uiState.value = uiState.value.copy(
            status = BridgeServerStatus.STOPPED,
            address = null,
            detail = "Сервер остановлен пользователем.",
        )
    }

    private fun bindToRunningService(autoCreate: Boolean) {
        if (bindRequested) return

        val flags = if (autoCreate) Context.BIND_AUTO_CREATE else 0
        val didBind = bindService(
            Intent(this, BridgeService::class.java),
            serviceConnection,
            flags,
        )

        if (didBind) {
            bindRequested = true
        }
    }

    private fun unbindFromBridgeService() {
        if (!bindRequested) return

        bridgeService?.removeStateListener(serviceStateListener)
        runCatching { unbindService(serviceConnection) }
        bridgeService = null
        bindRequested = false
    }

    private fun sendClipboardToPc() {
        val service = bridgeService
        if (service == null) {
            uiState.value = uiState.value.copy(
                lastClipboardAction = "Фоновый сервер недоступен. Запустите сервер и повторите.",
            )
            return
        }

        // Чтение clipboard остаётся здесь, в видимой Activity, по явному нажатию пользователя.
        service.publishClipboardFromPhone(clipboardBridge.readText())
    }

    companion object {
        private const val ANDROID_17_API = 37
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

@Composable
private fun LocalBridgeScreen(
    state: BridgeServiceState,
    onToggleServer: () -> Unit,
    onSendClipboardToPc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val serverRunning = state.status == BridgeServerStatus.RUNNING ||
        state.status == BridgeServerStatus.RUNNING_NO_ADDRESS

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "LOCAL BRIDGE",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (serverRunning) "●" else "○",
                style = MaterialTheme.typography.titleLarge,
            )
            Column {
                Text(
                    text = when (state.status) {
                        BridgeServerStatus.STOPPED -> "Сервер остановлен"
                        BridgeServerStatus.STARTING -> "Сервер запускается"
                        BridgeServerStatus.RUNNING -> "Сервер работает"
                        BridgeServerStatus.RUNNING_NO_ADDRESS -> "Сервер работает"
                        BridgeServerStatus.PERMISSION_REQUIRED -> "Нужен доступ к локальной сети"
                        BridgeServerStatus.ERROR -> "Ошибка сервера"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = state.detail, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.address != null) {
            SelectionContainer {
                Text(
                    text = state.address,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Button(
            onClick = onToggleServer,
            enabled = state.status != BridgeServerStatus.STARTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    serverRunning -> "ОСТАНОВИТЬ СЕРВЕР"
                    state.status == BridgeServerStatus.PERMISSION_REQUIRED -> "РАЗРЕШИТЬ И ЗАПУСТИТЬ СЕРВЕР"
                    else -> "ЗАПУСТИТЬ СЕРВЕР"
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        Text(
            text = "Буфер обмена",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Button(
            onClick = onSendClipboardToPc,
            enabled = serverRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("ОТПРАВИТЬ БУФЕР НА ПК")
        }

        Text(text = state.lastClipboardAction, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()

        Text(
            text = "Файлы",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Будут добавлены после проверки текстового MVP 1.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
