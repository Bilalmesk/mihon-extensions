package eu.kanade.tachiyomi.extension.ar.prochan

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Serves the exact protected page to Mihon's WebView fallback.
 *
 * The website reader has no page URL parameter, so opening the chapter always
 * starts at page one. This loopback server instead receives the page's proxy
 * plan (piece URLs, order, and rectangles), builds a small HTML page, and lets
 * Chromium decode and compose the AVIF pieces itself. That path works even
 * when Mihon's own native decoder is unavailable.
 *
 * It is loopback-only, uses a random per-process path token, and only exposes
 * pieces hosted by ProChan.
 */
internal object PageServer {

    private const val HOST = "127.0.0.1"

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

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            executor.execute { runCatching { handle(client) } }
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

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 20_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input) ?: return
            var headerLines = 0
            while (headerLines++ < 64) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            val target = requestLine.split(' ').getOrNull(1) ?: return
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val segments = path.trim('/').split('/')
            // The token segment is intentionally not checked: old versions
            // stored loopback URLs with a previous process token, and those
            // cached pages should still open. The server is loopback-only and
            // only proxies ProChan hosts.
            if (segments.size < 3 || segments[0] != "p") {
                respondText(output, "404 Not Found", "Not found")
                return
            }

            val source = owner
            if (source == null) {
                respondHtml(output, errorHtml("The ProChan source is no longer loaded."))
                return
            }

            when (segments[2]) {
                "page", "image" -> {
                    val chapterId = segments.getOrNull(3)?.toLongOrNull()
                    val pageIndex = segments.getOrNull(4)?.toIntOrNull()
                    if (segments.size != 5 || chapterId == null || pageIndex == null || pageIndex < 0) {
                        respondText(output, "404 Not Found", "Not found")
                        return
                    }

                    val map = source.proxyPlanForPage(chapterId, pageIndex)
                    if (map == null) {
                        respondHtml(
                            output,
                            errorHtml(
                                "تعذر تجهيز هذه الصفحة. ارجع إلى Mihon وأعد المحاولة.\n\n" +
                                    source.lastPageError.ifBlank { "Protected page plan failed" },
                            ),
                        )
                    } else {
                        respondHtml(output, pageHtml(pageIndex, map))
                    }
                }
                "piece" -> {
                    val pieceUrl = queryParameter(query, "u")
                    if (pieceUrl.isNullOrBlank() || !isAllowedPieceUrl(pieceUrl)) {
                        respondText(output, "400 Bad Request", "Invalid piece")
                        return
                    }
                    val bytes = try {
                        source.fetchPieceBytes(pieceUrl)
                    } catch (e: Throwable) {
                        respondText(
                            output,
                            "502 Bad Gateway",
                            "Piece fetch failed: ${e.message.orEmpty().take(160)}",
                        )
                        return
                    }
                    respondBytes(output, "200 OK", contentType(bytes), bytes)
                }
                else -> respondText(output, "404 Not Found", "Not found")
            }
        }
    }

    private fun pageHtml(pageIndex: Int, map: JSONObject): String {
        val dim = map.optJSONArray("dim")
        val width = max(1, dim?.optInt(0) ?: 1)
        val height = max(1, dim?.optInt(1) ?: 1)
        val pieces = map.optJSONArray("pieces") ?: JSONArray()
        val rects = map.optJSONArray("rects")
            ?: MapImageRenderer.computeRects(width, height, map.optString("mode"), pieces.length())
        val order = map.optJSONArray("order")

        val tiles = StringBuilder()
        for (index in 0 until rects.length()) {
            val rect = rects.optJSONObject(index) ?: continue
            val pieceIndex = if (order != null && index < order.length()) {
                order.optInt(index, index)
            } else {
                index
            }
            if (pieceIndex < 0 || pieceIndex >= pieces.length()) continue
            val pieceUrl = pieces.optString(pieceIndex)
            if (pieceUrl.isBlank()) continue

            val encoded = URLEncoder.encode(pieceUrl, "UTF-8")
            val left = rect.optDouble("left", 0.0) * 100.0 / width
            val top = rect.optDouble("top", 0.0) * 100.0 / height
            val tileWidth = rect.optDouble("width", width.toDouble()) * 100.0 / width
            val tileHeight = rect.optDouble("height", height.toDouble()) * 100.0 / height

            tiles.append("<img src=\"/p/")
                .append(token)
                .append("/piece?u=")
                .append(encoded)
                .append("\" style=\"left:")
                .append(percent(left))
                .append("%;top:")
                .append(percent(top))
                .append("%;width:")
                .append(percent(tileWidth))
                .append("%;height:")
                .append(percent(tileHeight))
                .append("%\" alt=\"\" decoding=\"async\">")
        }

        val ratio = height * 100.0 / width
        return """
            <!doctype html>
            <html lang="ar" dir="rtl">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
              <title>ProChan — صفحة ${pageIndex + 1}</title>
              <style>
                html, body { margin: 0; padding: 0; background: #000; }
                .page { position: relative; width: 100%; padding-bottom: ${percent(ratio)}%; background: #fff; overflow: hidden; }
                .page img { position: absolute; display: block; max-width: none; }
              </style>
            </head>
            <body><div class="page">$tiles</div></body>
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

    private fun queryParameter(query: String, name: String): String? {
        for (part in query.split('&')) {
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            if (part.substring(0, separator) != name) continue
            return runCatching {
                URLDecoder.decode(part.substring(separator + 1), "UTF-8")
            }.getOrNull()
        }
        return null
    }

    private fun isAllowedPieceUrl(url: String): Boolean {
        if (url.startsWith("/") && !url.startsWith("//")) return true
        val normalized = if (url.startsWith("//")) "https:$url" else url
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == "procomic.pro" ||
            host.endsWith(".procomic.pro") ||
            host == "procomic.im" ||
            host.endsWith(".procomic.im")
    }

    private fun contentType(bytes: ByteArray): String {
        if (bytes.size >= 12 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
            val brand = String(bytes, 8, 4, Charsets.ISO_8859_1)
            return when (brand) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "mif1", "msf1" -> "image/heic"
                else -> "image/avif"
            }
        }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        ) {
            return "image/webp"
        }
        return "application/octet-stream"
    }

    private fun percent(value: Double): String = String.format(Locale.US, "%.4f", value)

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (value == '\n'.code) return buffer.toString().trimEnd('\r')
            if (buffer.length < 32_768) buffer.append(value.toChar())
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
