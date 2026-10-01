// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class IzleAI : MainAPI() {
    override var mainUrl              = "https://selcukflix.com"
    override var name                 = "720PizleAI"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    override val mainPage = mainPageOf(
        "${mainUrl}/film-izle"                   to "Son Eklenen Filmler",
        "${mainUrl}/dizi-izle"                   to "Son Eklenen Diziler",
        "${mainUrl}/kategori/aksiyon-filmleri"     to "Aksiyon Filmleri",
        "${mainUrl}/kategori/bilim-kurgu-filmleri" to "Bilim Kurgu Filmleri",
        "${mainUrl}/kategori/komedi-filmleri"      to "Komedi Filmleri",
        "${mainUrl}/kategori/korku-filmleri"       to "Korku Filmleri",
        "${mainUrl}/kategori/dram-filmleri"        to "Dram Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document
        
        // Ana sayfadaki film ve dizi kartlarını seçer
        val home = document.select("div.new-added-list a, article.movie-type-genres li a, div.grid a[href*='/dizi/'], div.grid a[href*='/film/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        // Film / Dizi Başlığı
        val title = this.selectFirst("h3")?.text()
            ?: this.selectFirst("img")?.attr("alt")?.replace(" izle", "")?.replace(" 2026 izle", "")
            ?: return null

        // Poster Görseli
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src") 
                ?: this.selectFirst("img")?.attr("data-src")
        )

        val isTvSeries = href.contains("/dizi/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchReq = app.post(
            "${mainUrl}/bg/searchcontent",
            data = mapOf(
                "searchterm" to query
            ),
            headers = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "${mainUrl}/"
        ).parsedSafe<SearchResult>()

        val veriler = mutableListOf<SearchResponse>()

        searchReq?.data?.result?.forEach { searchItem ->
            val title = searchItem.title ?: return@forEach
            val slug  = searchItem.slug ?: return@forEach
            val poster = searchItem.poster

            val url = if (slug.startsWith("http")) slug else "${mainUrl}/${slug.removePrefix("/")}"
            val isTv = url.contains("/dizi/")

            if (isTv) {
                veriler.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } else {
                veriler.add(newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }

        return veriler
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() 
            ?: document.selectFirst("h2")?.text()?.trim() 
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.relative img")?.attr("src") 
                ?: document.selectFirst("img[alt*='$title']")?.attr("src")
        )
        
        val year = document.selectFirst("span:contains(202)")?.text()?.toIntOrNull() 
            ?: document.selectFirst("a[href*='/yil/']")?.text()?.toIntOrNull()

        val description = document.selectFirst("div.text-base")?.text()?.trim() 
            ?: document.selectFirst("p.description")?.text()?.trim()

        val tags = document.select("a[href*='/kategori/'], span.line-clamp-1").map { it.text() }
        val rating = document.selectFirst("h4.text-xs")?.text()?.trim()?.toRatingInt()
        
        val isTv = url.contains("/dizi/")

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            
            // Bölüm listesini DOM üzerinden ayrıştırır
            document.select("a[href*='/sezon-']").forEach { epAnchor ->
                val epHref = fixUrlNull(epAnchor.attr("href")) ?: return@forEach
                val epText = epAnchor.selectFirst("div.text-white")?.text() ?: epAnchor.text()
                
                val seasonNum = Regex("sezon-(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = Regex("bolum-(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epText
                        this.season = seasonNum
                        this.episode = episodeNum
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("IzleAI", "data » $data")
        val document = app.get(data).document
        
        // Iframe seçici
        val iframe = fixUrlNull(
            document.selectFirst("iframe[src*='player']")?.attr("src") 
                ?: document.selectFirst("div.player iframe")?.attr("src")
                ?: document.selectFirst("iframe")?.attr("src")
        ) ?: return false

        Log.d("IzleAI", "iframe » $iframe")

        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)

        return true
    }
}
