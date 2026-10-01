package com.lagradost.cloudstream3.extractors // Kendi paket yoluna göre (örn: com.ahmethakan.bomba) düzenleyebilirsin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.loadExtractor

class Filmmodu : MainAPI() {
    override var mainUrl = "https://filmmodu.live"
    override var name = "Filmmodu"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // HTML Header menüsünden alınan ana sayfa kategorileri
    override val mainPage = mainPageOf(
        "$mainUrl/filmler?page=" to "Filmler",
        "$mainUrl/diziler?page=" to "Diziler",
        "$mainUrl/animes?page=" to "Animeler",
        "$mainUrl/kesfet?page=" to "Keşfet"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        
        // DİKKAT: "div.movie-card" kısmını sitedeki gerçek içerik kutusunun class'ı ile değiştirin.
        val home = document.select("div.movie-card, article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // JSON-LD kısmında belirtilen arama şablonu kullanıldı
        val url = "$mainUrl/ara?q=$query"
        val document = app.get(url).document

        return document.select("div.movie-card, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // DİKKAT: h2, a ve img etiketlerini sitenin yapısına göre güncelle.
        val title = this.selectFirst("h2, .title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src") // veya data-src olabilir

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-title")?.text() ?: return null
        val poster = document.selectFirst("img.poster")?.attr("src")
        val plot = document.selectFirst("div.summary")?.text()

        // Film veya Dizi ayrımı
        val isTvSeries = url.contains("/dizi/")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            // Dizi bölümlerini çeken Jsoup selector'ını buraya eklemelisin
            document.select("ul.episodes li a").forEach {
                episodes.add(
                    Episode(
                        data = it.attr("href"),
                        name = it.text()
                    )
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
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
        
        // Iframe'leri veya video kaynaklarını bulup extract etme işlemi
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
            }
        }
        
        return true
    }
}
