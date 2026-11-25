package eu.kanade.tachiyomi.extension.ar.prochan

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Serves the exact protected page to Mihon's WebView fallback.
 *
 * The website's reader has no URL parameter for a page number: opening the
 * chapter URL always starts at page one. The reader's error screen opens
 * `Page.imageUrl`, so for protected pages that URL points here instead. This
 * tiny loopback server renders the requested page with the same code path as
 * the reader and returns it as JPEG, which every WebView can display.
 *
 * It is loopback-only, uses a random per-process path token, and is only
 * started when a chapter actually contains protected pages.
 */
internal object PageServer {

    private const val HOST = "127.0.0.1"
    private const val MAX_CACHED_PAGES = 8

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "prochan-page-server").apply { isDaemon = true }
    }

    private val token: String by lazy {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    @Volatile
    private var owner: ProChan? = null

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    // A stable port keeps already-cached page URLs valid after Mihon restarts.
    // The short range leaves room if another app happens to use the first one.
    private val preferredPorts = 47_600..47_620

    @Synchronized
    fun start(source: ProChan): Boolean {
        owner = source
        if (server != null) return true

        for (candidate in preferredPorts) {
            if (bind(candidate)) return true
        }
        return bind(0)
    }

    private fun bind(candidate: Int): Boolean {
        return try {
            val socket = ServerSocket(candidate, 16, InetAddress.getByName(HOST))
            server = socket
            port = socket.localPort
            executor.execute { acceptLoop(socket) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns a URL that renders the given protected page, or null when the
     * local server could not be started. The caller falls back to the normal
     * chapter URL in that case.
     */
    fun pageUrl(source: ProChan, chapterId: Long, pageIndex: Int): String? {
        if (!start(source)) return null
        return "http://$HOST:$port/p/$token/page/$chapterId/$pageIndex"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            executor.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input) ?: return
            var headerLines = 0
            while (headerLines++ < 64) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: return
            val segments = path.trim('/').split('/')
            if (segments.size != 5 || segments[0] != "p" || segments[1] != token) {
                respondText(output, "404 Not Found", "Not found")
                return
            }

            val section = segments[2]
            val chapterId = segments[3].toLongOrNull()
            val pageIndex = segments[4].toIntOrNull()
            if (chapterId == null || pageIndex == null || pageIndex < 0) {
                respondText(output, "404 Not Found", "Not found")
                return
            }

            val source = owner
            if (source == null) {
                respondHtml(output, errorHtml("The ProChan source is no longer loaded."))
                return
            }

            when (section) {
                "page" -> {
                    val image = source.renderedOrRender(chapterId, pageIndex)
                    if (image == null) {
                        respondHtml(
                            output,
                            errorHtml(
                                "تعذر تجهيز هذه الصفحة. ارجع إلى Mihon وأعد المحاولة.\n\n" +
                                    source.lastPageError.ifBlank {
                                        HostDecoder.lastError.ifBlank { "Decoder error" }
                                    },
                            ),
                        )
                    } else {
                        respondHtml(output, pageHtml(chapterId, pageIndex))
                    }
                }
                "image" -> {
                    val image = source.renderedOrRender(chapterId, pageIndex)
                    if (image == null) {
                        respondText(output, "503 Service Unavailable", "Page could not be rendered")
                    } else {
                        respondBytes(output, "200 OK", "image/jpeg", image)
                    }
                }
                else -> respondText(output, "404 Not Found", "Not found")
            }
        }
    }

    private fun pageHtml(chapterId: Long, pageIndex: Int): String {
        val imageUrl = "/p/$token/image/$chapterId/$pageIndex"
        return """
            <!doctype html>
            <html lang="ar" dir="rtl">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
              <title>ProChan — صفحة ${pageIndex + 1}</title>
              <style>
                html, body { margin: 0; padding: 0; background: #000; }
                img { display: block; width: 100%; height: auto; margin: 0 auto; }
              </style>
            </head>
            <body><img src="$imageUrl" alt="ProChan page ${pageIndex + 1}"></body>
            </html>
        """.trimIndent()
    }

    private fun errorHtml(message: String): String {
        val escaped = message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
        return """
            <!doctype html>
            <html lang="ar" dir="rtl">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>ProChan — خطأ</title>
              <style>
                html, body { margin: 0; padding: 0; background: #111; color: #eee; font-family: sans-serif; }
                main { padding: 24px; line-height: 1.8; }
                h1 { font-size: 18px; margin: 0 0 12px; }
                p { font-size: 14px; color: #f99; word-break: break-word; }
                a { color: #8cf; }
              </style>
            </head>
            <body><main>
              <h1>تعذر عرض الصفحة</h1>
              <p>$escaped</p>
              <p><a href="?retry=1">إعادة المحاولة</a></p>
            </main></body>
            </html>
        """.trimIndent()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (value == '\n'.code) return buffer.toString().trimEnd('\r')
            if (buffer.length < 16_384) buffer.append(value.toChar())
        }
    }

    private fun respondText(output: OutputStream, status: String, text: String) {
        respondBytes(output, status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }

    private fun respondHtml(output: OutputStream, html: String) {
        respondBytes(output, "200 OK", "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun respondBytes(output: OutputStream, status: String, contentType: String, body: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }
}
