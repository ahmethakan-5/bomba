// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

data class AlternatifData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("isim") val isim: String? = null
)

data class DataAlternatif(
    @JsonProperty("data") val data: List<AlternatifData> = emptyList()
)

class WebteIzle : MainAPI() {
    override var mainUrl              = "https://webteizle.info"
    override var name                 = "WebteIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage           = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.text().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/film-izle/"                   to "Güncel Filmler",
        "${mainUrl}/yeni-filmler"                 to "Yeni Filmler",
        "${mainUrl}/tavsiye-filmler"              to "Tavsiye Filmler",
        "${mainUrl}/trend"                        to "Trend Filmler",
        "${mainUrl}/imdb-top-250-izle"            to "IMDb Top 250",
        "${mainUrl}/filtre?tur=1"                 to "Aksiyon",
        "${mainUrl}/filtre?tur=2"                 to "Animasyon",
        "${mainUrl}/filtre?tur=3"                 to "Belgesel",
        "${mainUrl}/filtre?tur=4"                 to "Bilim Kurgu",
        "${mainUrl}/filtre?tur=6"                 to "Dram",
        "${mainUrl}/filtre?tur=7"                 to "Fantastik",
        "${mainUrl}/filtre?tur=8"                 to "Gerilim",
        "${mainUrl}/filtre?tur=9"                 to "Komedi",
        "${mainUrl}/filtre?tur=10"                to "Korku"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&s=$page" else "${request.data.trimEnd('/')}/$page"
        } else {
            request.data
        }

        val document = app.get(url, interceptor = interceptor).document
        val home     = document.select("div.card, div.golgever").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.getPoster(): String? {
        val img = this.selectFirst("img") ?: return null
        val dataSrc = img.attr("data-src").takeIf { it.isNotBlank() }
        val src = img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:image") }
        return fixUrlNull(dataSrc ?: src)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.filmtitle, div.filmname")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = this.getPoster()

        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        @Suppress("NAME_SHADOWING", "BlockingMethodInNonBlockingContext") 
        val encodedQuery = URLEncoder.encode(query, "windows-1254")

        val document = app.get(
            "${mainUrl}/filtre?a=${encodedQuery}",
            referer     = "${mainUrl}/",
            interceptor = interceptor
        ).document

        return document.select("div.card, div.golgever").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title       = document.selectFirst("[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: document.selectFirst("div.filmname")?.text()?.trim()
            ?: return null

        val poster      = fixUrlNull(document.selectFirst("div.card img, meta[property='og:image']")?.let { 
            it.attr("data-src").ifEmpty { it.attr("content") } 
        })
        
        val year        = document.selectFirst("span.year")?.text()?.toIntOrNull()
            ?: document.selectXpath("//td[contains(text(), 'Vizyon')]/following-sibling::td").text().trim().split(" ").last().toIntOrNull()
            
        val description = document.selectFirst("blockquote, meta[name='description']")?.attr("content")?.ifEmpty { 
            document.selectFirst("blockquote")?.text() 
        }?.trim()
        
        val tags        = document.select("span.tur, a[itemgroup='genre']").map { it.text().trim() }
        val rating      = document.selectFirst("span.imdb")?.text()?.trim()?.replace(",", ".")?.toRatingInt()
        
        val trailer     = document.selectFirst("button#fragman")?.attr("data-ytid")
            ?: document.selectFirst("span[data-yt]")?.attr("data-yt")

        val actors      = document.select("div[data-tab='oyuncular'] a").mapNotNull {
            val actorName = it.selectFirst("span")?.text()?.trim() ?: return@mapNotNull null
            val actorImg  = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
            Actor(actorName, actorImg)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            if (!trailer.isNullオーEmpty()) {
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("WBTI", "data » $data")
        val document = app.get(data, interceptor = interceptor).document

        val filmId  = document.selectFirst("button#wip")?.attr("data-id") 
            ?: document.selectFirst("input[name='filmid']")?.attr("value") 
            ?: return false

        Log.d("WBTI", "filmId » $filmId")

        val dilList = mutableListOf<String>()
        if (document.selectFirst("a[href*='/dublaj/'], i.audio.description") != null) {
            dilList.add("0")
        }
        if (document.selectFirst("a[href*='/altyazi/'], i.closed.captioning") != null) {
            dilList.add("1")
        }
        if (dilList.isEmpty()) {
            dilList.add("0")
        }

        dilList.forEach { dil ->
            val playerApi = app.post(
                "${mainUrl}/ajax/dataAlternatif3.asp",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data
                ),
                data = mapOf(
                    "filmid" to filmId,
                    "dil"    to dil,
                    "s"      to "",
                    "b"      to "",
                    "bot"    to "0"
                ),
                interceptor = interceptor
            ).text

            val playerData = AppUtils.tryParseJson<DataAlternatif>(playerApi) ?: return@forEach

            for (thisEmbed in playerData.data) {
                val embedId = thisEmbed.id ?: continue
                val embedApi = app.post(
                    "${mainUrl}/ajax/dataEmbed.asp",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data
                    ),
                    data = mapOf("id" to embedId.toString()),
                    interceptor = interceptor
                ).document

                var iframe = fixUrlNull(embedApi.selectFirst("iframe")?.attr("src"))

                if (iframe == null) {
                    val scriptSource = embedApi.html()
                    val matchResult  = Regex("""(vidmoly|okru|filemoon)\('([\d\w]+)'""").find(scriptSource)

                    if (matchResult != null) {
                        val platform = matchResult.groupValues[1]
                        val vidId    = matchResult.groupValues[2]

                        iframe = when (platform) {
                            "vidmoly"  -> "https://vidmoly.to/embed-${vidId}.html"
                            "okru"     -> "https://odnoklassniki.ru/videoembed/${vidId}"
                            "filemoon" -> "https://filemoon.sx/e/${vidId}"
                            else       -> null
                        }
                    }
                }

                iframe?.let { embedUrl ->
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
