// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Dizilla : MainAPI() {
    override var mainUrl              = "https://dizilla.now"
    override var name                 = "Dizilla"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/"                  to "Popüler Diziler",
        "${mainUrl}/yabanci-dizi-izle" to "Yabancı Diziler",
        "${mainUrl}/anime-izle"        to "Animeler",
        "${mainUrl}/kdrama-izle"       to "K-Drama",
        "${mainUrl}/arsiv"             to "Keşfet"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document

        val home = document.select("ul.trends li, div.grid > div, div.grid a, article.movie-type-genres li, div.grid-cols-3 a, div.grid-cols-2 a, div.grid-cols-4 a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a")
        val href = aTag?.attr("href") ?: return null
        if (href.isEmpty() || href == "#") return null

        val fullUrl = fixUrlNull(href) ?: return null
        val img = this.selectFirst("img") ?: aTag.selectFirst("img")
        val rawTitle = this.selectFirst("h2, h3, div.title, span.title, span.font-bold")?.text()
            ?: img?.attr("alt")
            ?: img?.attr("title")
            ?: return null

        val title = rawTitle.replace(Regex("(?i)\\s+izle$"), "").trim()
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(img?.attr("src"))
            ?: fixUrlNull(img?.attr("data-src"))
            ?: fixUrlNull(img?.attr("data-lazy-src"))

        return newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val titleText = title ?: return null
        val slugText  = slug ?: return null
        return newTvSeriesSearchResponse(
            titleText,
            "${mainUrl}/${slugText}",
            TvType.TvSeries,
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val veriler = mutableListOf<SearchResponse>()

        // 1. JSON POST arama denemesi
        try {
            val searchReq = app.post(
                "${mainUrl}/bg/searchcontent",
                data = mapOf("searchterm" to query, "q" to query),
                headers = mapOf(
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "${mainUrl}/"
            ).parsedSafe<SearchResult>()

            searchReq?.data?.result?.forEach { searchItem ->
                searchItem.toSearchResponse()?.let { veriler.add(it) }
            }

            if (veriler.isNotEmpty()) return veriler
        } catch (_: Exception) {}

        // 2. HTML GET arama (Arşiv / Keşfet arama fallback'i)
        try {
            val document = app.get("${mainUrl}/arsiv?q=${query}").document
            val results = document.select("ul.trends li, div.grid > div, div.grid a, article.movie-type-genres li, a[href*='dizi/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        } catch (_: Exception) {}

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.page-top h1")?.text() ?: document.selectFirst("h1")?.text() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.page-top img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("div.page-top img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("img.object-cover")?.attr("src"))
        val year        = document.selectXpath("//span[text()='Yayın tarihi']//following-sibling::span").text().trim().split(" ").last().toIntOrNull()
        val description = document.selectFirst("div.mv-det-p")?.text()?.trim() ?: document.selectFirst("div.w-full div.text-base")?.text()?.trim()
        val tags        = document.select("[href*='dizi-turu']").map { it.text() }
        val rating      = document.selectFirst("a[href*='imdb.com'] span")?.text()?.trim().toRatingInt()
        val duration    = Regex("(\\d+)").find(document.select("div.gap-3 span.text-sm").getOrNull(1)?.text() ?: "")?.value?.toIntOrNull()
        val actors      = document.select("[href*='oyuncu']").map { Actor(it.text()) }

        val episodeList = mutableListOf<Episode>()

        val seasonLinks = document.selectXpath("//div[contains(@class, 'gap-2')]/a[contains(@href, '-sezon')]")
        val docsToParse = if (seasonLinks.isNotEmpty()) {
            seasonLinks.mapNotNull { fixUrlNull(it.attr("href")) }.distinct().map { app.get(it).document }
        } else {
            listOf(document)
        }

        docsToParse.forEach { epDoc ->
            epDoc.select("div.episodes div.cursor-pointer, div.episodes a, div.dub-episodes div.cursor-pointer").forEach ep@ { episodeElement ->
                val epHref        = fixUrlNull(episodeElement.selectFirst("a")?.attr("href") ?: episodeElement.attr("href")) ?: return@ep
                val epName        = episodeElement.select("a").last()?.text()?.trim() ?: episodeElement.text().trim()
                val epDescription = episodeElement.selectFirst("span.t-content, p")?.text()?.trim()
                val epPoster      = fixUrlNull(epDoc.selectFirst("img.object-cover, div.page-top img")?.attr("src"))
                val epEpisode     = episodeElement.selectFirst("a.opacity-60")?.text()?.toIntOrNull()
                    ?: Regex("(\\d+)\\.\\s*Bölüm").find(epName)?.groupValues?.get(1)?.toIntOrNull()

                val parentDiv   = episodeElement.parent()
                val seasonClass = parentDiv?.className()?.split(" ")?.find { className -> className.startsWith("szn") }
                val epSeason    = seasonClass?.substringAfter("szn")?.toIntOrNull()
                    ?: Regex("(\\d+)\\.\\s*Sezon").find(epDoc.location())?.groupValues?.get(1)?.toIntOrNull()

                val isDub     = episodeElement.parents().any { it.hasClass("dub-episodes") }
                val finalName = if (isDub && !epName.contains("Dublaj", ignoreCase = true)) "$epName Dublaj" else epName

                episodeList.add(newEpisode(epHref) {
                    this.name        = finalName
                    this.season      = epSeason
                    this.episode     = epEpisode
                    this.description = epDescription
                    this.posterUrl   = epPoster
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList.distinctBy { it.data }) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            this.duration  = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZL", "data » $data")
        val document = app.get(data).document
        val iframes  = mutableSetOf<String>()

        val alternatifler = document.select("a[href*='player']")
        if (alternatifler.isEmpty()) {
            val iframe = fixUrlNull(
                document.selectFirst("div#playerLsDizilla iframe, iframe#player, div.player iframe, iframe")?.attr("src")
            ) ?: return false

            Log.d("DZL", "iframe » $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        } else {
            alternatifler.forEach {
                val playerDoc = app.get(fixUrlNull(it.attr("href")) ?: return@forEach).document
                val iframe    = fixUrlNull(
                    playerDoc.selectFirst("div#playerLsDizilla iframe, iframe#player, div.player iframe, iframe")?.attr("src")
                ) ?: return@forEach

                if (iframe in iframes) { return@forEach }
                iframes.add(iframe)

                Log.d("DZL", "iframe » $iframe")
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }

    data class SearchResult(
        val data: SearchData? = null
    )

    data class SearchData(
        val state: Boolean? = null,
        val result: List<SearchItem>? = emptyList()
    )

    data class SearchItem(
        val title: String? = null,
        val slug: String? = null,
        val poster: String? = null
    )
}
