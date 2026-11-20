package eu.kanade.tachiyomi.extension.ar.mangarabi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class MangaArabi : HttpSource() {

    override val name = "MangaArabi"
    override val baseUrl = "https://mangarabi.com"
    override val lang = "ar"
    override val supportsLatest = true

    private val apiUrl = "https://api.mangarabi.com/api/v1"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Accept", "application/json")
            .add("Referer", "$baseUrl/")
            .build()
    }

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
        val body = get(baseUrl + chapter.url)
        val doc = Jsoup.parse(body, baseUrl)

        var images = doc.select("div[data-reader-page] img[src]")
        if (images.isEmpty()) {
            images = doc.select("img[src*=reader-cdn.mangarabi.com]")
        }

        return images.mapIndexedNotNull { index, img ->
            val url = img.absUrl("src").ifBlank { img.attr("src") }
            if (url.isBlank()) null else Page(index, "", url)
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
