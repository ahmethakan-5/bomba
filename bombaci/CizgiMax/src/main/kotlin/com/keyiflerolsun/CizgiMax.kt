package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CizgiMax : MainAPI() {
    override var mainUrl = "https://cizgimax.online"
    override var name = "ÇizgiMax"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa - Son Bölümler",
        "$mainUrl/diziler/anime/" to "Animeler",
        "$mainUrl/diziler/cizgi-film/" to "Çizgi Filmler",
        "$mainUrl/diziler/dizi/" to "Diziler",
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/arsiv/?sort=populer&donem=daily" to "Trendler",
        "$mainUrl/yeni-eklenenler/" to "Yeni Eklenenler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(request.data).document
        val homePageList = mutableListOf<HomePageList>()

        // 1. Ana sayfadaki Öne Çıkan Slider Verilerini Çekme
        if (request.data == "$mainUrl/") {
            val sliderItems = doc.select(".slider-wrap .swiper-slide").mapNotNull { element ->
                val title = element.selectFirst("a.slide-name")?.text() ?: return@mapNotNull null
                val href = element.selectFirst("a.slide-name")?.attr("href") ?: return@mapNotNull null
                val style = element.attr("style") ?: ""
                
                // style="background-image: url('...')" yapısından görsel URL'sini yakalama
                val posterUrl = Regex("""background-image:\s*url\((?:&quot;\vert{}"\vert{}')?(.*?)(?:&quot;\vert{}"\vert{}')?\);""")
                    .find(style)?.groupValues?.get(1)

                newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                    this.posterUrl = posterUrl?.let { fixUrl(it) }
                }
            }
            if (sliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("Öne Çıkanlar", sliderItems))
            }
        }

        // 2. Film/Bölüm Kartlarını Parsing (Son Bölümler & Liste Sayfaları)
        val items = doc.select(".film-list .film-item, .episodes-block-body .film-item").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        if (items.isNotEmpty()) {
            homePageList.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(homePageList, false)
    }

    // HTML içerisindeki `.film-item` elemanlarını SearchResponse objesine dönüştürme
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.film-name")?.text() 
            ?: this.selectFirst(".inner a.poster img")?.attr("alt") 
            ?: return null

        val href = this.selectFirst("a.film-name")?.attr("href")
            ?: this.selectFirst("a.poster")?.attr("href") 
            ?: return null

        val poster = this.selectFirst("a.poster img")?.attr("src")

        // Köşe etiketi kontrolü (ANİME, ÇİZGİ, DİZİ, FİLM)
        val cornerTag = this.selectFirst(".corner-tag")?.text()?.uppercase() ?: ""
        val type = when {
            cornerTag.contains("ANİME") -> TvType.Anime
            cornerTag.contains("ÇİZGİ") -> TvType.Cartoon
            cornerTag.contains("DİZİ") -> TvType.TvSeries
            cornerTag.contains("FİLM") -> TvType.Movie
            else -> TvType.Anime
        }

        // Bölüm numarası ayrıştırma ("Bl. 26" -> 26)
        val epText = this.selectFirst(".ep-tag")?.text()?.replace("Bl.", "")?.trim()
        val epNum = epText?.toIntOrNull()

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, fixUrl(href), type) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        } else {
            newAnimeSearchResponse(title, fixUrl(href), type) {
                this.posterUrl = poster?.let { fixUrl(it) }
                if (epNum != null) {
                    addSub(epNum)
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara/?q=$query"
        val doc = app.get(url).document

        return doc.select(".film-list .film-item, .ss-list .ss-item, .search-suggest-body .film-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(fixUrl(url)).document

        val title = doc.selectFirst("h1, .anime-title, .title, .phc-title")?.text() ?: "Bilinmeyen Seri"
        val poster = doc.selectFirst(".poster img, .anime-poster img, .film-item img")?.attr("src")
        val description = doc.selectFirst(".description, .synopsis, .phc-desc, .summary")?.text()
        val tags = doc.select(".genres a, .tags a, .tur-list a, .mob-sub-list a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        // Sayfadaki bölüm listesini yakalama
        val epElements = doc.select(".episodes-list a, .bolumler-listesi a, .ep-item a, .season-episodes a")
        if (epElements.isNotEmpty()) {
            epElements.forEachIndexed { index, ep ->
                val epUrl = ep.attr("href")
                val epName = ep.text().ifEmpty { "${index + 1}. Bölüm" }
                episodes.add(
                    newEpisode(fixUrl(epUrl)) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        } else {
            // Eğer doğrudan tek bir bölüm veya film sayfasındaysa
            episodes.add(
                newEpisode(fixUrl(url)) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, fixUrl(url), TvType.Anime, episodes) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Sayfadaki iframe/player kaynaklarını tarama
        val iframes = doc.select("iframe[src], .player-container iframe, #player iframe")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                val fixedSrc = fixUrl(src)
                loadExtractor(fixedSrc, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
