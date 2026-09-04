package io.github.goreg39.localbridge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalHttpServer(
    private val port: Int = DEFAULT_PORT,
    private val onClipboardFromPc: (String) -> Unit = {},
    private val onFatalError: (String) -> Unit = {},
) {
    @Volatile
    private var running = false

    @Volatile
    private var latestClipboardFromPhone: String? = null

    private var serverSocket: ServerSocket? = null
    private val sseClients = CopyOnWriteArrayList<SseClient>()

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
        sseClients.forEach { it.close() }
        sseClients.clear()
        executor.shutdownNow()
    }

    fun publishClipboardFromPhone(text: String) {
        latestClipboardFromPhone = text
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

        sseClients.forEach { client ->
            if (!client.sendClipboard(encoded)) {
                sseClients.remove(client)
                client.close()
            }
        }
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
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000

            val request = try {
                readRequest(socket.getInputStream())
            } catch (_: Exception) {
                sendResponse(socket.getOutputStream(), 400, "Bad Request", "text/plain; charset=utf-8", "bad request\n")
                return
            } ?: return

            when {
                request.method == "GET" && request.path == "/events" -> {
                    socket.soTimeout = 0
                    serveEvents(socket)
                }

                request.method == "POST" && request.path == "/api/clipboard" -> {
                    onClipboardFromPc(request.body)
                    sendResponse(socket.getOutputStream(), 204, "No Content", "text/plain; charset=utf-8", "")
                }

                request.method == "GET" && request.path == "/health" -> {
                    sendResponse(socket.getOutputStream(), 200, "OK", "text/plain; charset=utf-8", "ok\n")
                }

                request.method == "GET" && request.path == "/" -> {
                    sendResponse(socket.getOutputStream(), 200, "OK", "text/html; charset=utf-8", HOME_PAGE)
                }

                else -> {
                    sendResponse(socket.getOutputStream(), 404, "Not Found", "text/plain; charset=utf-8", "not found\n")
                }
            }
        }
    }

    private fun serveEvents(socket: Socket) {
        val output = socket.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache, no-store\r\n")
            append("Connection: keep-alive\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(headers)
        output.flush()

        val sseClient = SseClient(socket, output)
        sseClients.add(sseClient)

        latestClipboardFromPhone?.let { text ->
            val encoded = Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))
            if (!sseClient.sendClipboard(encoded)) return
        }

        try {
            while (running && !socket.isClosed) {
                Thread.sleep(15_000)
                if (!sseClient.ping()) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            sseClients.remove(sseClient)
            sseClient.close()
        }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var state = 0

        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value == -1) return null
            headerBytes.write(value)

            state = when {
                state == 0 && value == '\r'.code -> 1
                state == 1 && value == '\n'.code -> 2
                state == 2 && value == '\r'.code -> 3
                state == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }

            if (state == 4) break
        }

        if (state != 4) error("HTTP headers too large")

        val headerText = headerBytes.toString(StandardCharsets.US_ASCII.name())
        val lines = headerText.split("\r\n")
        val requestParts = lines.firstOrNull()?.split(' ') ?: return null
        if (requestParts.size < 2) return null

        val method = requestParts[0].uppercase()
        val path = requestParts[1].substringBefore('?')
        val headers = lines
            .drop(1)
            .filter { it.contains(':') }
            .associate { line ->
                val separator = line.indexOf(':')
                line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        require(contentLength in 0..MAX_BODY_BYTES) { "Invalid Content-Length" }

        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(bodyBytes, offset, contentLength - offset)
            if (count <= 0) error("Unexpected end of request body")
            offset += count
        }

        return HttpRequest(
            method = method,
            path = path,
            body = String(bodyBytes, StandardCharsets.UTF_8),
        )
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String,
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)

        output.write(headers)
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private class SseClient(
        private val socket: Socket,
        private val output: OutputStream,
    ) {
        @Synchronized
        fun sendClipboard(encodedText: String): Boolean = send(
            "event: clipboard\ndata: $encodedText\n\n",
        )

        @Synchronized
        fun ping(): Boolean = send(": ping\n\n")

        @Synchronized
        private fun send(payload: String): Boolean = try {
            output.write(payload.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            true
        } catch (_: Exception) {
            false
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024

        private val HOME_PAGE = """
            <!doctype html>
            <html lang="ru">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Local Bridge</title>
              <style>
                * { box-sizing: border-box; }
                body { font-family: system-ui, sans-serif; margin: 32px auto; max-width: 820px; padding: 0 20px 40px; line-height: 1.45; color: #1f1f1f; }
                h1 { margin-bottom: 6px; }
                h2 { margin: 28px 0 10px; font-size: 1.1rem; }
                textarea { width: 100%; min-height: 180px; resize: vertical; padding: 12px; font: 14px/1.4 Consolas, monospace; }
                button { margin-top: 10px; padding: 10px 16px; font-weight: 650; cursor: pointer; }
                .status { margin-top: 12px; min-height: 24px; color: #555; }
                .connection { font-size: 0.9rem; color: #666; }
                hr { border: 0; border-top: 1px solid #ddd; margin: 28px 0; }
              </style>
            </head>
            <body>
              <h1>LOCAL BRIDGE</h1>
              <div id="connection" class="connection">Подключение к телефону…</div>

              <h2>ТЕЛЕФОН → ПК</h2>
              <textarea id="phoneText" readonly placeholder="После нажатия кнопки в приложении текст появится здесь без F5."></textarea>
              <br>
              <button id="copyButton" type="button">КОПИРОВАТЬ</button>

              <hr>

              <h2>ПК → ТЕЛЕФОН</h2>
              <textarea id="pcText" placeholder="Вставьте сюда вывод PowerShell или другой текст."></textarea>
              <br>
              <button id="sendButton" type="button">В БУФЕР ТЕЛЕФОНА</button>

              <div id="status" class="status"></div>

              <script>
                const phoneText = document.getElementById('phoneText');
                const pcText = document.getElementById('pcText');
                const status = document.getElementById('status');
                const connection = document.getElementById('connection');

                function decodeUtf8Base64(value) {
                  const bytes = Uint8Array.from(atob(value), ch => ch.charCodeAt(0));
                  return new TextDecoder().decode(bytes);
                }

                const events = new EventSource('/events');
                events.onopen = () => { connection.textContent = '● Соединение с телефоном активно'; };
                events.onerror = () => { connection.textContent = '○ Переподключение к телефону…'; };
                events.addEventListener('clipboard', event => {
                  phoneText.value = decodeUtf8Base64(event.data);
                  status.textContent = 'Получено с телефона.';
                });

                document.getElementById('sendButton').addEventListener('click', async () => {
                  try {
                    const response = await fetch('/api/clipboard', {
                      method: 'POST',
                      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
                      body: pcText.value,
                    });
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    status.textContent = 'Текст помещён в буфер телефона.';
                  } catch (error) {
                    status.textContent = 'Ошибка отправки: ' + error.message;
                  }
                });

                document.getElementById('copyButton').addEventListener('click', async () => {
                  const text = phoneText.value;
                  if (!text) {
                    status.textContent = 'С телефона пока ничего не получено.';
                    return;
                  }

                  try {
                    if (!navigator.clipboard || !window.isSecureContext) throw new Error('fallback');
                    await navigator.clipboard.writeText(text);
                    status.textContent = 'Скопировано.';
                    return;
                  } catch (_) {
                    phoneText.focus();
                    phoneText.select();
                    const copied = document.execCommand('copy');
                    status.textContent = copied ? 'Скопировано.' : 'Текст выделен — нажмите Ctrl+C.';
                  }
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
