// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import android.util.Base64
import org.jsoup.Jsoup

class KultFilmler : MainAPI() {
    override var mainUrl              = "https://kultfilmler.net"
    override var name                 = "KultFilmler"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/?sayfa="                                    to "Son Filmler",
        "${mainUrl}/category/aile-filmleri-izle/?sayfa="		to "Aile",
        "${mainUrl}/category/aksiyon-filmleri-izle/?sayfa="	to "Aksiyon",
        "${mainUrl}/category/animasyon-filmleri-izle/?sayfa="	to "Animasyon",
        "${mainUrl}/category/belgesel-izle/?sayfa="			to "Belgesel",
        "${mainUrl}/category/bilim-kurgu-filmleri-izle/?sayfa="   to "Bilim Kurgu",
        "${mainUrl}/category/biyografi-filmleri-izle/?sayfa="	to "Biyografi",
        "${mainUrl}/category/dram-filmleri-izle/?sayfa="		to "Dram",
        "${mainUrl}/category/fantastik-filmleri-izle/?sayfa="	to "Fantastik",
        "${mainUrl}/category/gerilim-filmleri-izle/?sayfa="	to "Gerilim",
        "${mainUrl}/category/gizem-filmleri-izle/?sayfa="		to "Gizem",
        "${mainUrl}/category/kara-filmleri-izle/?sayfa="		to "Kara",
        "${mainUrl}/category/kisa-film-izle/?sayfa="			to "Kısa Metrajlı",
        "${mainUrl}/category/komedi-filmleri-izle/?sayfa="		to "Komedi",
        "${mainUrl}/category/korku-filmleri-izle/?sayfa="		to "Korku",
        "${mainUrl}/category/macera-filmleri-izle/?sayfa="		to "Macera",
        "${mainUrl}/category/muzik-filmleri-izle/?sayfa="		to "Müzik",
        "${mainUrl}/category/polisiye-filmleri-izle/?sayfa="	to "Polisiye",
        "${mainUrl}/category/politik-filmleri-izle/?sayfa="	to "Politik",
        "${mainUrl}/category/romantik-filmleri-izle/?sayfa="	to "Romantik",
        "${mainUrl}/category/savas-filmleri-izle/?sayfa="		to "Savaş",
        "${mainUrl}/category/spor-filmleri-izle/?sayfa="		to "Spor",
        "${mainUrl}/category/suc-filmleri-izle/?sayfa="		to "Suç",
        "${mainUrl}/category/tarih-filmleri-izle/?sayfa="		to "Tarih",
        "${mainUrl}/category/yerli-filmleri-izle/?sayfa="		to "Yerli"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = if (page <= 1) request.data.substringBefore("?") else "${request.data}${page}"
        val document = app.get(url).document

        // Hem film kartlarını (a.mcard) hem de dizi kartlarını (a.dcard) yakala
        val home = document.select("a.mcard, a.dcard").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h3")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("a.mcard, a.dcard").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content")) ?: fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description     = document.selectFirst("div.description, div.mdesc, div.entry-content")?.text()?.trim()
        var tags            = document.select("ul.post-categories a, div.genres a").map { it.text() }
        val rating          = document.selectFirst("span.mscore b, div.imdb-count")?.text()?.trim()?.split(" ")?.first()?.toRatingInt()
        val year            = Regex("""(\d{4})""").find(document.selectFirst("span.pyear, li.release")?.text()?.trim() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val duration        = Regex("""(\d+)""").find(document.selectFirst("li.time")?.text()?.trim() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val recommendations = document.select("a.mcard, a.dcard").mapNotNull { it.toSearchResult() }
        val actors          = document.select("[href*='oyuncular']").map {
            Actor(it.text())
        }

        if (url.contains("/dizi/")) {
            tags  = document.select("div.category a, div.genres a").map { it.text() }

            val episodes = document.select("div.episode-box, div.ep-item").mapNotNull {
                val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val ssnDetail = it.selectFirst("span.episodetitle, span.snum")?.ownText()?.trim() ?: ""
                val epDetail  = it.selectFirst("span.episodetitle b, span.epnum")?.ownText()?.trim() ?: ""
                val epName    = if (ssnDetail.isNotEmpty()) "$ssnDetail - $epDetail" else it.text().trim()
                val epSeason  = Regex("""(\d+)""").find(ssnDetail)?.groupValues?.get(1)?.toIntOrNull()
                val epEpisode = Regex("""(\d+)""").find(epDetail)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.duration        = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.rating          = rating
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun getIframe(sourceCode: String): String {
        val atob = Regex("""PHA\+[0-9a-zA-Z+/=]*""").find(sourceCode)?.value ?: return ""

        val padding    = 4 - atob.length % 4
        val atobPadded = if (padding < 4) atob.padEnd(atob.length + padding, '=') else atob

        val iframe = Jsoup.parse(String(Base64.decode(atobPadded, Base64.DEFAULT), Charsets.UTF_8))

        return fixUrlNull(iframe.selectFirst("iframe")?.attr("src")) ?: ""
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("KLT", "data » $data")
        val document = app.get(data).document
        val iframes  = mutableSetOf<String>()

        val mainFrame = getIframe(document.html())
        if (mainFrame.isNotEmpty()) iframes.add(mainFrame)

        document.select("div.parts-middle a, div.source-list a").forEach {
            val alternatif = it.attr("href")
            if (alternatif.isNotEmpty()) {
                val alternatifDocument = app.get(alternatif).document
                val alternatifFrame    = getIframe(alternatifDocument.html())
                if (alternatifFrame.isNotEmpty()) iframes.add(alternatifFrame)
            }
        }

        for (iframe in iframes) {
            Log.d("KLT", "iframe » $iframe")
            if (iframe.contains("vidmoly")) {
                val headers  = mapOf(
                    "User-Agent"     to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36",
                    "Sec-Fetch-Dest" to "iframe"
                )
                val iSource = app.get(iframe, headers=headers, referer="${mainUrl}/").text
                val m3uLink = Regex("""file:"([^"]+)""").find(iSource)?.groupValues?.get(1) ?: continue

                Log.d("Kekik_VidMoly", "m3uLink » $m3uLink")

                callback.invoke(
                    ExtractorLink(
                        source  = "VidMoly",
                        name    = "VidMoly",
                        url     = m3uLink,
                        referer = "https://vidmoly.to/",
                        quality = Qualities.Unknown.value,
                        type    = INFER_TYPE
                    )
                )
            } else {
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
