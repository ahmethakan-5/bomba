package com.keyiflerolsun

import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.ErrorLoadingException

class DiziBox : MainAPI() {
    override var mainUrl = "https://www.dizibox.tv"
    override var name = "DiziBox"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    private var seriesCache: List<Pair<String, String>> = emptyList()

    // --- YARDIMCI DÖNÜŞTÜRÜCÜ VE STRING METOTLARI ---
    private fun String.cleanTitle(): String {
        return this.replace(Regex("""(?i)\s*izle\s*"""), "").trim()
    }

    private fun norm(str: String): String {
        return str.lowercase().replace(Regex("""[^a-z0-9]"""), "")
    }

    private fun Element.imgUrl(): String? {
        val img = selectFirst("img[src]") ?: selectFirst("img[data-src]")
        return img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
    }

    private suspend fun getDoc(url: String): Document {
        return app.get(url).document
    }

    private fun seriesUrlFromEpisode(epUrl: String): String? {
        // Bölüm linkinden dizi ana sayfa linkini türetme mantığı
        val regex = Regex("""(https?://[^/]+/[^/]+)""")
        return regex.find(epUrl)?.value
    }

    private fun readSeriesIndex(document: Document): List<Pair<String, String>> {
        return document.select("ul.series-list li a, .all-series a").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title = a.text().trim()
            if (title.isBlank()) null else Pair(title, href)
        }
    }

    // --- KART YARDIMCILARI ---
    private fun Element.recommendedItem(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = (selectFirst("span.baslik, .series-title, .title")?.text() ?: a.attr("title")).cleanTitle()
        if (title.isBlank()) return null
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.posterItem(): SearchResponse? {
        val a = selectFirst("a.poster-title[href]") 
            ?: selectFirst("a[href*=/diziler/]") 
            ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = a.attr("title").ifBlank { a.text() }.cleanTitle()
        if (title.isBlank()) return null
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.episodeCardItem(byName: Map<String, Pair<String, String>>): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val epHref = fixUrlNull(a.attr("href")) ?: return null
        
        val rawName = selectFirst("b.series-name, .series-title, .title")?.text()?.trim().orEmpty()
        val season = selectFirst("span.season, .season-no")?.text()?.trim().orEmpty()
        val episode = selectFirst("b.episode, .episode-no")?.text()?.trim().orEmpty()

        val hit = byName[norm(rawName)]
        val seriesUrl = hit?.second ?: seriesUrlFromEpisode(epHref) ?: epHref
        val shownName = hit?.first ?: rawName.ifBlank { a.attr("title") }
        
        val title = "$shownName $season $episode".replace(Regex("""\s+"""), " ").trim()
        if (title.isBlank()) return null
        
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { this.posterUrl = poster }
    }

    // --- MAIN PAGE ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDoc(mainUrl)

        val index = readSeriesIndex(document)
        if (index.isNotEmpty()) seriesCache = index
        val byName = index.associateBy { norm(it.first) }

        val lists = mutableListOf<HomePageList>()

        // 1. Öne Çıkan / Beklenen Diziler (#recommended-series)
        val recItems = document.select("#recommended-series li, section.recommended li").mapNotNull { it.recommendedItem() }.distinctBy { it.url }
        if (recItems.isNotEmpty()) {
            lists.add(HomePageList("Beklenen / Eklenen Diziler", recItems))
        }

        // 2. Dikkat Çeken Yeni Diziler (#new-serieses)
        val newItems = document.select("#new-serieses article, #new-series article, article.article-series-poster").mapNotNull { it.posterItem() }.distinctBy { it.url }
        if (newItems.isNotEmpty()) {
            lists.add(HomePageList("Dikkat Çeken Yeni Diziler", newItems))
        }

        // 3. Son Bölümler
        val latestEpisodes = document.select("article.article-episode-card, .latest-episodes article")
            .mapNotNull { it.episodeCardItem(byName) }
            .distinctBy { it.url }
        
        if (latestEpisodes.isNotEmpty()) {
            lists.add(HomePageList("Son Bölümler", latestEpisodes))
        }

        // 4. Fallback: İçerik bulunamazsa alfabetik listeden doldur
        if (lists.isEmpty() && index.isNotEmpty()) {
            val fallbackItems = index.take(20).map { item ->
                newTvSeriesSearchResponse(item.first, item.second, TvType.TvSeries)
            }
            lists.add(HomePageList("Tüm Diziler", fallbackItems))
        }

        if (lists.isEmpty()) {
            throw ErrorLoadingException("DiziBox ana sayfası okunamadı.")
        }

        return newHomePageResponse(lists, false)
    }
}
