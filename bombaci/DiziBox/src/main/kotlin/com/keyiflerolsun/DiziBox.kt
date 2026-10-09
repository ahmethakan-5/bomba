package com.keyiflerolsun

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

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
        // WebView yerine doğrudan hafif HTTP isteği atıyoruz (Timeout ve Cloudflare engeli için)
        val document = app.get(request.data).document

        // Sitedeki dizi/bölüm kartlarını yakalıyoruz
        val home = document.select("article, div.post-item, a.poster, div.tv-series-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2, h3, strong, .title")
        val title = titleElement?.text()?.trim() ?: this.attr("title").takeIf { it.isNotBlank() } ?: return null
        
        val href = fixUrlNull(this.selectFirst("a")?.attr("href") ?: this.attr("href")) ?: return null
        
        val imgElement = this.selectFirst("img")
        var posterUrl = imgElement?.attr("src")?.takeIf { !it.startsWith("data:") }
            ?: imgElement?.attr("data-src")?.takeIf { !it.startsWith("data:") }
        posterUrl = fixUrlNull(posterUrl)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}/?s=${query}"
        val document = app.get(url).document

        return document.select("article, div.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img, img.thumb")?.attr("src"))
        val description = document.selectFirst("div.description, div.entry-content")?.text()?.trim()

        val episodes = document.select("div.episodes-list a, ul.episodes a").mapNotNull {
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
