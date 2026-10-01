// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class JetFilmizle : MainAPI() {
    override var mainUrl              = "https://jetfilmizle.now"
    override var name                 = "JetFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler/page/"           to "Tüm Filmler",
        "${mainUrl}/turkce-dublaj/page/"      to "Türkçe Dublaj",
        "${mainUrl}/turkce-altyazili/page/"   to "Türkçe Altyazılı",
        "${mainUrl}/yerli-filmler/page/"      to "Yerli Filmler",
        "${mainUrl}/diziler/page/"            to "Diziler",
        "${mainUrl}/trendler/page/"           to "Trendler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data.removeSuffix("/page/").removeSuffix("/") else "${request.data}$page"
        val document = app.get(url).document
        
        // Hem standart article/card elemanlarını hem de olası liste elemanlarını seçer
        val home = document.select("article, div.movie-card, div.film-card, div.card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2 a, h3 a, h4 a, h5 a, .card-title a, a.title") ?: return null
        var title = titleElement.text().trim()
        if (title.isBlank()) return null
        
        title = title.substringBefore(" izle").trim()

        val href = fixUrlNull(titleElement.attr("href") ?: this.selectFirst("a")?.attr("href")) ?: return null
        
        var posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("data-lazy-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))

        val isTv = href.contains("/dizi/")
        val tvType = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Yeni HTML yapısında arama GET isteği ile /arama?q=query şeklinde yapılıyor
        val document = app.get("${mainUrl}/arama?q=${query}").document
        return document.select("article, div.movie-card, div.film-card, div.card, div.search-result-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1, section.movie-exp div.movie-exp-title, .film-details h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img, section.movie-exp img, .film-cover img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("div.poster img, section.movie-exp img, .film-cover img")?.attr("src"))
        
        val yearDiv = document.select("div.yap, div.info-item, .film-info").text().trim()
        val year = Regex("""(\d{4})""").find(yearDiv)?.groupValues?.get(1)?.toIntOrNull()
        
        val description = document.selectFirst("p.aciklama, div.synopsis, div.description, section.movie-exp p")?.text()?.trim()
        val tags = document.select("div.catss a, div.genres a, .genres a").map { it.text().trim() }
        val rating = document.selectFirst("div.imdb_puan span, .imdb-score, .rating")?.text()?.split(" ")?.last()?.toRatingInt()
        
        val actors = document.select("div.oyuncu, div.actor-card, .cast-item").mapNotNull {
            val name = it.selectFirst("div.name, .actor-name")?.text()?.trim() ?: return@mapNotNull null
            val actorPoster = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))
            Actor(name, actorPoster)
        }

        val recommendations = document.select("div#benzers article, div.similar-movies div.card").mapNotNull {
            it.toSearchResult()
        }

        val isTv = url.contains("/dizi/")

        return if (isTv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, ArrayList()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("JTF", "data » $data")
        val document = app.get(data).document

        val iframes = mutableListOf<String>()
        
        // Ana oynatıcı iframe'ini yakala
        val mainIframe = fixUrlNull(document.selectFirst("div#movie iframe, div.player-container iframe, iframe#player")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("div#movie iframe, div.player-container iframe, iframe#player")?.attr("data"))
            ?: fixUrlNull(document.selectFirst("div#movie iframe, div.player-container iframe, iframe#player")?.attr("src"))

        if (mainIframe != null) {
            iframes.add(mainIframe)
        }

        // Alternatif parça/kaynak linkleri
        document.select("div.film_part a, div.player-servers a, ul.server-list a").forEach {
            val source = it.text().trim()
            if (source.lowercase().contains("fragman")) return@forEach

            val href = it.attr("href")
            if (href.isNotEmpty() && href != "#") {
                val movDoc = app.get(fixUrl(href)).document
                val iframe = fixUrlNull(movDoc.selectFirst("div#movie iframe, div.player-container iframe, iframe#player")?.attr("data-src"))
                    ?: fixUrlNull(movDoc.selectFirst("div#movie iframe, div.player-container iframe, iframe#player")?.attr("data"))
                    ?: fixUrlNull(movDoc.selectFirst("div#movie iframe, div.player-container iframe, iframe#player")?.attr("src"))

                if (iframe != null) {
                    iframes.add(iframe)
                } else {
                    movDoc.select("div#movie p a, div.download-links a").forEach downloadLinkForEach@{ link ->
                        val downloadLink = fixUrlNull(link.attr("href")) ?: return@downloadLinkForEach
                        iframes.add(downloadLink)
                    }
                }
            }
        }

        for (iframe in iframes.distinct()) {
            if (iframe.contains("jetv.xyz")) {
                Log.d("JTF", "jetv » $iframe")
                val jetvDoc = app.get(iframe).document
                val jetvIframe = fixUrlNull(jetvDoc.selectFirst("iframe")?.attr("src")) ?: continue
                Log.d("JTF", "jetvIframe » $jetvIframe")

                loadExtractor(jetvIframe, "${mainUrl}/", subtitleCallback, callback)
            } else {
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
