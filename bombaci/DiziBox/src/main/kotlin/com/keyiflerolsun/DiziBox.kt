package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import org.jsoup.nodes.Element

class DiziBox : MainAPI() {
    // Sitenin güncel alan adını kontrol edip buraya yazın
    override var mainUrl        = "https://ddizibox.com" 
    override var name           = "DiziBox"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    
    // Bot korumasını aşmak için standart tarayıcı başlıkları
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val interceptor = Interceptor { chain ->
        val request  = chain.request().newBuilder().apply {
            headers.forEach { (key, value) -> addHeader(key, value) }
        }.build()
        
        val response = chain.proceed(request)

        if (response.code in listOf(403, 503, 521)) {
            response.close()
            return@Interceptor cloudflareKiller.intercept(chain)
        }
        response
    }

    // ---------- Ana Sayfa ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        try {
            val doc = app.get(mainUrl, interceptor = interceptor, headers = headers).document

            // Esnek Seçici 1: Genel Poster İçerikleri
            val newSeries = doc.select("article.article-series-poster, div.poster-item, div.tv-card")
                .mapNotNull { it.posterToResult() }
            if (newSeries.isNotEmpty()) {
                lists.add(HomePageList("Yeni Diziler", newSeries))
            }

            // Esnek Seçici 2: Izgara / Liste İçerikleri
            val smallGrid = doc.select("article.article-series-small-grid, div.small-grid, ul.series-list li")
                .mapNotNull { it.smallGridToResult() }
            if (smallGrid.isNotEmpty()) {
                lists.add(HomePageList("Önerilen ve Popüler Diziler", smallGrid))
            }

            // Esnek Seçici 3: Son Eklenen Bölümler / Genel Kartlar
            val generalCards = doc.select("a[href*=/dizi/], a[href*=/izle/]")
                .mapNotNull { element ->
                    val title = element.text().trim()
                    val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                    if (title.length > 2 && !href.contains("/kategori/")) {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries)
                    } else null
                }.take(15)
            
            if (lists.isEmpty() && generalCards.isNotEmpty()) {
                lists.add(HomePageList("Öne Çıkanlar", generalCards))
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(lists, false)
    }

    private fun Element.posterToResult(): SearchResponse? {
        val a      = this.selectFirst("a.poster-title, a[title], a") ?: return null
        val title  = (a.attr("title").ifBlank { a.text() }).trim()
        if (title.isBlank()) return null
        
        val href   = fixUrlNull(a.attr("href")) ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src") 
            ?: this.selectFirst("img")?.attr("src"))
            
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.smallGridToResult(): SearchResponse? {
        val title  = this.selectFirst("div.tv-title, h3, .title")?.text()?.removeSuffix(" izle")?.trim() ?: return null
        val href   = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src") 
            ?: this.selectFirst("img")?.attr("src"))
            
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    // ---------- Arama ----------
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/?s=$query"
            val doc = app.get(searchUrl, interceptor = interceptor, headers = headers).document

            doc.select("article, div.post-item, ul.alphabetical-category-list li")
                .mapNotNull { element ->
                    val a = element.selectFirst("a") ?: return@mapNotNull null
                    val title = a.text().trim()
                    val href  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                    if (title.contains(query, ignoreCase = true)) {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries)
                    } else null
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- Dizi Detayı ----------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = interceptor, headers = headers).document
        
        val title = doc.selectFirst("h1")?.text()?.removeSuffix(" izle")?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot   = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val epRegex  = Regex("""(\d+)-sezon-(\d+)-bolum""")
        val episodes = doc.select("a[href*=-sezon-][href*=-bolum], div.episodes a").mapNotNull { a ->
            val href  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val match = epRegex.find(href)
            
            val seasonNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val episodeNum = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1

            newEpisode(href) {
                this.season  = seasonNum
                this.episode = episodeNum
            }
        }.distinctBy { it.data }

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
        return try {
            val doc = app.get(data, interceptor = interceptor, headers = headers).document
            val iframe = doc.selectFirst("div#video-area iframe, iframe[src*=king]")?.attr("src") ?: return false
            
            callback(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = fixUrl(iframe),
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
