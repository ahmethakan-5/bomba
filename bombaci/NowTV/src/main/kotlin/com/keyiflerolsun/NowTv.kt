package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class NowTv : MainAPI() {
    override var mainUrl = "https://www.nowtv.com.tr"
    override var name = "NOW TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Documentary)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi" to "Diziler",
        "$mainUrl/programlar" to "Programlar"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        // NOW TV içerik kartları için güncel seçiciler
        val home = document.select("div.card, div.show-card, li.swiper-slide, .card-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3, h4, .title, strong, .program-name")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrl(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("src") 
                ?: this.selectFirst("source")?.attr("srcset")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/arama?q=$query").document
        return document.select("div.card, div.show-card, .search-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .detail-title, .program-name")?.text()?.trim() ?: "NOW TV"
        val poster = fixUrl(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img.poster, .detail-img img")?.attr("data-src")
                ?: document.selectFirst("img.poster, .detail-img img")?.attr("src")
        )
        val description = document.selectFirst("meta[name='description']")?.attr("content")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
            this.posterUrl = poster
            this.plot = description
        }
    }
}
