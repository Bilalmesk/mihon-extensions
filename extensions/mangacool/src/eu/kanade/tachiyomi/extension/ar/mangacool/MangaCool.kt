package eu.kanade.tachiyomi.extension.ar.mangacool

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

class MangaCool : HttpSource() {

    override val name = "MangaCool"
    override val baseUrl = "https://mangacool.net"
    override val lang = "ar"
    override val supportsLatest = true

    private val apiUrl = "https://api.mangacool.net/api"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // Client-side proof salt used by the reader before requesting page images.
    private val proofSalt = "137d078c1fc620cff2e95d84b27c4071025ceca97c6cd2d5"

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Accept", "application/json")
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("x-app-source", "mangacool")
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val body = get("$apiUrl/manga?sort=views&page=$page&limit=24", apiHeaders)
        return parseMangaList(body)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val body = get("$apiUrl/manga?sort=updated_at&page=$page&limit=24", apiHeaders)
        return parseMangaList(body)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("$apiUrl/manga?search=$encoded&sort=views&page=$page&limit=24", apiHeaders)
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
        val body = get("$apiUrl/manga/slug/$slug", apiHeaders)
        val item = JSONObject(body).getJSONObject("data")

        val updatedManga = if (fetchDetails) parseMangaDetails(item, manga) else manga
        val updatedChapters = if (fetchChapters) parseChapters(item, slug) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val readerHtml = get(baseUrl + chapter.url, headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .build())

        val token = Regex("&quot;unlockToken&quot;:\\[0,&quot;([^&]+)&quot;\\]")
            .find(readerHtml)?.groupValues?.get(1)
            ?: throw Exception("Could not find reader token")

        val chapterId = Regex("&quot;chapterId&quot;:\\[0,(\\d+)\\]")
            .find(readerHtml)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("data-chapter-id=\"(\\d+)\"").find(readerHtml)?.groupValues?.get(1)?.toLongOrNull()
            ?: decodeChapterId(token)
            ?: throw Exception("Could not find chapter id")

        val parts = chapter.url.removePrefix("/reader/").split("/", limit = 2)
        val slug = parts[0]
        val number = parts.getOrNull(1).orEmpty()

        val payload = JSONObject().apply {
            put("chapterId", chapterId)
            put("mangaSlug", slug)
            put("chapterNumber", number)
            put("token", token)
            put("proof", sha256("$proofSalt|$token|$chapterId"))
        }

        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val responseBody = client.newCall(POST("$apiUrl/reader/pages", apiHeaders, requestBody)).await().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code} while loading pages")
            it.body.string()
        }

        val root = JSONObject(responseBody)
        if (!root.optBoolean("success")) {
            throw Exception("Reader rejected the page request")
        }

        val data = root.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val url = data.optString(index)
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
        if (!root.optBoolean("success")) {
            throw Exception("MangaCool API error")
        }

        val data = root.getJSONObject("data")
        val array = data.optJSONArray("mangas") ?: JSONArray()

        val mangas = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("slug")
            if (slug.isBlank()) return@mapNotNull null

            SManga.create().apply {
                url = "/manga/$slug"
                title = item.optString("title_ar")
                    .ifBlank { item.optString("title") }
                thumbnail_url = item.optString("cover_image")
                genre = item.optJSONArray("display_tags").tagNames()
                    .ifBlank { item.optJSONArray("Tags").tagNames() }
                description = item.optString("description_cool")
                    .ifBlank { item.optString("description_sid") }
                status = parseStatus(item.optString("status"))
                initialized = true
            }
        }

        val pagination = data.optJSONObject("pagination")
        val page = pagination?.optInt("page", 1) ?: 1
        val totalPages = pagination?.optInt("totalPages", 1) ?: 1

        return MangasPage(mangas, page < totalPages)
    }

    private fun parseMangaDetails(item: JSONObject, manga: SManga): SManga {
        return SManga.create().apply {
            url = manga.url
            title = item.optString("title_ar")
                .ifBlank { item.optString("title") }
                .ifBlank { manga.title }
            thumbnail_url = item.optString("cover_image")
            author = item.optString("author").takeUnless { it.equals("unknown", ignoreCase = true) }.orEmpty()
            description = item.optString("description")
                .ifBlank { item.optString("description_cool") }
                .ifBlank { item.optString("description_sid") }
            genre = item.optJSONArray("Tags").tagNames()
                .ifBlank { item.optJSONArray("display_tags").tagNames() }
            status = parseStatus(item.optString("status"))
            initialized = true
        }
    }

    private fun parseChapters(item: JSONObject, slug: String): List<SChapter> {
        val array = item.optJSONArray("MangaChapters") ?: JSONArray()
        val chapters = mutableListOf<SChapter>()

        for (index in 0 until array.length()) {
            val chapter = array.optJSONObject(index) ?: continue
            if (chapter.optInt("visible", 1) == 0) continue

            val number = chapter.optString("chapter_number")
            if (number.isBlank()) continue

            chapters.add(
                SChapter.create().apply {
                    url = "/reader/$slug/$number"
                    name = "الفصل $number"
                    chapter_number = number.toFloatOrNull() ?: -1f
                    date_upload = parseDate(
                        chapter.optString("release_date")
                            .ifBlank { chapter.optString("created_at") },
                    )
                },
            )
        }

        return chapters.sortedByDescending { it.chapter_number }
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

    private fun decodeChapterId(token: String): Long? {
        return try {
            val decoded = String(Base64.decode(token, Base64.NO_WRAP), Charsets.UTF_8)
            decoded.substringBefore("|").toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun JSONArray?.tagNames(): String {
        if (this == null) return ""
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
    }
}
