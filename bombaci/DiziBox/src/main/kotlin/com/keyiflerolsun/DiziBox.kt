package com.keyiflerolsun

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.ExtractorLink

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

        val home = document.select("article, div.post-box, div.item, .col-box").mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title, h2, h3, h4, .title, .mh-posts-list-title")
        val title = titleElement?.text()?.trim() 
            ?: this.selectFirst("a")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(this.selectFirst("a")?.attr("href") ?: this.attr("href")) ?: return null

        if (href == mainUrl || href.contains("/kategori/") || href.contains("/tag/") || href.contains("/page/")) return null

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

        return document.select("article, div.post-box, div.item, .col-box").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img, .post-thumb img, .entry-content img")?.attr("src") 
            ?: document.selectFirst("img")?.attr("data-src"))
        val description = document.selectFirst("div.description, .entry-content, p")?.text()?.trim()

        val episodes = emptyList<Episode>() // Boş liste

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

        if (iframes.isEmpty()) {
            return false
        }

        iframes.forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src") ?: iframe.attr("data-src"))
            if (!src.isNullOrBlank()) {
                callback(ExtractorLink(src, name = "DiziBox", referer = data))
            }
        }

        return true
    }
}

// Helper functions
private fun fixUrlNull(url: String?): String? {
    return url?.trim().takeIf { it.isNotBlank() }
}
