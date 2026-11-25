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

        val data = loadChapterPages(chapterId, slug, number)
        val pages = mutableListOf<Page>()

        data.publicImages.forEach { url ->
            pages.add(Page(pages.size, url, url))
        }
        data.maps.forEach { map ->
            val url = "$baseUrl/__map__/$chapterId/${map.pageIndex}"
            pages.add(Page(pages.size, url, url))
        }

        if (pages.isEmpty()) throw Exception("No pages found for this chapter")
        return pages
    }

    internal fun renderMapPage(chapterId: Long, pageIndex: Int): ByteArray {
        val data = pageCache[chapterId] ?: loadChapterPages(chapterId, "", "")
        val map = data.maps.firstOrNull { it.pageIndex == pageIndex }
            ?: throw Exception("Protected page is no longer available")

        val result = api.getProxyPlan(chapterId, map, data.pass)
        if (result.pass != data.pass) {
            pageCache[chapterId] = data.copy(pass = result.pass)
        }
        return MapImageRenderer.render(api, result.map)
    }

    private fun loadChapterPages(chapterId: Long, slug: String, number: String): ChapterPageData {
        val root = api.getJson("/api/android/chapter/$chapterId")
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

        val refreshedToken = api.refreshDeferredToken(chapterId, token)
        val deferred = if (refreshedToken != null && refreshedToken != token) {
            try {
                api.getWebDeferred(chapterId, refreshedToken, split)
            } catch (first: ApiException) {
                api.getWebDeferred(chapterId, token, split)
            }
        } else {
            api.getWebDeferred(chapterId, token, split)
        }
        val deferredData = deferred.optJSONObject("data") ?: deferred
        val pass = deferredData.optString("pass")

        val appImages = try {
            api.getAppImages(seriesSlug, chapterNumber, chapterId)
        } catch (_: Throwable) {
            emptyList()
        }
        val publicImages = mutableListOf<String>()
        if (appImages.isNotEmpty()) {
            publicImages.addAll(appImages)
        } else {
            publicImages.addAll(data.optJSONArray("images").toStringList())
            publicImages.addAll(deferredData.optJSONArray("images").toStringList())
        }

        val maps = mutableListOf<MapPage>()
        val mapsArray = deferredData.optJSONArray("maps") ?: JSONArray()
        val cdnPath = data.optString("cdn_path").ifBlank { "cdn1" }
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
                val title = item.optString("title").trim()
                val language = item.optString("language")

                val chapter = SChapter.create().apply {
                    url = "$id|$slug|$number"
                    name = when {
                        title.isBlank() -> "الفصل $number"
                        title == "الفصل $number" -> title
                        else -> "الفصل $number - $title"
                    }
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
        val url = page.imageUrl ?: page.url
        return GET(url, imageHeaders)
    }

    override suspend fun getImageUrl(page: Page): String = page.url

    private inner class MapImageInterceptor(
        private val source: ProChan,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val segments = request.url.pathSegments
            if (request.url.host != "procomic.pro" || segments.firstOrNull() != "__map__") {
                return chain.proceed(request)
            }

            val chapterId = segments.getOrNull(1)?.toLongOrNull()
                ?: throw Exception("Invalid map chapter")
            val pageIndex = segments.getOrNull(2)?.toIntOrNull()
                ?: throw Exception("Invalid map page")

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

