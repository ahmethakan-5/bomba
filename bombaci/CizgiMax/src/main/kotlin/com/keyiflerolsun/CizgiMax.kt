// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CizgiMax : MainAPI() {
    override var mainUrl              = "https://cizgimax.online"
    override var name                 = "CizgiMax"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Cartoon, TvType.Anime, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/yeni-eklenenler/"     to "Yeni Eklenenler",
        "/diziler/cizgi-film/" to "Çizgi Film",
        "/diziler/anime/"      to "Anime",
        "/diziler/dizi/"       to "Dizi",
        "/film/"               to "Film",
        "/tur/aksiyon/"        to "Aksiyon",
        "/tur/komedi/"         to "Komedi",
        "/tur/macera/"         to "Macera",
        "/tur/bilim-kurgu/"    to "Bilim Kurgu",
        "/tur/aile/"           to "Aile"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "${mainUrl}${request.data}" else "${mainUrl}${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.film-item, div.slide-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.film-name, a.slide-name, h2.truncate, h2")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val href = fixUrlNull(
            this.selectFirst("a.film-name")?.attr("href")
                ?: this.selectFirst("a.poster")?.attr("href")
                ?: this.selectFirst("a.slide-name")?.attr("href")
                ?: this.selectFirst("a")?.attr("href")
        ) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara/?q=${query}").document
        return document.select("div.film-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.page-title, h1.series-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("img.series-profile-thumb")?.attr("src")
                ?: document.selectFirst("div.series-profile-thumb img")?.attr("src")
                ?: document.selectFirst("div.poster img")?.attr("src")
        )
        val description = document.selectFirst("p#tv-series-desc, div.series-summary, div.description")?.text()?.trim()
        val tags = document.select("div.genre-item a, div.genres a").mapNotNull { it.text().trim() }
        val rating = document.selectFirst("div.color-imdb, div.rating")?.text()?.trim()?.toRatingInt()

        val episodeElements = document.select("div.asisotope div.ajax_post, div.episode-item, div.film-item, a[href*='-bolum-izle']")

        val episodes = if (episodeElements.isNotEmpty()) {
            episodeElements.mapNotNull { epEl ->
                val epHref = fixUrlNull(epEl.attr("href").ifEmpty { epEl.selectFirst("a")?.attr("href") }) ?: return@mapNotNull null
                val epName = epEl.selectFirst("span.episode-names")?.text()?.trim()
                    ?: epEl.selectFirst("a.film-name")?.text()?.trim()
                    ?: epEl.text().trim()

                val season = Regex("""(?i)(\d+)\s*[-.]?\s*Sezon""").find("$epName $epHref")?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)sezon[-_/\s]*(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                val episode = Regex("""(?i)(\d+)\s*[-.]?\s*Bölüm""").find("$epName $epHref")?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)bolum[-_/\s]*(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)Bl\.\s*(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                }
            }
        } else {
            listOf(
                newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.rating = rating
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("CZGM", "data » $data")
        val document = app.get(data).document

        val sources = document.select("ul.linkler li a, ul.linkler li, iframe[src], button[data-frame]")
        sources.forEach { el ->
            val iframe = fixUrlNull(
                el.attr("data-frame")
                    .ifEmpty { el.attr("data-src") }
                    .ifEmpty { el.attr("src") }
                    .ifEmpty { el.selectFirst("a")?.attr("data-frame") }
                    .ifEmpty { el.selectFirst("iframe")?.attr("src") }
            ) ?: return@forEach

            if (iframe.startsWith("http")) {
                Log.d("CZGM", "iframe » $iframe")
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
