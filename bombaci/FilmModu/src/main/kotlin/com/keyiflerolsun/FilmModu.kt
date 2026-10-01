package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmModu : MainAPI() {
    override var mainUrl = "https://filmmodu.live"
    override var name = "FilmModu"
    override var hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/animes" to "Animeler",
        "$mainUrl/kesfet" to "Keşfet"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document

        val items = document.select("a[href*=/film/], a[href*=/dizi/], a[href*=/anime/]").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.font-medium, h2, h3, .title")?.text() 
            ?: this.attr("title").ifEmpty { this.text() }
        
        if (title.isBlank()) return null

        val href = fixUrlNull(this.attr("href")) ?: return null
        
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        }

        val quality = if (this.text().contains("1080p", true) || this.text().contains("HD", true)) {
            SearchQuality.HD
        } else null

        val type = when {
            href.contains("/dizi/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }

        return if (type == TvType.TvSeries || type == TvType.Anime) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara?q=$query"
        val document = app.get(url).document

        return document.select("a[href*=/film/], a[href*=/dizi/], a[href*=/anime/]").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[src*=/posters/]")?.attr("src")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("p")?.text()

        val year = document.selectFirst("a[href*=/yil/]")?.text()?.toIntOrNull()
        val rating = document.selectFirst(".rating, [class*=score]")?.text()?.let {
            Regex("""\d+(\.\d+)?""").find(it)?.value?.toIntOrNull()
        }

        val isTv = url.contains("/dizi/") || url.contains("/anime/")

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*=/bolum/]").forEach { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@forEach
                val epName = ep.text().trim()
                
                val seasonNum = epHref.substringAfter("sezon-", "").substringBefore("-").toIntOrNull() ?: 1
                val epNum = epHref.substringAfter("bolum-", "").substringBefore("-").toIntOrNull() ?: 1

                episodes.add(
                    Episode(
                        data = epHref,
                        name = epName.ifEmpty { "$seasonNum. Sezon $epNum. Bölüm" },
                        season = seasonNum,
                        episode = epNum
                    )
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.rating = rating
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = fixUrl(iframe.attr("src"))
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
