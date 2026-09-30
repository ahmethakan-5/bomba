// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class DiziPal : MainAPI() {
    // Domain dizipal2134.com olarak güncellendi
    override var mainUrl              = "https://dizipal2134.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    // Menü bağlantıları HTML yapısına (/platform/ ve /kategori/) göre güncellendi
    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/son-bolumler"                          to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/trend"                                         to "Trend",
        "${mainUrl}/anime"                                         to "Anime",
        "${mainUrl}/platform/netflix"                              to "Netflix",
        "${mainUrl}/platform/exxen"                                to "Exxen",
        "${mainUrl}/platform/blutv"                                to "BluTV",
        "${mainUrl}/platform/disney-plus"                          to "Disney+",
        "${mainUrl}/platform/prime-video"                          to "Amazon Prime",
        "${mainUrl}/platform/tabii"                                to "Tabii",
        "${mainUrl}/platform/gain"                                 to "Gain",
        "${mainUrl}/platform/max"                                  to "Max",
        "${mainUrl}/platform/paramount-plus"                       to "Paramount+",
        "${mainUrl}/kategori/bilim-kurgu"                          to "Bilimkurgu",
        "${mainUrl}/kategori/komedi"                               to "Komedi",
        "${mainUrl}/kategori/belgesel"                             to "Belgesel",
        "${mainUrl}/kategori/aksiyon"                              to "Aksiyon",
        "${mainUrl}/kategori/dram"                                 to "Dram",
        "${mainUrl}/kategori/gizem"                                to "Gizem",
        "${mainUrl}/kategori/korku"                                to "Korku",
        "${mainUrl}/kategori/suc"                                  to "Suç",
        "${mainUrl}/kategori/gerilim"                              to "Gerilim",
        "${mainUrl}/kategori/fantastik"                            to "Fantastik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data,
        ).document
        val home     = if (request.data.contains("/diziler/son-bolumler")) {
            document.select("div.episode-item").mapNotNull { it.sonBolumler() } 
        } else {
            // bakalim_4.py ile doğrulanan aynı seçici (selector) yapısı kullanılıyor
            document.select("article.type2 ul li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext=false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst("div.name")?.text() ?: return null
        val episode   = this.selectFirst("div.episode")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title     = "$name $episode"

        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("span.title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = "${mainUrl}${this.url}"
        val posterUrl = this.poster

        return if (this.type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseRaw = app.post(
            "${mainUrl}/api/search-autocomplete",
            headers     = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer     = "${mainUrl}/",
            data        = mapOf(
                "query" to query
            )
        )

        val searchItemsMap = jacksonObjectMapper().readValue<Map<String, SearchItem>>(responseRaw.text)

        val searchResponses = mutableListOf<SearchResponse>()

        for ((_, searchItem) in searchItemsMap) {
            searchResponses.add(searchItem.toPostSearchResult())
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val year        = document.selectXpath("//div[text()='Yapım Yılı']//following-sibling::div").text().trim().toIntOrNull()
        val description = document.selectFirst("div.summary p")?.text()?.trim()
        val tags        = document.selectXpath("//div[text()='Türler']//following-sibling::div").text().trim().split(" ").map { it.trim() }
        val rating      = document.selectXpath("//div[text()='IMDB Puanı']//following-sibling::div").text().trim().toRatingInt()
        val duration    = Regex("(\\d+)").find(document.selectXpath("//div[text()='Ortalama Süre']//following-sibling::div").text())?.value?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val title       = document.selectFirst("div.cover h5")?.text() ?: return null

            val episodes    = document.select("div.episode-item").mapNotNull {
                val epName    = it.selectFirst("div.name")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epEpisode = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(2)?.replace(".", "")?.toIntOrNull()
                val epSeason  = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(0)?.replace(".", "")?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
            }
        } else { 
            val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP", "data » $data")
        val document = app.get(data).document
        val iframe   = document.selectFirst(".series-player-container iframe")?.attr("src") ?: document.selectFirst("div#vast_new iframe")?.attr("src") ?: return false
        Log.d("DZP", "iframe » $iframe")

        val iSource = app.get(iframe, referer="${mainUrl}/").text
        val m3uLink = Regex("""file:"([^"]+)""").find(iSource)?.groupValues?.get(1)
        if (m3uLink == null) {
            Log.d("DZP", "iSource » $iSource")
            return loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        val subtitles = Regex(""""subtitle":"([^"]+)""").find(iSource)?.groupValues?.get(1)
        if (subtitles != null) {
            if (subtitles.contains(",")) {
                subtitles.split(",").forEach {
                    val subLang = it.substringAfter("[").substringBefore("]")
                    val subUrl  = it.replace("[${subLang}]", "")

                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = subLang,
                            url  = fixUrl(subUrl)
                        )
                    )
                }
            } else {
                val subLang = subtitles.substringAfter("[").substringBefore("]")
                val subUrl  = subtitles.replace("[${subLang}]", "")

                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = subLang,
                        url  = fixUrl(subUrl)
                    )
                )
            }
        }

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
                referer = "${mainUrl}/",
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )
        return true
    }
}
