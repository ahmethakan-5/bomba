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
        val items = document.select("article, div.post-box, div.item, .col-box").mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
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

        // Bölüm listesini ara
        val episodes = document.select("div.episodes-list a, ul.episodes a, div.seasons-list a, table.episodes a, .les-content a").mapNotNull {
            val epName = it.text().trim()
            val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
            newEpisode(epHref) {
                this.name = epName
            }
        }

        // Eğer bölüm listesi bulunamadıysa, bu sayfa doğrudan bir bölüme ait olabilir.
        // Bu durumu yönetmek için mevcut URL'yi ilk bölüm olarak ekliyoruz.
        val finalEpisodes = if (episodes.isEmpty()) {
            listOf(newEpisode(url) { this.name = "Bölüm" })
        } else {
            episodes
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
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

        if (iframes.isEmpty()) return false

        iframes.forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src") ?: iframe.attr("data-src"))
            if (!src.isNullOrBlank()) {
                // Direct extractor call instead of custom wrappers to avoid build bugs
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        return true
    }
}

// Yardımcı fonksiyon (Her dosyada tekrarlamaması adına alt kısma ekledim)
private fun fixUrlNull(url: String?): String? {
    return url?.trim()?.let {
        if (it.startsWith("/")) "https://www.dizibox.live$it" else it
    }?.takeIf { it.isNotBlank() }
}
