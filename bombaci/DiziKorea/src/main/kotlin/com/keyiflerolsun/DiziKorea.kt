// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.Jsoup

class DiziKorea : MainAPI() {
    override var mainUrl              = "https://dizikorea3.com"
    override var name                 = "DiziKorea"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/kore-dizileri-izle-dq1?page=" to "Kore Dizileri",
        "${mainUrl}/cin-dizileri?page="           to "Çin Dizileri",
        "${mainUrl}/japon-dizileri?page="         to "Japon Dizileri",
        "${mainUrl}/tayland-dizileri?page="       to "Tayland Dizileri",
        "${mainUrl}/tayvan-dizileri?page="        to "Tayvan Dizileri",
        "${mainUrl}/filipin-dizileri?page="       to "Filipin Dizileri",
        "${mainUrl}/filmler?page="                to "Filmler",
        "${mainUrl}/trendler?page="                to "Trendler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.poster-card, div.poster-long, div.poster-item, a.dual-slide-card, a.upcoming-home-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, h3, .dual-slide-title, .upcoming-home-card-title, .poster-title")?.text()?.trim()
            ?: this.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href") ?: this.attr("href")) ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
                ?: img?.attr("srcset")?.substringBefore(" ")?.ifBlank { null }
        )

        val isMovie = href.contains("/film/")
        val tvType  = if (isMovie) TvType.Movie else TvType.AsianDrama

        return if (isMovie) {
            newMovieSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
        }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span, h2, h3, div.title")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href") ?: this.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
        )

        val isMovie = href.contains("/film/")
        val tvType  = if (isMovie) TvType.Movie else TvType.AsianDrama

        return if (isMovie) {
            newMovieSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "${mainUrl}/search",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = "${mainUrl}/",
            data    = mapOf("query" to query)
        ).parsedSafe<KoreaSearch>()?.theme ?: return emptyList()

        val document = Jsoup.parse(response)
        val results  = mutableListOf<SearchResponse>()

        document.select("ul li, div.search-result-item").forEach { listItem ->
            val aTag = listItem.selectFirst("a") ?: (if (listItem.tagName() == "a") listItem else null)
            val href = aTag?.attr("href")
            if (href != null && (href.contains("/dizi/") || href.contains("/film/"))) {
                val result = listItem.toPostSearchResult()
                result?.let { results.add(it) }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1 a, h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(
            document.selectFirst("div.series-profile-image img, div.poster-img img, div.series-poster img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }
        )
        val year        = document.selectFirst("h1 span, span.year, div.series-profile-year")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()
        val description = document.selectFirst("div.series-profile-summary p, div.series-summary, div.description")?.text()?.trim()
        val tags        = document.select("div.series-profile-type a, div.series-genres a").mapNotNull { it.text().trim() }
        val rating      = document.selectFirst("span.color-imdb, span.imdb-score")?.text()?.trim()?.toRatingInt()
        val duration    = document.selectXpath("//span[text()='Süre']//following-sibling::p").text().trim().split(" ").firstOrNull()?.toIntOrNull()
        val trailer     = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        val actors      = document.select("div.series-profile-cast li, div.cast-item").mapNotNull {
            val actorName = it.selectFirst("h5, span.name")?.text()?.trim() ?: return@mapNotNull null
            val actorImg  = fixUrlNull(it.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } })
            Actor(actorName, actorImg)
        }

        val isMovie = url.contains("/film/")

        if (!isMovie) {
            val episodes = mutableListOf<Episode>()
            document.select("div.series-profile-episode-list").forEach { seasonDiv ->
                val epSeason = seasonDiv.parent()?.id()?.split("-")?.lastOrNull()?.toIntOrNull() ?: 1

                seasonDiv.select("li").forEach ep@ { episodeElement ->
                    val epHref    = fixUrlNull(episodeElement.selectFirst("h6 a, a")?.attr("href")) ?: return@ep
                    val epEpisode = episodeElement.selectFirst("a.truncate data, span.ep-num")?.text()?.trim()?.toIntOrNull()

                    episodes.add(newEpisode(epHref) {
                        this.name    = if (epEpisode != null) "${epSeason}. Sezon ${epEpisode}. Bölüm" else episodeElement.selectFirst("h6, a")?.text()?.trim()
                        this.season  = epSeason
                        this.episode = epEpisode
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
                addActors(actors)
                if (!trailer.isNullOrBlank()) addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
                addActors(actors)
                if (!trailer.isNullOrBlank()) addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZK", "data » $data")
        val document = app.get(data).document
        val sources  = mutableSetOf<String>()

        document.select("div.series-watch-alternatives button, ul.player-servers li button, button[data-hhs], button[data-src]").forEach { button ->
            val iframeUrl = fixUrlNull(
                button.attr("data-hhs").ifBlank {
                    button.attr("data-src").ifBlank {
                        button.attr("data-video")
                    }
                }
            )
            if (!iframeUrl.isNullOrEmpty()) {
                sources.add(iframeUrl)
            }
        }

        document.select("iframe[src], div.player-container iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src"))
            if (!src.isNullOrEmpty() && !src.contains("about:blank") && !src.contains("googletagmanager")) {
                sources.add(src)
            }
        }

        sources.forEach { iframe ->
            Log.d("DZK", "iframe » $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
