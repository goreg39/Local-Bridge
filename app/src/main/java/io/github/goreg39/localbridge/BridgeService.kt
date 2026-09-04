package io.github.goreg39.localbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

enum class BridgeServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    RUNNING_NO_ADDRESS,
    PERMISSION_REQUIRED,
    ERROR,
}

data class BridgeServiceState(
    val status: BridgeServerStatus = BridgeServerStatus.STOPPED,
    val address: String? = null,
    val detail: String = "Сервер остановлен.",
    val lastClipboardAction: String = "Передача текста ещё не выполнялась.",
)

class BridgeService : Service() {
    inner class LocalBinder : Binder() {
        val service: BridgeService
            get() = this@BridgeService
    }

    private val binder = LocalBinder()
    private val stateListeners = CopyOnWriteArraySet<(BridgeServiceState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var clipboardBridge: ClipboardBridge
    private var server: LocalHttpServer? = null
    private var foregroundStarted = false

    @Volatile
    private var state = BridgeServiceState()

    override fun onCreate() {
        super.onCreate()
        clipboardBridge = ClipboardBridge(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopBridge()
                START_NOT_STICKY
            }

            else -> {
                startBridge()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        foregroundStarted = false

        if (state.status != BridgeServerStatus.STOPPED) {
            updateState(
                state.copy(
                    status = BridgeServerStatus.STOPPED,
                    address = null,
                    detail = "Сервис Local Bridge остановлен.",
                ),
            )
        }

        super.onDestroy()
    }

    fun currentState(): BridgeServiceState = state

    fun addStateListener(listener: (BridgeServiceState) -> Unit) {
        stateListeners.add(listener)
        listener(state)
    }

    fun removeStateListener(listener: (BridgeServiceState) -> Unit) {
        stateListeners.remove(listener)
    }

    fun publishClipboardFromPhone(text: String?) {
        val activeServer = server
        if (activeServer == null) {
            updateState(
                state.copy(lastClipboardAction = "Сервер не запущен — отправлять текст некуда."),
            )
            return
        }

        if (text.isNullOrEmpty()) {
            updateState(
                state.copy(lastClipboardAction = "Буфер телефона пуст или недоступен."),
            )
            return
        }

        activeServer.publishClipboardFromPhone(text)
        updateState(
            state.copy(lastClipboardAction = "Отправлено на ПК: ${preview(text)}"),
        )
    }

    fun stopBridge() {
        server?.stop()
        server = null

        updateState(
            state.copy(
                status = BridgeServerStatus.STOPPED,
                address = null,
                detail = "Сервер остановлен пользователем.",
            ),
        )

        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun startBridge() {
        if (server != null) {
            ensureForeground()
            return
        }

        updateState(
            state.copy(
                status = BridgeServerStatus.STARTING,
                address = null,
                detail = "Запускаю локальный HTTP-сервер…",
            ),
        )
        ensureForeground()

        val newServer = LocalHttpServer(
            onClipboardFromPc = { text ->
                mainHandler.post {
                    clipboardBridge.writeText(text)
                    updateState(
                        state.copy(lastClipboardAction = "Получено с ПК: ${preview(text)}"),
                    )
                }
            },
            onFatalError = { message ->
                mainHandler.post {
                    handleFatalServerError(message)
                }
            },
        )

        try {
            newServer.start()
            server = newServer

            val ipv4 = LanAddressFinder.findBestIpv4()
            if (ipv4 != null) {
                updateState(
                    state.copy(
                        status = BridgeServerStatus.RUNNING,
                        address = "http://$ipv4:${LocalHttpServer.DEFAULT_PORT}",
                        detail = "Сервер работает в фоне. Activity можно закрыть.",
                    ),
                )
            } else {
                updateState(
                    state.copy(
                        status = BridgeServerStatus.RUNNING_NO_ADDRESS,
                        address = null,
                        detail = "Сервер работает, но локальный IPv4 пока не найден.",
                    ),
                )
            }
        } catch (error: Exception) {
            newServer.stop()
            server = null
            handleFatalServerError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun handleFatalServerError(message: String) {
        server?.stop()
        server = null

        updateState(
            state.copy(
                status = BridgeServerStatus.ERROR,
                address = null,
                detail = "Сервер остановился: $message",
            ),
        )

        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun updateState(newState: BridgeServiceState) {
        state = newState
        stateListeners.forEach { listener ->
            runCatching { listener(newState) }
        }

        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(newState),
            )
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return

        startForeground(
            NOTIFICATION_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        foregroundStarted = true
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Local Bridge server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Состояние локального сервера Local Bridge"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(currentState: BridgeServiceState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, BridgeService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notificationText = when {
            currentState.address != null -> currentState.address
            currentState.status == BridgeServerStatus.STARTING -> "Сервер запускается…"
            else -> currentState.detail
        }

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Local Bridge работает")
            .setContentText(notificationText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stat_bridge, "Остановить", stopPendingIntent)
            .build()
    }

    private fun preview(text: String): String {
        val oneLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (oneLine.length <= 72) oneLine else oneLine.take(69) + "…"
    }

    companion object {
        const val ACTION_START = "io.github.goreg39.localbridge.action.START_SERVER"
        const val ACTION_STOP = "io.github.goreg39.localbridge.action.STOP_SERVER"

        private const val NOTIFICATION_CHANNEL_ID = "local_bridge_server"
        private const val NOTIFICATION_ID = 1001
    }
}
