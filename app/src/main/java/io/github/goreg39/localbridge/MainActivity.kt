package io.github.goreg39.localbridge

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private val uiState = mutableStateOf(ServerUiState())
    private var server: LocalHttpServer? = null
    private lateinit var clipboardBridge: ClipboardBridge

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startServer()
        } else {
            uiState.value = uiState.value.copy(
                status = ServerStatus.PERMISSION_REQUIRED,
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
                            onRequestPermission = ::ensureLocalNetworkAccess,
                            onSendClipboardToPc = ::sendClipboardToPc,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )
                    }
                }
            }
        }

        ensureLocalNetworkAccess()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun ensureLocalNetworkAccess() {
        if (Build.VERSION.SDK_INT < ANDROID_17_API) {
            startServer()
            return
        }

        if (checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startServer()
        } else {
            uiState.value = uiState.value.copy(
                status = ServerStatus.PERMISSION_REQUIRED,
                detail = "Нужно разрешение Android «Устройства поблизости / локальная сеть».",
            )
            localNetworkPermissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }

    private fun startServer() {
        if (server != null) return

        uiState.value = uiState.value.copy(
            status = ServerStatus.STARTING,
            detail = "Запускаю локальный HTTP-сервер…",
        )

        val newServer = LocalHttpServer(
            onClipboardFromPc = { text ->
                runOnUiThread {
                    clipboardBridge.writeText(text)
                    uiState.value = uiState.value.copy(
                        lastClipboardAction = "Получено с ПК: ${preview(text)}",
                    )
                }
            },
            onFatalError = { message ->
                runOnUiThread {
                    server = null
                    uiState.value = uiState.value.copy(
                        status = ServerStatus.ERROR,
                        detail = "Сервер остановился: $message",
                    )
                }
            },
        )

        try {
            newServer.start()
            server = newServer

            val ipv4 = LanAddressFinder.findBestIpv4()
            uiState.value = if (ipv4 != null) {
                uiState.value.copy(
                    status = ServerStatus.RUNNING,
                    address = "http://$ipv4:${LocalHttpServer.DEFAULT_PORT}",
                    detail = "Откройте этот адрес в Edge на устройстве в той же локальной сети.",
                )
            } else {
                uiState.value.copy(
                    status = ServerStatus.RUNNING_NO_ADDRESS,
                    address = null,
                    detail = "Сервер запущен, но IPv4 Wi‑Fi или точки доступа пока не найден.",
                )
            }
        } catch (error: Exception) {
            newServer.stop()
            uiState.value = uiState.value.copy(
                status = ServerStatus.ERROR,
                detail = "Не удалось запустить сервер: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun sendClipboardToPc() {
        val activeServer = server ?: return
        val text = clipboardBridge.readText()

        if (text.isNullOrEmpty()) {
            uiState.value = uiState.value.copy(
                lastClipboardAction = "Буфер телефона пуст или недоступен.",
            )
            return
        }

        activeServer.publishClipboardFromPhone(text)
        uiState.value = uiState.value.copy(
            lastClipboardAction = "Отправлено на ПК: ${preview(text)}",
        )
    }

    private fun preview(text: String): String {
        val oneLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (oneLine.length <= 72) oneLine else oneLine.take(69) + "…"
    }

    companion object {
        private const val ANDROID_17_API = 37
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

private data class ServerUiState(
    val status: ServerStatus = ServerStatus.STARTING,
    val address: String? = null,
    val detail: String = "Подготовка…",
    val lastClipboardAction: String = "Передача текста ещё не выполнялась.",
)

private enum class ServerStatus {
    STARTING,
    RUNNING,
    RUNNING_NO_ADDRESS,
    PERMISSION_REQUIRED,
    ERROR,
}

@Composable
private fun LocalBridgeScreen(
    state: ServerUiState,
    onRequestPermission: () -> Unit,
    onSendClipboardToPc: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                text = if (state.status == ServerStatus.RUNNING) "●" else "○",
                style = MaterialTheme.typography.titleLarge,
            )
            Column {
                Text(
                    text = when (state.status) {
                        ServerStatus.STARTING -> "Сервер запускается"
                        ServerStatus.RUNNING -> "Сервер запущен"
                        ServerStatus.RUNNING_NO_ADDRESS -> "Сервер запущен"
                        ServerStatus.PERMISSION_REQUIRED -> "Нужен доступ к локальной сети"
                        ServerStatus.ERROR -> "Ошибка сервера"
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

        if (state.status == ServerStatus.PERMISSION_REQUIRED) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("РАЗРЕШИТЬ ДОСТУП К ЛОКАЛЬНОЙ СЕТИ")
            }
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
            enabled = state.status == ServerStatus.RUNNING || state.status == ServerStatus.RUNNING_NO_ADDRESS,
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
