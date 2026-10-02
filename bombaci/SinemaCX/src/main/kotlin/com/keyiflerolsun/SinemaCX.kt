// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.fasterxml.jackson.annotation.JsonProperty

class SinemaCX : MainAPI() {
    override var mainUrl              = "https://sinemacc.com"
    override var name                 = "SinemaCX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/kategori/aile-filmleri-izle/page/"         to "Aile Filmleri",
        "${mainUrl}/kategori/aksiyon-filmleri-izle/page/"      to "Aksiyon Filmleri",
        "${mainUrl}/kategori/animasyon-filmleri-izle/page/"   to "Animasyon Filmleri",
        "${mainUrl}/kategori/belgesel-filmleri-izle/page/"    to "Belgesel Filmleri",
        "${mainUrl}/kategori/bilim-kurgu-filmleri-izle/page/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/kategori/biyografi-filmleri-izle/page/"   to "Biyografi Filmleri",
        "${mainUrl}/kategori/fantastik-filmler-izle/page/"     to "Fantastik Filmler",
        "${mainUrl}/kategori/gizem-filmleri-izle/page/"        to "Gizem Filmleri",
        "${mainUrl}/kategori/komedi-filmleri-izle/page/"       to "Komedi Filmleri",
        "${mainUrl}/kategori/korku-filmleri-izle/page/"        to "Korku Filmleri",
        "${mainUrl}/kategori/macera-filmleri-izle/page/"       to "Macera Filmleri",
        "${mainUrl}/kategori/romantik-filmler-izle/page/"      to "Romantik Filmler",
        "${mainUrl}/kategori/erotik-filmler-izle/page/"        to "Erotik Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/").document
        val home     = document.select("div.film_kutusu").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.title span.text")?.text()?.trim()
            ?: this.selectFirst("div.title")?.text()?.trim()
            ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.image img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("div.image img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.film_kutusu").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.f-bilgi h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster      = fixUrlNull(document.selectFirst("link[rel='image_src']")?.attr("href"))
            ?: fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val year        = document.selectFirst("div.f-bilgi ul.detay a[href*='yapim']")?.text()?.toIntOrNull()
            ?: document.selectFirst("a[href*='yapim']")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.f-bilgi div.ackl")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")
        val tags        = document.select("div.f-bilgi div.tur a, div.tur a").map { it.text() }
        val rating      = document.selectFirst("b#puandegistir")?.text()?.trim()?.toRatingInt()
        val duration    = Regex("""Süre:?\s*</span>?(\d+)\s*Dakika""", RegexOption.IGNORE_CASE)
            .find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val actors      = document.select("li.oync li.oyuncu-k, div.oyuncu_k").mapNotNull {
            val actorName = it.selectFirst("span.isim, span.title")?.text() ?: return@mapNotNull null
            val actorImg  = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))
            Actor(actorName, actorImg)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            this.duration  = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SCX", "data » $data")
        val document = app.get(data).document
        
        // iframe data-vsrc veya standart src özniteliğinden yakalanır
        val iframeAttr = document.selectFirst("iframe")?.attr("data-vsrc")
            ?: document.selectFirst("iframe")?.attr("src")
            ?: return false

        val iframe = fixUrlNull(iframeAttr)?.substringBefore("?img=") ?: return false
        Log.d("SCX", "iframe » $iframe")

        val iframeSource         = app.get(iframe, referer = "${mainUrl}/").text
        val subtitleSectionRegex = Regex("""playerjsSubtitle\s*=\s*"(.+?)"""")
        val subtitleSectionMatch = subtitleSectionRegex.find(iframeSource)
        if (subtitleSectionMatch != null) {
            val subtitleSection = subtitleSectionMatch.groupValues[1]
            val subtitleRegex   = Regex("""\[(.*?)](https?://[^\s",]+)""")
            val subtitleMatches = subtitleRegex.findAll(subtitleSection)

            for (subtitleMatch in subtitleMatches) {
                val subtitleGroups   = subtitleMatch.groupValues
                val subtitleLanguage = subtitleGroups[1]
                val subtitleUrl      = subtitleGroups[2]

                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = subtitleLanguage,
                        url  = fixUrl(subtitleUrl)
                    )
                )
            }
        }

        if (iframe.contains("panel.sinema.gg") || iframe.contains("panel.sinema.cx") || iframe.contains("panel.sinema.cc")) {
            val panelHost = iframe.substringBefore("/player/")
            val dataParam = iframe.split("/").last()
            
            val vidUrl = app.post(
                "${panelHost}/player/index.php?data=${dataParam}&do=getVideo",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = "${mainUrl}/"
            ).parsedSafe<Panel>()?.securedLink ?: return false

            callback.invoke(
                ExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = vidUrl,
                    referer = iframe,
                    quality = Qualities.Unknown.value,
                    isM3u8  = true
                )
            )
        } else {
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }

    data class Panel(
        @JsonProperty("hls")         val hls: Boolean?        = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
