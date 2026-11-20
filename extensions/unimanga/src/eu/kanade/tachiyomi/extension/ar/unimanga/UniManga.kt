package eu.kanade.tachiyomi.extension.ar.unimanga

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class UniManga : HttpSource() {

    override val name = "UniManga"
    override val baseUrl = "https://unimanga.net"
    override val lang = "ar"
    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val body = get("$baseUrl/titles?sort=views&page=$page")
        return parseMangaList(body)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val body = get("$baseUrl/titles?sort=latest&page=$page")
        return parseMangaList(body)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("$baseUrl/search?q=$encoded&page=$page")
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

        val detailBody = get(baseUrl + manga.url)
        val doc = Jsoup.parse(detailBody, baseUrl)

        val updatedManga = if (fetchDetails) parseMangaDetails(doc, manga) else manga
        val updatedChapters = if (fetchChapters) parseAllChapters(doc, manga) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val body = get(baseUrl + chapter.url)
        val doc = Jsoup.parse(body, baseUrl)

        return doc.select("div.rd-pages img[src]").mapIndexedNotNull { index, img ->
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
        val doc = Jsoup.parse(body, baseUrl)
        val mangas = doc.select("a.um-card").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            SManga.create().apply {
                url = href.substringAfter(baseUrl)
                title = anchor.selectFirst("strong")?.text()?.trim().orEmpty()
                thumbnail_url = anchor.selectFirst("img")?.let {
                    it.absUrl("src").ifBlank { it.attr("src") }
                }.orEmpty()
            }
        }

        val hasNextPage = doc.selectFirst("a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseMangaDetails(doc: Document, manga: SManga): SManga {
        return SManga.create().apply {
            url = manga.url
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { manga.title }
            thumbnail_url = doc.selectFirst("[data-mob=cover] img")?.let {
                it.absUrl("src").ifBlank { it.attr("src") }
            }.orEmpty()
            author = doc.selectFirst(".tp-by bdi")?.text()?.trim().orEmpty()
            description = doc.selectFirst("section[data-mob=tsyn] p")?.text()?.trim().orEmpty()
            genre = doc.select("a.tp-genre").joinToString(", ") { it.text().trim() }
            status = parseStatus(doc)
            initialized = true
        }
    }

    private fun parseStatus(doc: Document): Int {
        val labels = doc.select("[data-mob=tmeta] span, [data-mob=tmeta] a")
            .map { it.text().trim() }
            .toSet()

        return when {
            "مستمر" in labels -> SManga.ONGOING
            "مكتمل" in labels || "مكتملة" in labels -> SManga.COMPLETED
            "متوقف" in labels || "متوقفة" in labels -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun parseAllChapters(doc: Document, manga: SManga): List<SChapter> {
        val firstPage = parseChapters(doc)
        if (firstPage.isEmpty()) return firstPage

        // The detail page renders the first 50 rows; the rest come from the chapter-rows endpoint.
        if (firstPage.size < 50) return firstPage

        val moreBody = get(
            "$baseUrl${manga.url}/chapter-rows?offset=${firstPage.size}",
            headers.newBuilder()
                .add("Accept", "text/html")
                .add("X-Requested-With", "XMLHttpRequest")
                .build(),
        )

        return firstPage + parseChapters(Jsoup.parse(moreBody, baseUrl))
    }

    private fun parseChapters(doc: Document): List<SChapter> {
        return doc.select("li.tp-row[data-num]").mapNotNull { row ->
            val link = row.selectFirst("a[href*=\"/chapter/\"]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            val number = row.attr("data-num").toFloatOrNull() ?: 0f
            val name = link.select("span")
                .map { it.text().trim() }
                .firstOrNull { it.startsWith("الفصل") }
                ?: "الفصل $number"

            SChapter.create().apply {
                url = href.substringAfter(baseUrl)
                this.name = name
                chapter_number = number
                date_upload = parseDate(row.selectFirst("time")?.attr("datetime"))
            }
        }
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        if (value.length < 10) return 0L
        return try {
            dateFormat.parse(value.substring(0, 10))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
