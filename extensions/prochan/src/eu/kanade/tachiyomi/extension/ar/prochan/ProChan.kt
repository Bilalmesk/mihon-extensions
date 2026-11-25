package eu.kanade.tachiyomi.extension.ar.prochan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

internal data class MapPage(
    val token: String,
    val method: String,
    val cdnPath: String,
    val pageIndex: Int,
)

internal data class ChapterPageData(
    val publicImages: List<String>,
    val maps: List<MapPage>,
    val pass: String,
)

class ProChan : HttpSource() {

    override val name = "ProChan"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    override fun getHomeUrl(): String = "https://procomic.net/ar"

    private val api by lazy { ProChanApi(network.client) }

    private val pageCache = ConcurrentHashMap<Long, ChapterPageData>()
    private val cdnPathCache = ConcurrentHashMap<Long, String>()

    @Volatile
    internal var lastPageError: String = ""

    init {
        // Bind the exact-page WebView fallback off the main thread. Cached
        // page lists can already contain a loopback URL from an earlier
        // session, so start it when the source is created instead of waiting
        // for the first protected page to load.
        Thread({ PageServer.start(this) }, "prochan-page-server-init").apply {
            isDaemon = true
            start()
        }
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(MapImageInterceptor(this))
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        .add("Referer", "https://procomic.pro/")

    private val imageHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .build()
    }

    override fun getFilterList(): FilterList = FilterList()

    override suspend fun getPopularManga(page: Int): MangasPage {
        val root = api.getJson("/api/android/feed/all-series?page=$page&limit=50&sort=popular")
        return parseMangaList(root, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val root = api.getJson("/api/android/feed/latest-updates?page=$page&limit=50")
        val data = root.optJSONArray("data") ?: JSONArray()
        val seen = HashSet<Long>()
        val mangas = mutableListOf<SManga>()

        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            if (!isVisualType(item.optString("type"))) continue

            val id = item.optLong("mangaId", 0L)
            if (id <= 0L || !seen.add(id)) continue
            val slug = item.optString("mangaSlug")
            if (slug.isBlank()) continue

            mangas.add(
                SManga.create().apply {
                    url = "/$id/$slug"
                    title = item.optString("mangaTitle").ifBlank { slug }
                    thumbnail_url = fixImageUrl(item.optString("coverImage"))
                    initialized = false
                },
            )
            if (mangas.size >= 50) break
        }

        val hasNext = root.optJSONObject("meta")?.optBoolean("hasMore", false) ?: false
        return MangasPage(mangas, hasNext)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val root = api.getJson(
            "/api/android/feed/all-series?page=$page&limit=50&sort=popular&search=$encoded",
        )
        return parseMangaList(root, page)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val id = mangaIdFromUrl(manga.url)
        val detail = api.getJson("/api/android/content/$id").getJSONObject("data")
        val updatedManga = if (fetchDetails) parseDetails(detail, manga) else manga
        val updatedChapters = if (fetchChapters) {
            getChapters(id, detail.optString("slug"))
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split('|')
        val chapterId = parts.getOrNull(0)?.toLongOrNull()
            ?: throw Exception("Invalid chapter url")
        val slug = parts.getOrNull(1).orEmpty()
        val number = parts.getOrNull(2).orEmpty()
        val cdnHint = parts.getOrNull(3).orEmpty()

        val data = loadChapterPages(chapterId, slug, number, cdnHint)
        val pages = mutableListOf<Page>()

        data.publicImages.forEach { url ->
            pages.add(Page(pages.size, url, url))
        }
        data.maps.forEach { map ->
            val url = if (slug.isNotBlank() && number.isNotBlank()) {
                "$baseUrl/ar/chapter/$slug-$number-$chapterId?__map__=$chapterId:${map.pageIndex}"
            } else {
                "$baseUrl/__map__/$chapterId/${map.pageIndex}"
            }
            // Leave imageUrl null so it is resolved when the page loads.
            // That keeps cached page lists from storing a stale loopback port,
            // and lets the error screen open the exact page in WebView.
            pages.add(Page(pages.size, url))
        }

        if (pages.isEmpty()) throw Exception("No pages found for this chapter")
        return pages
    }

    private fun loadProxyPlanMap(chapterId: Long, pageIndex: Int): JSONObject {
        val data = pageCache[chapterId] ?: loadChapterPages(chapterId, "", "", "")
        val map = data.maps.firstOrNull { it.pageIndex == pageIndex }
            ?: throw Exception("Protected page is no longer available")

        val result = try {
            api.getProxyPlan(chapterId, map, data.pass)
        } catch (e: ApiException) {
            lockedMessage(e.body)?.let { throw Exception(it, e) }
            throw e
        }
        if (result.pass != data.pass) {
            pageCache[chapterId] = data.copy(pass = result.pass)
        }
        return result.map
    }

    internal fun renderMapPage(chapterId: Long, pageIndex: Int): ByteArray {
        return MapImageRenderer.render(api, loadProxyPlanMap(chapterId, pageIndex))
    }

    /**
     * Returns the proxy plan for [PageServer]. The server turns the pieces
     * into an HTML page so the WebView decodes them with Chromium's AVIF
     * decoder, which works even when Mihon's own decoder is unavailable.
     */
    internal fun proxyPlanForPage(chapterId: Long, pageIndex: Int): JSONObject? {
        return try {
            lastPageError = ""
            loadProxyPlanMap(chapterId, pageIndex)
        } catch (e: Throwable) {
            lastPageError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    internal fun fetchPieceBytes(url: String): ByteArray = api.fetchPiece(url)

    private fun loadChapterPages(chapterId: Long, slug: String, number: String, cdnHint: String): ChapterPageData {
        val root = try {
            api.getJson("/api/android/chapter/$chapterId")
        } catch (e: ApiException) {
            lockedMessage(e.body)?.let { throw Exception(it, e) }
            throw e
        }
        val data = root.optJSONObject("data") ?: throw Exception("Chapter not found")

        val deferredMedia = data.optJSONObject("deferredMedia")
        if (deferredMedia == null) {
            val images = data.optJSONArray("images").toStringList()
            if (images.isNotEmpty()) {
                val result = ChapterPageData(images, emptyList(), "")
                pageCache[chapterId] = result
                return result
            }
            throw Exception("This chapter is not readable")
        }
        val token = deferredMedia.optString("token")
        if (token.isBlank()) {
            val images = data.optJSONArray("images").toStringList()
            if (images.isNotEmpty()) {
                val result = ChapterPageData(images, emptyList(), "")
                pageCache[chapterId] = result
                return result
            }
            throw Exception("Could not get the chapter token")
        }
        val split = deferredMedia.optInt("triggerAfterImageIndex", 1).coerceAtLeast(1)

        val series = data.optJSONObject("series")
        val seriesSlug = series?.optString("slug").orEmpty().ifBlank { slug }
        val chapterNumber = data.optString("chapter_number").ifBlank { number }
        val cdnPath = resolveCdnPath(cdnHint, data.optString("cdn_path"), series?.optLong("id", 0L) ?: 0L)
        val androidSplit = deferredMedia.optInt("triggerAfterImageIndex", 1).coerceAtLeast(1)

        val webInfo = try {
            api.getWebChapterInfo(seriesSlug, chapterNumber, chapterId)
        } catch (_: Throwable) {
            null
        }

        var deferred: JSONObject? = null
        var deferredError: ApiException? = null

        // The reader page's own token is freshest and usually works first try.
        if (webInfo != null && webInfo.token.isNotBlank()) {
            try {
                deferred = api.getWebDeferred(chapterId, webInfo.token, webInfo.split)
            } catch (e: ApiException) {
                lockedMessage(e.body)?.let { throw Exception(it, e) }
                deferredError = e
                if (e.code == 403) runCatching { Thread.sleep(2500) }
            }
        }

        // Fall back to the Android app token, refreshing it only if needed.
        if (deferred == null) {
            try {
                deferred = api.getWebDeferred(chapterId, token, androidSplit)
            } catch (first: ApiException) {
                lockedMessage(first.body)?.let { throw Exception(it, first) }
                deferredError = first
                if (first.code == 403) runCatching { Thread.sleep(2500) }
                val fresh = api.refreshDeferredToken(chapterId, token)
                if (fresh != null && fresh != token) {
                    deferred = api.getWebDeferred(chapterId, fresh, androidSplit)
                }
            }
        }

        val deferredRoot = deferred ?: throw (deferredError ?: Exception("Could not load chapter pages"))
        val deferredData = deferredRoot.optJSONObject("data") ?: deferredRoot
        val pass = deferredData.optString("pass")

        val appImages = webInfo?.images.orEmpty()
        val publicImages = mutableListOf<String>()
        if (appImages.isNotEmpty()) {
            publicImages.addAll(appImages)
        } else {
            publicImages.addAll(data.optJSONArray("images").toStringList())
            publicImages.addAll(deferredData.optJSONArray("images").toStringList())
        }

        val maps = mutableListOf<MapPage>()
        val mapsArray = deferredData.optJSONArray("maps") ?: JSONArray()
        for (index in 0 until mapsArray.length()) {
            val item = mapsArray.optJSONObject(index) ?: continue
            val mapToken = item.optString("token")
            if (mapToken.isBlank()) continue
            maps.add(
                MapPage(
                    token = mapToken,
                    method = item.optString("method", "browser_session"),
                    cdnPath = cdnPath,
                    pageIndex = publicImages.size + index,
                ),
            )
        }

        if (maps.isEmpty() && appImages.isEmpty()) {
            publicImages.addAll(deferredData.optJSONArray("images").toStringList())
        }

        val result = ChapterPageData(publicImages.distinct(), maps, pass)
        pageCache[chapterId] = result
        return result
    }

    private fun parseMangaList(root: JSONObject, page: Int): MangasPage {
        val data = root.optJSONArray("data") ?: JSONArray()
        val mangas = mutableListOf<SManga>()

        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            if (!isVisualType(item.optString("type"))) continue

            val id = item.optLong("id", 0L)
            val slug = item.optString("slug")
            if (id <= 0L || slug.isBlank()) continue

            mangas.add(
                SManga.create().apply {
                    url = "/$id/$slug"
                    title = item.optString("title").ifBlank { slug }
                    thumbnail_url = fixImageUrl(
                        item.optJSONObject("coverImageApp")?.optString("mobile")
                            .orEmpty()
                            .ifBlank { item.optJSONObject("metadata")?.optString("appCoverImage").orEmpty() }
                            .ifBlank { item.optString("coverImage") },
                    )
                    genre = parseGenres(item)
                    description = item.optString("description")
                    status = parseStatus(item.optString("progress"))
                    initialized = false
                },
            )
        }

        val meta = root.optJSONObject("meta")
        val totalPages = meta?.optInt("pages", 0) ?: 0
        val hasNext = if (totalPages > 0) page < totalPages else data.length() >= 50

        return MangasPage(mangas, hasNext)
    }

    private fun parseDetails(detail: JSONObject, manga: SManga): SManga {
        val metadata = detail.optJSONObject("metadata") ?: JSONObject()
        val genres = detail.optJSONArray("genres")
            ?: metadata.optJSONArray("genres")
        val genreText = buildList {
            if (genres != null) {
                for (index in 0 until genres.length()) {
                    val item = genres.opt(index)
                    when (item) {
                        is JSONObject -> add(item.optString("ar").ifBlank { item.optString("en") })
                        is String -> add(item)
                    }
                }
            }
        }.filter { it.isNotBlank() }.joinToString(", ")

        return SManga.create().apply {
            url = manga.url
            title = detail.optString("title").ifBlank { manga.title }
            thumbnail_url = fixImageUrl(
                detail.optJSONObject("coverImageApp")?.optString("mobile")
                    .orEmpty()
                    .ifBlank { metadata.optString("appCoverImage") }
                    .ifBlank { detail.optString("coverImage") }
                    .ifBlank { manga.thumbnail_url.orEmpty() },
            )
            author = metadata.optString("author").takeUnless { it.equals("unknown", true) }.orEmpty()
            artist = metadata.optString("artist").takeUnless { it.equals("unknown", true) }.orEmpty()
            description = detail.optString("description")
                .ifBlank { metadata.optJSONObject("descriptions")?.optString("ar").orEmpty() }
            genre = genreText
            status = parseStatus(detail.optString("progress"))
            initialized = true
        }
    }

    private fun getChapters(mangaId: Long, slug: String): List<SChapter> {
        data class ParsedChapter(val chapter: SChapter, val language: String)

        val chapters = mutableListOf<ParsedChapter>()
        var page = 1
        var totalPages = 1

        do {
            val root = api.getJson("/api/android/manga/$mangaId/chapters?page=$page&limit=100")
            val data = root.optJSONArray("data") ?: JSONArray()
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optLong("id", 0L)
                if (id <= 0L) continue
                val number = item.optString("chapter_number")
                val rawTitle = item.opt("title")
                val title = when (rawTitle) {
                    null, JSONObject.NULL -> ""
                    else -> rawTitle.toString().trim()
                }
                val language = item.optString("language")
                val locked = item.optBoolean("lockedByCoins", false) ||
                    item.optBoolean("lockedByExclusive", false) ||
                    item.optInt("coinsRequired", 0) > 0
                val chapterCdn = item.optString("cdn_path")

                val chapter = SChapter.create().apply {
                    url = "$id|$slug|$number|$chapterCdn"
                    val baseName = when {
                        title.isBlank() -> "الفصل $number"
                        title == number || title == "الفصل $number" -> "الفصل $number"
                        else -> "الفصل $number - $title"
                    }
                    name = if (locked) "$baseName (مقفل)" else baseName
                    chapter_number = number.toFloatOrNull() ?: -1f
                    date_upload = parseIsoDate(item.optString("published_at"))
                    scanlator = item.optString("translator").takeUnless { it.isBlank() }.orEmpty()
                }
                chapters.add(ParsedChapter(chapter, language))
            }

            val meta = root.optJSONObject("meta")
            val total = meta?.optInt("total", 0) ?: 0
            val limit = meta?.optInt("limit", 100) ?: 100
            totalPages = if (total > 0 && limit > 0) {
                (total + limit - 1) / limit
            } else {
                page
            }
            page += 1
        } while (page <= totalPages)

        val arabic = chapters.filter { it.language.trim().equals("AR", true) }
        val result = if (arabic.isNotEmpty()) arabic else chapters
        return result.map { it.chapter }.sortedByDescending { it.chapter_number }
    }

    private fun mangaIdFromUrl(url: String): Long {
        val id = url.trim('/').substringBefore('/').toLongOrNull()
        return id ?: throw Exception("Invalid manga url")
    }

    private fun resolveCdnPath(hint: String, chapterCdn: String, seriesId: Long): String {
        hint.trim().takeIf { it.startsWith("cdn") }?.let { return it }
        chapterCdn.trim().takeIf { it.startsWith("cdn") }?.let { return it }

        if (seriesId > 0L) {
            cdnPathCache[seriesId]?.let { return it }
            try {
                val path = api.getJson("/api/android/content/$seriesId")
                    .optJSONObject("data")
                    ?.optString("cdn_path")
                    .orEmpty()
                if (path.startsWith("cdn")) {
                    cdnPathCache[seriesId] = path
                    return path
                }
            } catch (_: Throwable) {
            }
        }
        return "cdn1"
    }

    private fun lockedMessage(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optString("errorCode") != "ANDROID_CHAPTER_LOCKED") return null
        val options = root.optJSONObject("options")
        val coins = options?.optInt("coins", 0) ?: 0
        val shortlink = options?.optString("shortlink_url").orEmpty()
        val freeAt = options?.optString("freeAt").orEmpty()
        return buildString {
            append("هذا الفصل مقفل على ProChan")
            if (coins > 0) append(" ($coins عملات)")
            if (shortlink.isNotBlank()) append(". افتحه من: $shortlink")
            if (freeAt.isNotBlank()) append(" أو انتظر حتى ${freeAt.substringBefore('T')}")
        }
    }

    private fun isVisualType(type: String): Boolean {
        val value = type.trim()
        return value.equals("manga", true) ||
            value.equals("manhwa", true) ||
            value.equals("manhua", true)
    }

    private fun fixImageUrl(url: String): String {
        return url.replace("://app.prochan.net/", "://app.procomic.pro/")
    }

    private fun parseStatus(value: String?): Int {
        val status = value?.trim().orEmpty()
        return when {
            status.contains("مكتمل") || status.equals("completed", true) -> SManga.COMPLETED
            status.contains("متوقف") || status.contains("hiatus", true) -> SManga.ON_HIATUS
            status.contains("ملغي") || status.contains("cancelled", true) -> SManga.CANCELLED
            status.contains("مستمر") || status.equals("ongoing", true) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun parseGenres(item: JSONObject): String {
        val metadata = item.optJSONObject("metadata")
            ?: runCatching { JSONObject(item.optString("metadata")) }.getOrNull()
            ?: return ""
        val genres = metadata.optJSONArray("genres") ?: return ""
        val names = mutableListOf<String>()
        for (index in 0 until genres.length()) {
            when (val value = genres.opt(index)) {
                is JSONObject -> names.add(value.optString("ar").ifBlank { value.optString("en") })
                is String -> names.add(value)
            }
        }
        return names.filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun parseIsoDate(value: String): Long {
        if (value.isBlank()) return 0L
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in formats) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                return format.parse(value)?.time ?: 0L
            } catch (_: Throwable) {
            }
        }
        return 0L
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val values = mutableListOf<String>()
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) values.add(value)
        }
        return values
    }

    override fun getMangaUrl(manga: SManga): String {
        val parts = manga.url.trim('/').split('/', limit = 2)
        val id = parts.getOrNull(0).orEmpty()
        val slug = parts.getOrNull(1).orEmpty()
        return if (slug.isNotBlank()) "$baseUrl/ar/$slug-$id" else "$baseUrl/ar/$id"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split('|')
        val id = parts.getOrNull(0).orEmpty()
        val slug = parts.getOrNull(1).orEmpty()
        val number = parts.getOrNull(2).orEmpty()
        return if (slug.isNotBlank() && number.isNotBlank()) {
            "$baseUrl/ar/chapter/$slug-$number-$id"
        } else {
            "$baseUrl/ar/chapter/$id"
        }
    }

    override fun imageRequest(page: Page): Request {
        // `page.url` is the internal reader URL. `page.imageUrl` may be the
        // loopback exact-page fallback, which must not be used for downloads
        // or normal in-reader loading.
        val url = page.url.ifBlank { page.imageUrl.orEmpty() }
        return GET(url, imageHeaders)
    }

    override suspend fun getImageUrl(page: Page): String {
        val map = mapPageFromUrl(page.url) ?: return page.url
        return PageServer.pageUrl(this, map.first, map.second) ?: page.url
    }

    private fun mapPageFromUrl(url: String): Pair<Long, Int>? {
        val queryMarker = "?__map__="
        val queryIndex = url.indexOf(queryMarker)
        if (queryIndex >= 0) {
            val value = url.substring(queryIndex + queryMarker.length).substringBefore('&')
            val parts = value.split(':', limit = 2)
            val chapterId = parts.getOrNull(0)?.toLongOrNull()
            val pageIndex = parts.getOrNull(1)?.toIntOrNull()
            if (chapterId != null && pageIndex != null) return chapterId to pageIndex
        }

        val path = url.substringAfter("/__map__/", "")
        if (path.isNotBlank()) {
            val parts = path.split('/')
            val chapterId = parts.getOrNull(0)?.substringBefore('?')?.toLongOrNull()
            val pageIndex = parts.getOrNull(1)?.substringBefore('?')?.toIntOrNull()
            if (chapterId != null && pageIndex != null) return chapterId to pageIndex
        }
        return null
    }

    private inner class MapImageInterceptor(
        private val source: ProChan,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val segments = request.url.pathSegments
            val mapQuery = request.url.queryParameter("__map__")
            if (request.url.host != "procomic.pro" || (mapQuery == null && segments.firstOrNull() != "__map__")) {
                return chain.proceed(request)
            }

            val chapterId: Long
            val pageIndex: Int
            if (mapQuery != null) {
                val parts = mapQuery.split(':', limit = 2)
                chapterId = parts.getOrNull(0)?.toLongOrNull()
                    ?: throw Exception("Invalid map chapter")
                pageIndex = parts.getOrNull(1)?.toIntOrNull()
                    ?: throw Exception("Invalid map page")
            } else {
                chapterId = segments.getOrNull(1)?.toLongOrNull()
                    ?: throw Exception("Invalid map chapter")
                pageIndex = segments.getOrNull(2)?.toIntOrNull()
                    ?: throw Exception("Invalid map page")
            }

            val bytes = source.renderMapPage(chapterId, pageIndex)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                .build()
        }
    }
}

