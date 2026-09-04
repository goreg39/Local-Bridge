package io.github.goreg39.localbridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalHttpServer(
    private val port: Int = DEFAULT_PORT,
    private val onFatalError: (String) -> Unit = {},
) {
    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null

    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "local-bridge-http").apply { isDaemon = true }
    }

    fun start() {
        check(!running) { "Server is already running" }

        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("0.0.0.0", port))
        }

        serverSocket = socket
        running = true
        executor.execute { acceptLoop(socket) }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                executor.execute { handleClient(client) }
            } catch (error: Exception) {
                if (running) {
                    running = false
                    onFatalError(error.message ?: error.javaClass.simpleName)
                }
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5_000

            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII),
            )

            val requestLine = reader.readLine().orEmpty()
            var headerLine: String?
            do {
                headerLine = reader.readLine()
            } while (!headerLine.isNullOrEmpty())

            val path = requestLine
                .split(' ')
                .getOrNull(1)
                ?.substringBefore('?')
                ?: "/"

            val response = when (path) {
                "/health" -> HttpResponse(
                    contentType = "text/plain; charset=utf-8",
                    body = "ok\n",
                )

                else -> HttpResponse(
                    contentType = "text/html; charset=utf-8",
                    body = HOME_PAGE,
                )
            }

            val bodyBytes = response.body.toByteArray(StandardCharsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)

            socket.getOutputStream().use { output ->
                output.write(headers)
                output.write(bodyBytes)
                output.flush()
            }
        }
    }

    private data class HttpResponse(
        val contentType: String,
        val body: String,
    )

    companion object {
        const val DEFAULT_PORT = 8765

        private val HOME_PAGE = """
            <!doctype html>
            <html lang="ru">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Local Bridge</title>
              <style>
                body { font-family: sans-serif; margin: 40px auto; max-width: 640px; padding: 0 20px; line-height: 1.5; }
                .ok { font-weight: 700; }
                code { background: #eee; padding: 2px 6px; border-radius: 4px; }
              </style>
            </head>
            <body>
              <h1>Local Bridge</h1>
              <p class="ok">Соединение с телефоном работает.</p>
              <p>Это первый сетевой тест MVP 1. Передача буфера обмена будет добавлена следующим этапом.</p>
              <p>Проверка сервера: <code>/health</code></p>
            </body>
            </html>
        """.trimIndent()
    }
}
