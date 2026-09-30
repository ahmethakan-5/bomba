package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    // Cloudflare Yapılandırması
    override var sequentialMainPage           = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    // Dinamik Cookie Üretici
    private fun getCookies(): Map<String, String> {
        return mapOf(
            "LockUser"      to "true",
            "isTrustedUser" to "true",
            "dbxu"          to System.currentTimeMillis().toString()
        )
    }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            // Hem eski Türkçe uyarıyı hem de Cloudflare varsayılan koruma metinlerini kontrol et
            val isBlocked = doc.text().contains("Güvenlik taramasından geçiriliyorsunuz") ||
                            doc.selectFirst("title")?.text()?.contains("Just a moment") == true ||
                            doc.selectFirst("div.cf-browser-verification") != null

            if (isBlocked) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi-arsivi/page/SAYFA/?ulke[]=turkiye&yil=&imdb"   to "Yerli",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=aile&yil&imdb"       to "Aile",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=aksiyon&yil&imdb"    to "Aksiyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=animasyon&yil&imdb"  to "Animasyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=belgesel&yil&imdb"   to "Belgesel",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=bilimkurgu&yil&imdb" to "Bilimkurgu",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=dram&yil&imdb"       to "Dram",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=fantastik&yil&imdb"  to "Fantastik",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=gerilim&yil&imdb"    to "Gerilim",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=gizem&yil&imdb"      to "Gizem",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=komedi&yil&imdb"     to "Komedi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=korku&yil&imdb"      to "Korku",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=macera&yil&imdb"     to "Macera",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=romantik&yil&imdb"   to "Romantik",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=savas&yil&imdb"      to "Savaş",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=suc&yil&imdb"        to "Suç",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=tarih&yil&imdb"      to "Tarih"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = request.data.replace("SAYFA", "$page")
        val document = app.get(
            url,
            cookies     = getCookies(),
            interceptor = interceptor
        ).document

        // Genişletilmiş HTML Seçicileri
        val home = document.select("article.detailed-article, article.post, article.grid-box, div.article-series-small-grid")
            .mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("h3 a, h2 a, div.post-title a")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("h3 a, h2 a, div.post-title a, a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "${mainUrl}/?s=${query}",
            cookies     = getCookies(),
            interceptor = interceptor
        ).document

        return document.select("article.detailed-article, article.post, article.grid-box")
            .mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            cookies     = getCookies(),
            interceptor = interceptor
        ).document

        val title       = document.selectFirst("div.tv-overview h1 a, h1.entry-title, title")?.text()?.replace("izle", "")?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.tv-overview figure img, div.poster img")?.attr("src"))
        val description = document.selectFirst("div.tv-story p, div.entry-content p")?.text()?.trim()
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("a[href*='/tur/']").map { it.text() }
        val rating      = document.selectFirst("span.label-imdb b, div.imdb-rate span")?.text()?.trim()?.toRatingInt()
        val actors      = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer     = document.selectFirst("div.tv-overview iframe, iframe[src*='youtube']")?.attr("src")

        val episodeList = mutableListOf<Episode>()
        
        // Sezon listesi veya doğrudan bölüm listesi taraması
        val seasonElements = document.select("div#seasons-list a, div.seasons-list a")
        if (seasonElements.isNotEmpty()) {
            seasonElements.forEach {
                val epUrl = fixUrlNull(it.attr("href")) ?: return@forEach
                val epDoc = app.get(
                    epUrl,
                    cookies     = getCookies(),
                    interceptor = interceptor
                ).document

                epDoc.select("article.grid-box, article.post, div.episode-box").forEach ep@ { epElem ->
                    val epTitle   = epElem.selectFirst("div.post-title a, h3 a")?.text()?.trim() ?: return@ep
                    val epHref    = fixUrlNull(epElem.selectFirst("div.post-title a, h3 a")?.attr("href")) ?: return@ep
                    val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                    episodeList.add(newEpisode(epHref) {
                        this.name    = epTitle
                        this.season  = epSeason
                        this.episode = epEpisode
                    })
                }
            }
        } else {
            // Sayfadaki direkt bölüm bağlantıları
            document.select("article.grid-box, article.post, div.episode-list a").forEach ep@ { epElem ->
                val epTitle   = epElem.selectFirst("div.post-title a, h3 a")?.text()?.trim() ?: epElem.text().trim()
                val epHref    = fixUrlNull(epElem.selectFirst("a")?.attr("href") ?: epElem.attr("href")) ?: return@ep
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name    = epTitle
                    this.season  = epSeason
                    this.episode = epEpisode
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.rating    = rating
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private suspend fun iframeDecode(data: String, iframe: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var targetIframe = iframe

        try {
            if (targetIframe.contains("/player/king/")) {
                if (!targetIframe.contains("wmode=opaque")) {
                    targetIframe = targetIframe.replace("king.php?v=", "king.php?wmode=opaque&v=")
                }
                val subDoc = app.get(
                    targetIframe,
                    referer     = data,
                    cookies     = getCookies(),
                    interceptor = interceptor
                ).document
                val subFrame = subDoc.selectFirst("div#Player iframe, iframe")?.attr("src") ?: return false

                val iDoc          = app.get(subFrame, referer = "${mainUrl}/").text
                val cryptData     = Regex("""CryptoJS\.AES\.decrypt\("(.*?)"""").find(iDoc)?.groupValues?.get(1) ?: return false
                val cryptPass     = Regex("""","(.*?)"""\);""").find(iDoc)?.groupValues?.get(1) ?: return false
                val decryptedData = CryptoJS.decrypt(cryptPass, cryptData)
                val decryptedDoc  = Jsoup.parse(decryptedData)
                val vidUrl        = Regex("""file:\s*'(.*?)'""").find(decryptedDoc.html())?.groupValues?.get(1) ?: return false

                callback.invoke(
                    ExtractorLink(
                        source  = this.name,
                        name    = this.name,
                        url     = vidUrl,
                        referer = vidUrl,
                        quality = getQualityFromName("4k"),
                        isM3u8  = vidUrl.contains(".m3u8")
                    )
                )
                return true

            } else if (targetIframe.contains("/player/moly/") || targetIframe.contains("/player/haydi")) {
                val subDoc = app.get(
                    targetIframe,
                    referer     = data,
                    cookies     = getCookies(),
                    interceptor = interceptor
                ).document

                val atobData = Regex("""unescape\("(.*?)"\)""").find(subDoc.html())?.groupValues?.get(1)
                var parsedDoc = subDoc
                if (atobData != null) {
                    val decodedAtob = atobData.decodeUri()
                    val strAtob     = String(Base64.decode(decodedAtob, Base64.DEFAULT), Charsets.UTF_8)
                    parsedDoc       = Jsoup.parse(strAtob)
                }

                val subFrame = parsedDoc.selectFirst("div#Player iframe, iframe")?.attr("src") ?: return false
                loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
                return true
            } else {
                // Genel durumlarda standart extractor yükleyiciye devret
                loadExtractor(targetIframe, "${mainUrl}/", subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            Log.e("DZBX", "iframeDecode Error: ${e.message}")
            return false
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZBX", "data » $data")
        val document = app.get(
            data,
            cookies     = getCookies(),
            interceptor = interceptor
        ).document

        var iframe = document.selectFirst("div#video-area iframe, div.video-container iframe")?.attr("src")
        if (iframe != null) {
            iframeDecode(data, iframe, subtitleCallback, callback)
        }

        // Alternatif dil / kaynak seçenekleri
        document.select("div.video-toolbar option[value], select#select-source option[value]").forEach {
            val altLink = it.attr("value")
            if (altLink.isNotEmpty() && altLink != "#") {
                val subDoc = app.get(
                    altLink,
                    cookies     = getCookies(),
                    interceptor = interceptor
                ).document
                iframe = subDoc.selectFirst("div#video-area iframe, div.video-container iframe")?.attr("src")
                if (iframe != null) {
                    iframeDecode(data, iframe, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
