// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! Cloudflare Bypass Ayarları
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/sayfa/"                                  to "Son Filmler",
        "${mainUrl}/film-izle/olmeden-izlenmesi-gerekenler-fm1/sayfa/" to "Ölmeden İzle",
        "${mainUrl}/tur/aksiyon-fmy54y/film/sayfa/"                    to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu-fm3/film/sayfa/"                   to "Bilim Kurgu",
        "${mainUrl}/tur/macera-fm1/film/sayfa/"                        to "Macera",
        "${mainUrl}/tur/komedi-fm1/film/sayfa/"                        to "Komedi",
        "${mainUrl}/tur/romantik-fm1/film/sayfa/"                      to "Romantik",
        "${mainUrl}/tur/belgesel/film/sayfa/"                          to "Belgesel",
        "${mainUrl}/tur/fantastik-fm1/film/sayfa/"                     to "Fantastik",
        "${mainUrl}/tur/polisiye/film/sayfa/"                          to "Polisiye",
        "${mainUrl}/tur/korku-fm2/film/sayfa/"                         to "Korku"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("a.item, section#film_posts article, div.movie-box, div.poster-box").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.title, h6 a, h2, div.film-title")?.text()?.trim()
            ?: this.attr("data-title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val href = fixUrlNull(this.attr("href"))
            ?: fixUrlNull(this.selectFirst("h6 a, a")?.attr("href"))
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title     = this.select("a").last()?.text()?.trim() ?: this.selectFirst("div.title")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.select("a").last()?.attr("href")) ?: fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/?s=${query}").document

        return document.select("a.item, section#film_posts article, div.movie-box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("div#film_izle h1, h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description     = document.select("section#film_single article p, div.entry-content p").last()?.text()?.trim()
        val tags            = document.selectFirst("dt:contains(Tür:) + dd")?.text()?.split(",")?.map { it.trim() }
        val rating          = document.selectFirst("dt:contains(IMDB Puanı:) + dd")?.text()?.trim()?.toRatingInt()
        val year            = document.selectFirst("dt:contains(Yapım Yılı:) + dd")?.text()?.trim()?.toIntOrNull()

        val recommendations = document.select("div.hidden-mobile li, div.film-list a.item").mapNotNull { it.toRecommendResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.rating          = rating
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FLMM", "data » $data")
        val document = app.get(data).document

        val iframeElement = document.selectFirst("div.player-div iframe, div#player iframe, iframe[data-src], iframe[src]")
        val rawIframe = iframeElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: iframeElement?.attr("src")?.takeIf { it.isNotBlank() }
            ?: return false

        val iframe = fixUrl(rawIframe)
        Log.d("FLMM", "iframe » $iframe")

        val loaded = loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)

        if (!loaded) {
            CloseLoad().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
