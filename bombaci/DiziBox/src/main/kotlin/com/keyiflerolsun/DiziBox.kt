package com.keyiflerolsun

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenen Bölümler",
        "${mainUrl}/dizi-izle/" to "Tüm Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        // DiziBox ana sayfasında içerikler "article.post-box" veya ".post-item" içinde yer alır
        val home = document.select("article.post-box, article, div.post-item").mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Başlığı 'entry-title', 'h2', 'h3' veya 'a' etiketinden alıyoruz
        val titleElement = this.selectFirst(".entry-title, h2, h3, h4, .title")
        val title = titleElement?.text()?.trim() 
            ?: this.selectFirst("a")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(this.selectFirst("a")?.attr("href") ?: this.attr("href")) ?: return null

        // Kategori/etiket/sayfalama linklerini filtreliyoruz
        if (href == mainUrl || href.contains("/kategori/") || href.contains("/tag/") || href.contains("/page/")) return null

        // Görsel URL'sini alıyoruz (lazy-load attributes: data-src, data-lazy-src)
        val imgElement = this.selectFirst("img")
        var posterUrl = imgElement?.attr("data-src")?.takeIf { !it.startsWith("data:") }
            ?: imgElement?.attr("data-lazy-src")?.takeIf { !it.startsWith("data:") }
            ?: imgElement?.attr("src")?.takeIf { !it.startsWith("data:") }
        posterUrl = fixUrlNull(posterUrl)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}/?s=${query}"
        val document = app.get(url).document

        return document.select("article.post-box, article, div.post-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img, article img, .post-thumb img")?.attr("src") 
            ?: document.selectFirst("article img")?.attr("data-src"))
        val description = document.selectFirst("div.description, div.entry-content, article p")?.text()?.trim()

        val episodes = document.select("div.episodes-list a, ul.episodes a, div.seasons-list a, table.episodes a").mapNotNull {
            val epName = it.text().trim()
            val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null

            newEpisode(epHref) {
                this.name = epName
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe")

        iframes.forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src") ?: iframe.attr("data-src"))
            if (!src.isNullOrBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
