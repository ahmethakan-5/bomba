package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import org.jsoup.nodes.Element

class DiziBox : MainAPI() {
    override var mainUrl        = "https://www.dizibox.live"
    override var name           = "DiziBox"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cookies = mapOf("LockUser" to "true", "isTrustedUser" to "true")

    private val interceptor = Interceptor { chain ->
        val request  = chain.request()
        val response = chain.proceed(request)

        if (response.code == 403 || response.code == 530) {
            response.close()
            return@Interceptor cloudflareKiller.intercept(chain)
        }
        response
    }

    // ---------- Ana Sayfa ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, interceptor = interceptor, cookies = cookies).document

        val lists = listOf(
            HomePageList(
                "Dikkat Çeken Yeni Diziler",
                doc.select("section#new-series article.article-series-poster").mapNotNull { it.posterToResult() }
            ),
            HomePageList(
                "Efsane Diziler",
                doc.select("section#best-series article.article-series-small-grid").mapNotNull { it.smallGridToResult() }
            ),
            HomePageList(
                "Önerilen Diziler",
                doc.select("section#recommended-series article.article-series-small-grid").mapNotNull { it.smallGridToResult() }
            )
        ).filter { it.list.isNotEmpty() }

        return newHomePageResponse(lists, false)
    }

    private fun Element.posterToResult(): SearchResponse? {
        val a      = this.selectFirst("a.poster-title") ?: return null
        val title  = a.text().trim().ifBlank { return null }
        val href   = fixUrlNull(a.attr("href")) ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.smallGridToResult(): SearchResponse? {
        val title  = this.selectFirst("div.tv-title")?.text()?.removeSuffix(" izle")?.trim() ?: return null
        val href   = fixUrlNull(this.selectFirst("a.series-details")?.attr("href")) ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src")?.replace("-50x50", "-200x290"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    // ---------- Arama ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl, interceptor = interceptor, cookies = cookies).document
        
        // Jsoup nesnesi yerine doğrudan Element listesi üzerinde Kotlin mapNotNull kullanımı:
        return doc.select("ul.alphabetical-category-list li a")
            .mapNotNull { element ->
                val title = element.text().trim()
                val href  = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                
                if (title.contains(query, ignoreCase = true)) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries)
                } else null
            }
    }

    // ---------- Dizi Detayı ----------
    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url, interceptor = interceptor, cookies = cookies).document
        val title  = doc.selectFirst("h1")?.text()?.removeSuffix(" izle")?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot   = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val epRegex  = Regex("""(\d+)-sezon-(\d+)-bolum""")
        val episodes = doc.select("a[href*=-sezon-][href*=-bolum]").mapNotNull { a ->
            val href  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val match = epRegex.find(href) ?: return@mapNotNull null
            newEpisode(href) {
                this.season  = match.groupValues[1].toIntOrNull()
                this.episode = match.groupValues[2].toIntOrNull()
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    // ---------- Video Bağlantıları ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc    = app.get(data, interceptor = interceptor, cookies = cookies).document
        var iframe = doc.selectFirst("div#video-area iframe")?.attr("src") ?: return false
        iframe     = iframe.replace("king.php?v=", "king.php?wmode=opaque&v=")

        val doc2    = app.get(iframe, headers = mapOf("Referer" to data), interceptor = interceptor, cookies = cookies).document
        val iframe2 = doc2.selectFirst("div#Player iframe")?.attr("src") ?: return false

        val html = app.get(iframe2, headers = mapOf("Referer" to "$mainUrl/"), interceptor = interceptor).text

        val cryptData = Regex("CryptoJS\\.AES\\.decrypt\\(\"(.*?)\",\\s*\"").find(html)?.groupValues?.getOrNull(1) ?: return false
        val cryptPass = Regex("\",\\s*\"(.*?)\"\\);").find(html)?.groupValues?.getOrNull(1) ?: return false

        val decrypted = CryptoJS.decrypt(cryptPass, cryptData)
        val m3u8      = Regex("file:\\s*'(.*?)'").find(decrypted)?.groupValues?.getOrNull(1) ?: return false

        callback(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = m3u8,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = iframe2
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
