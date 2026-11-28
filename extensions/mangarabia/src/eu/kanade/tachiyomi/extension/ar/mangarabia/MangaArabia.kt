package eu.kanade.tachiyomi.extension.ar.mangarabia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class MangaArabia : HttpSource() {

    override val name = "MangaArabia"
    // mangarabia.com permanently redirects to mangarabi.com and its old API
    // host no longer resolves, so this source now uses the merged backend.
    override val baseUrl = "https://mangarabi.com"
    override val lang = "ar"
    override val supportsLatest = true

    private val apiUrl = "https://api.mangarabi.com/api/v1"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val textMediaType = "text/plain;charset=UTF-8".toMediaType()

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Accept", "application/json")
            .add("Referer", "$baseUrl/")
            .build()
    }

    // The reader is a Next.js client component. Only the first 4 pages are
    // server-rendered; the rest are produced by these server actions.
    private data class ReaderActions(
        val start: String,
        val status: String,
        val refresh: String,
    )

    private val defaultReaderActions = ReaderActions(
        start = "4062e4dd13e2f34d79823b9ad207d4ed44326b9730",
        status = "4069248148dbff95344b82ad9d140cc22ee603908e",
        refresh = "40655bc2f66b518036cca1f8ac4bc9a1d2fcb39bc6",
    )

    @Volatile
    private var readerActions: ReaderActions? = null

    private val readerActionRegex = Regex(
        """createServerReference\)?\("([0-9a-f]{20,})"[^)]{0,400}?"(\w+Action)"\)""",
    )
    private val chapterIdRegex = Regex("\"chapterId\"\\s*:\\s*\"([^\"]+)\"")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val body = get("$apiUrl/series?sort=popular&per_page=20&page=$page", apiHeaders)
        return parseMangaList(body)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val body = get("$apiUrl/series?sort=latest&per_page=20&page=$page", apiHeaders)
        return parseMangaList(body)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("$apiUrl/search?q=$encoded&page=$page", apiHeaders)
        return parseMangaList(body)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val slug = manga.url.removePrefix("/manga/")
        val updatedManga = if (fetchDetails) {
            val body = get("$apiUrl/series/$slug", apiHeaders)
            parseMangaDetails(JSONObject(body).getJSONObject("data"), manga)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) fetchChapters(slug) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageUrl = baseUrl + chapter.url
        val html = get(pageUrl)
        val htmlPages = parseHtmlPages(html)
        val chapterId = extractChapterId(html) ?: return htmlPages

        return try {
            val actionPages = fetchReaderPages(pageUrl, html, chapterId)
            // Keep whichever list is more complete; the site server-renders
            // the first few pages and fills the rest through server actions.
            if (actionPages.size > htmlPages.size) actionPages else htmlPages
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            htmlPages
        }
    }

    private fun extractChapterId(html: String): String? {
        val normalized = html.replace("\\\"", "\"")
        return chapterIdRegex.find(normalized)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun parseHtmlPages(html: String): List<Page> {
        val doc = Jsoup.parse(html, baseUrl)

        var images = doc.select("div[data-reader-page] img[src]")
        if (images.isEmpty()) {
            images = doc.select("img[src*=reader-cdn.mangarabia.com]")
        }

        return images.mapIndexedNotNull { index, img ->
            val url = img.absUrl("src").ifBlank { img.attr("src") }
            if (url.isBlank()) null else Page(index, "", url)
        }
    }

    private suspend fun fetchReaderPages(pageUrl: String, html: String, chapterId: String): List<Page> {
        var state = postReaderAction(pageUrl, html, "start", chapterId) ?: return emptyList()
        var totalPages = state.optInt("total_pages", 0)

        state.optJSONObject("reader")?.pages()?.takeIf { it.isNotEmpty() }?.let { return it }

        var status = state.optString("status")
        var attempts = 0
        while (status == "cold" || status == "processing" || status == "partial_ready") {
            if (++attempts > 90) break
            delay(1_000)
            state = postReaderAction(pageUrl, html, "status", chapterId) ?: break
            totalPages = state.optInt("total_pages", totalPages)
            status = state.optString("status")
        }

        if (status == "failed" || status == "unavailable") return emptyList()

        var pages = emptyList<Page>()
        var refreshes = 0
        while (refreshes < 6) {
            val reader = postReaderAction(pageUrl, html, "refresh", chapterId) ?: break
            val newPages = reader.pages()
            if (newPages.isNotEmpty()) pages = newPages
            if (totalPages > 0 && pages.size >= totalPages) break
            if (newPages.isEmpty() && refreshes >= 2) break
            refreshes++
            delay(1_000)
        }

        return pages
    }

    private suspend fun postReaderAction(
        pageUrl: String,
        html: String,
        kind: String,
        chapterId: String,
    ): JSONObject? {
        val actions = readerActions ?: defaultReaderActions
        postAction(pageUrl, actions.forKind(kind), chapterId)?.let { return it }

        // Action IDs change when the site is rebuilt. Resolve them from the
        // reader chunks and retry once before giving up.
        val resolved = resolveReaderActions(html) ?: return null
        readerActions = resolved
        return postAction(pageUrl, resolved.forKind(kind), chapterId)
    }

    private suspend fun postAction(pageUrl: String, actionId: String, chapterId: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(pageUrl)
                .post("[\"$chapterId\"]".toRequestBody(textMediaType))
                .headers(
                    headers.newBuilder()
                        .set("Accept", "text/x-component")
                        .set("Content-Type", "text/plain;charset=UTF-8")
                        .set("Next-Action", actionId)
                        .set("Origin", baseUrl)
                        .set("Referer", pageUrl)
                        .build(),
                )
                .build()

            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                parseActionPayload(response.body.string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun parseActionPayload(body: String): JSONObject? {
        val line = body.lineSequence().firstOrNull { it.startsWith("1:") } ?: return null
        val payload = line.substring(2).trim()
        if (!payload.startsWith("{")) return null
        return try {
            JSONObject(payload)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveReaderActions(html: String): ReaderActions? {
        val scriptUrls = Jsoup.parse(html, baseUrl)
            .select("script[src]")
            .mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
            .filter { it.startsWith(baseUrl) && it.contains("/_next/static/chunks/") }
            .distinct()

        val ids = mutableMapOf<String, String>()
        for (url in scriptUrls) {
            val script = try {
                get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }

            for (match in readerActionRegex.findAll(script)) {
                ids[match.groupValues[2]] = match.groupValues[1]
            }

            if (ids.containsKey("startChapterAction") &&
                ids.containsKey("chapterStatusAction") &&
                ids.containsKey("refreshReaderAction")
            ) {
                break
            }
        }

        return ReaderActions(
            start = ids["startChapterAction"] ?: return null,
            status = ids["chapterStatusAction"] ?: return null,
            refresh = ids["refreshReaderAction"] ?: return null,
        )
    }

    private fun ReaderActions.forKind(kind: String): String = when (kind) {
        "start" -> start
        "status" -> status
        else -> refresh
    }

    private fun JSONObject.pages(): List<Page> {
        val array = optJSONArray("pages") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            if (url.isBlank()) {
                null
            } else {
                Page((item.optInt("page", index + 1) - 1).coerceAtLeast(0), "", url)
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    private suspend fun get(url: String, requestHeaders: Headers = headers): String {
        return client.newCall(GET(url, requestHeaders)).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} for $url")
            }
            response.body.string()
        }
    }

    private fun parseMangaList(body: String): MangasPage {
        val root = JSONObject(body)
        val data = root.getJSONArray("data")

        val mangas = (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("slug")
            if (slug.isBlank()) return@mapNotNull null

            SManga.create().apply {
                url = "/manga/$slug"
                title = item.optString("title_ar")
                    .ifBlank { item.optString("title") }
                thumbnail_url = item.optString("cover_url")
                genre = item.optJSONArray("genres").genreNames()
                status = parseStatus(item.optString("status"))
                initialized = true
            }
        }

        val pagination = root.optJSONObject("meta")?.optJSONObject("pagination")
        val current = pagination?.optInt("current_page", 1) ?: 1
        val last = pagination?.optInt("last_page", 1) ?: 1

        return MangasPage(mangas, current < last)
    }

    private fun parseMangaDetails(item: JSONObject, manga: SManga): SManga {
        return SManga.create().apply {
            url = manga.url
            title = item.optString("arabic_name")
                .ifBlank { item.optString("title") }
                .ifBlank { manga.title }
            thumbnail_url = item.optString("cover_url")
            author = item.optString("author")
            artist = item.optString("artist")
            description = item.optString("description")
            genre = item.optJSONArray("genres").genreNames()
            status = parseStatus(item.optString("status"))
            initialized = true
        }
    }

    private suspend fun fetchChapters(slug: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var lastPage = 1

        do {
            val body = get("$apiUrl/series/$slug/chapters?per_page=100&page=$page", apiHeaders)
            val root = JSONObject(body)
            val data = root.getJSONArray("data")

            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val chapterSlug = item.optString("slug")
                if (chapterSlug.isBlank()) continue

                val number = item.optString("number")
                val title = item.optString("title")
                val name = if (title.isNotBlank() && !title.equals("Chapter $number", ignoreCase = true)) {
                    "الفصل $number - $title"
                } else {
                    "الفصل $number"
                }

                chapters.add(
                    SChapter.create().apply {
                        url = "/read/$slug/$chapterSlug"
                        this.name = name
                        chapter_number = number.toFloatOrNull() ?: -1f
                        date_upload = parseDate(item.optString("published_at"))
                    },
                )
            }

            val pagination = root.optJSONObject("meta")?.optJSONObject("pagination")
            lastPage = pagination?.optInt("last_page", page) ?: page
            page++
        } while (page <= lastPage && page <= 100)

        return chapters
    }

    private fun parseStatus(value: String?): Int {
        return when (value?.lowercase(Locale.US)) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus", "on_hiatus" -> SManga.ON_HIATUS
            "cancelled", "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank() || value.length < 10) return 0L
        return try {
            dateFormat.parse(value.substring(0, 10))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun JSONArray?.genreNames(): String {
        if (this == null) return ""
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
    }
}
