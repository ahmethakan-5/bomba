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

    // Cloudflare engelini aşmak için gerekli Chrome başlıkları
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Not-A.Brand\";v=\"99\", \"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    private val interceptor = Interceptor { chain ->
        val request = chain.request().newBuilder().apply {
            commonHeaders.forEach { (k, v) -> addHeader(k, v) }
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
            val document = app.get(
                mainUrl,
                headers = commonHeaders,
                interceptor = interceptor
            ).document

            // 1. Öne Çıkan / Poster Dizileri
            val mainItems = document.select("article, div.article-series-poster, div.poster-item, div.tv-card, .slider-item")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (mainItems.isNotEmpty()) {
                lists.add(HomePageList("Öne Çıkan Diziler", mainItems))
            }

            // 2. Izgara Tipi / Popüler Diziler
            val gridItems = document.select("article.article-series-small-grid, div.small-grid, .series-list li, .post-item")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (gridItems.isNotEmpty()) {
                lists.add(HomePageList("Popüler Diziler", gridItems))
            }

            // 3. Yedek Tarama (Genel Bağlantılar)
            if (lists.isEmpty()) {
                val fallbackItems = document.select("a[href*=/dizi/], a[href*=-sezon-]")
                    .mapNotNull { a ->
                        val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                        val title = a.text().trim().removeSuffix(" izle")
                        if (title.length > 2 && !href.contains("/kategori/") && !href.contains("/oyuncu/")) {
                            newTvSeriesSearchResponse(title, href, TvType.TvSeries)
                        } else null
                    }
                    .distinctBy { it.url }
                    .take(20)

                if (fallbackItems.isNotEmpty()) {
                    lists.add(HomePageList("Son Güncellemeler", fallbackItems))
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(lists, false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a.poster-title, div.tv-title, h2, h3, a[title]") ?: this.selectFirst("a")
        val href = fixUrlNull(titleElement?.attr("href") ?: this.selectFirst("a")?.attr("href")) ?: return null

        var title = titleElement?.attr("title")?.ifBlank { titleElement.text() } ?: this.text()
        title = title.removeSuffix(" izle").trim()

        if (title.isBlank() || href.contains("/kategori/") || href.contains("/oyuncu/")) return null

        val img = this.selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")
                ?: img?.attr("data-lazy-src")
                ?: img?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ---------- Arama ----------
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/?s=$query"
            val doc = app.get(searchUrl, headers = commonHeaders, interceptor = interceptor).document

            doc.select("article, div.post-item, ul.alphabetical-category-list li, div.search-result")
                .mapNotNull { element ->
                    val a = element.selectFirst("a") ?: return@mapNotNull null
                    val title = (a.attr("title").ifBlank { a.text() }).removeSuffix(" izle").trim()
                    val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                    if (title.contains(query, ignoreCase = true) || query.isEmpty()) {
                        element.toSearchResult() ?: newTvSeriesSearchResponse(title, href, TvType.TvSeries)
                    } else null
                }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- Dizi Detayı ----------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = commonHeaders, interceptor = interceptor).document

        val title = doc.selectFirst("h1")?.text()?.removeSuffix(" izle")?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.poster img")?.attr("src")
        )

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("div.series-summary, div.plot")?.text()

        val epRegex = Regex("""(\d+)-sezon-(\d+)-bolum""")
        val episodes = doc.select("a[href*=-sezon-][href*=-bolum], div.episodes a, ul.episodes-list a").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val match = epRegex.find(href)

            val seasonNum = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episodeNum = match?.groupValues?.getOrNull(2)?.toIntOrNull()

            newEpisode(href) {
                this.season = seasonNum
                this.episode = episodeNum
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
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
            val doc = app.get(data, headers = commonHeaders, interceptor = interceptor).document
            val iframe = doc.selectFirst("div#video-area iframe, iframe[src*=king], iframe[src*=player]")?.attr("src") ?: return false

            val fixedIframe = fixUrl(iframe)

            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = fixedIframe,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
