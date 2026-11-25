package eu.kanade.tachiyomi.extension.ar.prochan

import android.os.Build
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class ApiException(
    val code: Int,
    val body: String,
    message: String,
) : Exception(message)

internal class ProxyPlanResult(
    val map: JSONObject,
    val pass: String,
)

internal class ProChanApi(val plainClient: OkHttpClient) {

    private companion object {
        const val API_BASE = "https://pro-chan.com"
        const val WEB_BASE = "https://procomic.pro"
        const val VERSION_CODE = "96"
        const val USER_AGENT = "okhttp/4.12.0"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // Static fallback key used by the app before a device key is registered.
        const val OBFUSCATED_KEY =
            "2bf4f8dfba5f3b541a9e59268c29e0c92b7544f320df653f6e683a0880147eb00be4e68682742b516c9e681ed124ffd60f4922e723d1426d72432c03e03b5989"
        const val KEY_SEED = "prochan-hmac-obf-static-v2"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private object DeviceState {
        val lock = Any()

        @Volatile
        var deviceId: String? = null

        @Volatile
        var privateKey: PrivateKey? = null

        @Volatile
        var timeOffsetSeconds: Long = 0L
    }

    private val deviceInfo: String by lazy {
        val raw = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; sdk ${Build.VERSION.SDK_INT}"
            .lowercase(Locale.US)
        raw.replace(Regex("[^a-z0-9 ._+()\\-/;]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(96)
    }

    private val apiHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "application/json")
            .build()
    }

    private val webHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", WEB_USER_AGENT)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Referer", "$WEB_BASE/")
            .build()
    }

    private val webJsonHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", WEB_USER_AGENT)
            .add("Accept", "application/json")
            .add("Referer", "$WEB_BASE/")
            .build()
    }

    fun getJson(path: String): JSONObject {
        val url = if (path.startsWith("http")) path else API_BASE + path
        return requestJson("GET", url, null)
    }

    fun getWebDeferred(chapterId: Long, token: String, split: Int): JSONObject {
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val url = "$WEB_BASE/chapter-deferred-media/$chapterId?token=$encodedToken&split=$split"
        val request = GET(url, webJsonHeaders)
        return executeJson(request)
    }

    fun refreshDeferredToken(chapterId: Long, token: String): String? {
        return try {
            val body = JSONObject().put("token", token).toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = POST(
                "$WEB_BASE/chapter-deferred-media/$chapterId/refresh",
                webJsonHeaders.newBuilder().add("Content-Type", "application/json").build(),
                body,
            )
            executeJson(request).optString("token").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun getProxyPlan(
        chapterId: Long,
        map: MapPage,
        pass: String,
    ): ProxyPlanResult {
        var currentPass = pass
        repeat(2) { attempt ->
            val body = JSONObject()
                .put("token", map.token)
                .put("method", map.method)
                .put("cdnPath", map.cdnPath)
                .put("pageIndex", map.pageIndex)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val headers = webJsonHeaders.newBuilder()
                .add("Content-Type", "application/json")
                .add("x-chapter-pass", currentPass)
                .build()

            val request = POST("$WEB_BASE/chapter-map-proxy-plan/$chapterId", headers, body)
            try {
                val root = executeJson(request)
                val data = root.optJSONObject("data") ?: throw Exception("Invalid protected page response")
                val plan = data.optJSONObject("map") ?: throw Exception("Protected page map is missing")
                return ProxyPlanResult(plan, currentPass)
            } catch (e: ApiException) {
                val expired = e.code == 403 && e.body.contains("chapter_pass_expired")
                if (!expired || attempt == 1) throw e
                currentPass = refreshPass(chapterId, currentPass)
            }
        }
        throw Exception("Could not load protected page")
    }

    private fun refreshPass(chapterId: Long, pass: String): String {
        val body = JSONObject().put("pass", pass).toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = POST(
            "$WEB_BASE/chapter-deferred-media/$chapterId/image-pass",
            webJsonHeaders.newBuilder().add("Content-Type", "application/json").build(),
            body,
        )
        val root = executeJson(request)
        val fresh = root.optString("pass")
        if (fresh.isBlank()) throw Exception("Could not renew chapter pass")
        return fresh
    }

    fun getAppImages(slug: String, number: String, chapterId: Long): List<String> {
        if (slug.isBlank() || number.isBlank()) return emptyList()
        val url = "$WEB_BASE/ar/chapter/$slug-$number-$chapterId"
        plainClient.newCall(GET(url, webHeaders)).execute().use { response ->
            val html = response.body.string()
            if (!response.isSuccessful) return emptyList()
            return parseAppImages(html)
        }
    }

    fun fetchPiece(url: String): ByteArray {
        val finalUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> "https://img1.procomic.im/${url.trimStart('/')}"
        }
        val request = Request.Builder()
            .url(finalUrl)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Referer", "$WEB_BASE/")
            .build()
        plainClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "", "Piece HTTP ${response.code}")
            return response.body.bytes()
        }
    }

    private fun requestJson(method: String, url: String, bodyString: String?): JSONObject {
        repeat(2) { attempt ->
            ensureDevice()
            val bodyBytes = bodyString?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val headers = signedHeaders(method, url, bodyBytes)
            val body = bodyString?.toRequestBody(JSON_MEDIA_TYPE)
            val request = when (method) {
                "POST" -> POST(url, headers, body ?: ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
                else -> GET(url, headers)
            }

            plainClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                val root = runCatching { JSONObject(text) }.getOrNull()

                if (response.code == 401 && attempt == 0) {
                    val code = root?.optString("errorCode").orEmpty()
                    if (code == "stale_timestamp" || text.contains("stale_timestamp")) {
                        adjustClock(response.header("Date"))
                        return@repeat
                    }
                    if (code == "device_required" || text.contains("device_required")) {
                        resetDevice()
                        return@repeat
                    }
                    if (code == "invalid_signature" || text.contains("invalid_signature") || text.contains("signature verification failed")) {
                        resetDevice()
                        return@repeat
                    }
                }

                if (!response.isSuccessful) {
                    throw ApiException(response.code, text, "HTTP ${response.code} for $url")
                }
                return root ?: throw Exception("Invalid API response for $url")
            }
        }
        throw Exception("Request failed for $url")
    }

    private fun executeJson(request: Request): JSONObject {
        plainClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw ApiException(response.code, text, "HTTP ${response.code}")
            }
            return runCatching { JSONObject(text) }.getOrElse { throw Exception("Invalid JSON response") }
        }
    }

    private fun ensureDevice() {
        if (DeviceState.deviceId != null && DeviceState.privateKey != null) return
        synchronized(DeviceState.lock) {
            if (DeviceState.deviceId != null && DeviceState.privateKey != null) return
            registerDevice()
        }
    }

    private fun registerDevice() {
        repeat(2) { attempt ->
            val pair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

            val body = JSONObject()
                .put("publicKeyDer", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
                .toString()
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val url = "$API_BASE/api/android/device/register"
            val headers = staticHeaders("POST", url, bodyBytes)
            val request = POST(url, headers, body.toRequestBody(JSON_MEDIA_TYPE))

            plainClient.newCall(request).execute().use { response ->
                val text = response.body.string()

                if (response.code == 401 && attempt == 0 && text.contains("stale_timestamp")) {
                    adjustClock(response.header("Date"))
                    return@repeat
                }
                if (!response.isSuccessful) {
                    throw ApiException(response.code, text, "Device registration failed (HTTP ${response.code})")
                }

                val root = runCatching { JSONObject(text) }.getOrElse { throw Exception("Invalid registration response") }
                val id = root.optString("deviceId")
                    .ifBlank { root.optJSONObject("data")?.optString("deviceId").orEmpty() }
                if (id.isBlank()) throw Exception("Device registration did not return an id")

                DeviceState.privateKey = pair.private
                DeviceState.deviceId = id
                return
            }
        }
        throw Exception("Device registration failed")
    }

    private fun resetDevice() {
        DeviceState.deviceId = null
        DeviceState.privateKey = null
    }

    private fun signedHeaders(method: String, url: String, bodyBytes: ByteArray): Headers {
        val id = DeviceState.deviceId ?: throw Exception("Device is not registered")
        val key = DeviceState.privateKey ?: throw Exception("Device key is missing")
        val uri = URI(url)
        val pathQuery = buildString {
            append(uri.rawPath.ifBlank { "/" })
            uri.rawQuery?.let {
                append('?')
                append(it)
            }
        }
        val timestamp = (System.currentTimeMillis() / 1000 + DeviceState.timeOffsetSeconds).toString()
        val nonce = randomNonce()
        val signing = listOf(
            method.uppercase(Locale.US),
            pathQuery,
            sha256Hex(bodyBytes),
            timestamp,
            nonce,
            id,
            uri.host.lowercase(Locale.US),
            "",
        ).joinToString("\n")

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(signing.toByteArray(Charsets.UTF_8))
            sign()
        }

        return apiHeaders.newBuilder()
            .add("X-ProChan-Client", "android-app")
            .add("X-ProChan-Version", VERSION_CODE)
            .add("X-ProChan-Device-Info", deviceInfo)
            .add("X-ProChan-Device-Id", id)
            .add("X-ProChan-Timestamp", timestamp)
            .add("X-ProChan-Nonce", nonce)
            .add("X-ProChan-Signature", signature.toHex())
            .build()
    }

    private fun staticHeaders(method: String, url: String, bodyBytes: ByteArray): Headers {
        val uri = URI(url)
        val pathQuery = buildString {
            append(uri.rawPath.ifBlank { "/" })
            uri.rawQuery?.let {
                append('?')
                append(it)
            }
        }
        val timestamp = (System.currentTimeMillis() / 1000 + DeviceState.timeOffsetSeconds).toString()
        val nonce = randomNonce()
        val signing = listOf(
            method.uppercase(Locale.US),
            pathQuery,
            sha256Hex(bodyBytes),
            timestamp,
            nonce,
            "",
            uri.host.lowercase(Locale.US),
            "",
        ).joinToString("\n")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(staticKey(), "HmacSHA256"))
        val signature = mac.doFinal(signing.toByteArray(Charsets.UTF_8))

        return apiHeaders.newBuilder()
            .add("X-ProChan-Client", "android-app")
            .add("X-ProChan-Version", VERSION_CODE)
            .add("X-ProChan-Device-Info", deviceInfo)
            .add("X-ProChan-Timestamp", timestamp)
            .add("X-ProChan-Nonce", nonce)
            .add("X-ProChan-Signature", signature.toHex())
            .add("Content-Type", "application/json; charset=utf-8")
            .build()
    }

    private fun staticKey(): ByteArray {
        val obfuscated = OBFUSCATED_KEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").run {
            update("com.prochan.android".toByteArray(Charsets.UTF_8))
            update(0.toByte())
            update(KEY_SEED.toByteArray(Charsets.UTF_8))
            digest()
        }
        return ByteArray(obfuscated.size) { index ->
            (obfuscated[index].toInt() xor digest[index % digest.size].toInt()).toByte()
        }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun ByteArray.toHex(): String {
        val chars = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            out.append(chars[value ushr 4])
            out.append(chars[value and 0x0f])
        }
        return out.toString()
    }

    private fun adjustClock(dateHeader: String?) {
        if (dateHeader.isNullOrBlank()) return
        try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val serverMillis = format.parse(dateHeader)?.time ?: return
            DeviceState.timeOffsetSeconds = (serverMillis / 1000) - (System.currentTimeMillis() / 1000)
        } catch (_: Throwable) {
        }
    }

    private fun parseAppImages(html: String): List<String> {
        // The Next.js flight payload escapes quotes: appImages\":[{"mobile":"...","desktop":"..."}]
        val marker = "appImages\\\":["
        val start = html.indexOf(marker)
        if (start < 0) return emptyList()
        val arrayStart = start + marker.length - 1
        val arrayEnd = html.indexOf(']', arrayStart)
        if (arrayEnd <= arrayStart) return emptyList()

        val raw = html.substring(arrayStart, arrayEnd + 1)
            .replace("\\\"", "\"")
            .replace("\\/", "/")
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val images = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("desktop").ifBlank { item.optString("mobile") }
            if (url.isNotBlank()) images.add(url)
        }
        return images
    }
}




