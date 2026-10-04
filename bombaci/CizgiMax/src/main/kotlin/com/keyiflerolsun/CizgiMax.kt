// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CizgiMax : MainAPI() {
    override var mainUrl              = "https://cizgimax.online"
    override var name                 = "CizgiMax"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Cartoon, TvType.Anime)

    override val mainPage = mainPageOf(
        "/yeni-eklenenler/"     to "Yeni Eklenenler",
        "/diziler/cizgi-film/"  to "Çizgi Filmler",
        "/diziler/anime/"       to "Animeler",
        "/diziler/dizi/"        to "Diziler",
        "/tur/aksiyon/"         to "Aksiyon",
        "/tur/animasyon/"       to "Animasyon",
        "/tur/aile/"            to "Aile",
        "/tur/bilim-kurgu/"     to "Bilim Kurgu",
        "/tur/komedi/"          to "Komedi",
        "/tur/macera/"          to "Macera",
        "/tur/gizem/"           to "Gizem"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "${mainUrl}${request.data}"
        } else {
            "${mainUrl}${request.data}page/${page}/"
        }

        val document = app.get(url).document
        val home     = document.select("div.film-list div.film-item, ul.filter-results li, div.poster-item, div.anime-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a.film-name, h2.truncate, a.poster-title, .title a, h2 a, h3 a")?.text()?.trim()
                        ?: this.selectFirst("a")?.text()?.trim() ?: return null
        
        val href      = fixUrlNull(
                            this.selectFirst("a.film-name")?.attr("href")
                            ?: this.selectFirst("div.poster-subject a")?.attr("href")
                            ?: this.selectFirst("a.poster")?.attr("href")
                            ?: this.selectFirst("a")?.attr("href")
                        ) ?: return null
        
        var posterUrl = this.selectFirst("a.poster img, div.poster-media img, img")?.attr("src")
                        ?: this.selectFirst("img")?.attr("data-src")
                        ?: this.selectFirst("img")?.attr("data-lazy-src")

        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("img")?.attr("src")
        }

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) { 
            this.posterUrl = fixUrlNull(posterUrl) 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara/?q=${query}").document
        val results = document.select("div.film-list div.film-item, ul.filter-results li, div.poster-item, div.anime-card").mapNotNull { it.toSearchResult() }

        if (results.isNotEmpty()) {
            return results
        }

        val response = app.get("${mainUrl}/ajaxservice/index.php?qr=${query}").parsedSafe<AjaxSearchResponse>()?.data?.result ?: return listOf()

        return response.mapNotNull { result ->
            if (result.sName.contains(".Bölüm", true) || result.sName.contains("Sezon", true) || result.sName.contains("-izle")) {
                return@mapNotNull null
            }

            newTvSeriesSearchResponse(
                result.sName,
                fixUrl(result.sLink),
                TvType.Cartoon
            ) {
                this.posterUrl = fixUrlNull(result.sImage)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.page-title, h1.entry-title, h1.film-title, h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(
                            document.selectFirst("img.series-profile-thumb, .poster img, .film-poster img, div.poster img, .series-poster img")?.attr("src")
                            ?: document.selectFirst("img.series-profile-thumb, .poster img, .film-poster img, div.poster img, .series-poster img")?.attr("data-src")
                          )
        val description = document.selectFirst("p#tv-series-desc, .description, .summary, .plot, .film-desc")?.text()?.trim()
        val tags        = document.select("div.genre-item a, .genres a, .tags a, .film-genres a").mapNotNull { it.text().trim() }
        val rating      = document.selectFirst("div.color-imdb, .rating, .imdb-rating, .score")?.text()?.trim()?.toRatingInt()

        val episodes = mutableListOf<Episode>()

        // 1. Standart CizgiMax Bölüm Kutuları
        val epElements = document.select("div.asisotope div.ajax_post, div.episodes-list a, div.bolumler a, ul.bolum-listesi a")
        if (epElements.isNotEmpty()) {
            epElements.forEach { element ->
                val epName     = element.selectFirst("span.episode-names, a, .title")?.text()?.trim() ?: element.text().trim()
                val epHref     = fixUrlNull(element.selectFirst("a")?.attr("href") ?: element.attr("href")) ?: return@forEach
                val epEpisode  = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val seasonName = element.selectFirst("span.season-name")?.text()?.trim() ?: ""
                val epSeason   = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = if (epName.isNotBlank()) epName else "$epSeason. Sezon ${epEpisode ?: 1}. Bölüm"
                        this.season = epSeason
                        this.episode = epEpisode
                    }
                )
            }
        }

        // 2. Yedek Taraması (Tüm Bölüm Linklerini Bulma)
        if (episodes.isEmpty()) {
            document.select("a[href]").forEach { element ->
                val epHref = fixUrlNull(element.attr("href")) ?: return@forEach
                val epText = element.text().trim().ifEmpty { element.attr("title").trim() }

                if (epHref == url || epHref.contains("/diziler/") || epHref.contains("/tur/") || 
                    epHref.contains("/yeni-eklenenler/") || epHref.contains("/ara/")) return@forEach

                val isEpisodeLink = epHref.contains("-bolum") || epHref.contains("-sezon") || epHref.contains("-izle")
                if (!isEpisodeLink) return@forEach

                val epEpisode = Regex("""(\d+)[-.\s]*(?:bolum|bölüm)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()

                val epSeason = Regex("""(\d+)[-.\s]*(?:sezon)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = if (epText.isNotBlank()) epText else "$epSeason. Sezon ${epEpisode ?: 1}. Bölüm"
                        this.season = epSeason
                        this.episode = epEpisode
                    }
                )
            }
        }

        val finalEpisodes = episodes.distinctBy { it.data }

        // 3. Tek Parça / Film Sayfası Fallback
        val episodeList = if (finalEpisodes.isEmpty()) {
            listOf(
                newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            )
        } else {
            finalEpisodes
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodeList) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("CZGM", "data » $data")
        val response = app.get(data)
        val document = response.document
        val rawHtml  = response.text

        val extractedUrls = mutableSetOf<String>()

        fun isValidVideoUrl(urlStr: String): Boolean {
            val lower = urlStr.lowercase()
            if (urlStr == data) return false
            if (lower.endsWith(".css") || lower.endsWith(".png") || lower.endsWith(".jpg") || 
                lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".svg") || 
                lower.endsWith(".gif") || lower.endsWith(".ico") || lower.endsWith(".woff") || 
                lower.endsWith(".woff2")) return false
            if (lower.contains("google") || lower.contains("wargamings") || lower.contains("fontawesome") || 
                lower.contains("swiper") || lower.contains("discord.com") || lower.contains("facebook.com") || 
                lower.contains("twitter.com") || lower.contains("cloudflare") || lower.contains("yandex") ||
                lower.contains("analytics") || lower.contains("disqus")) return false
            return true
        }

        fun addUrl(urlStr: String?) {
            if (urlStr.isNullOrBlank()) return
            var cleanUrl = urlStr.trim()
                .replace("\\/", "/")
                .replace("\\\"", "")
                .replace("&amp;", "&")

            if (cleanUrl.startsWith("//")) {
                cleanUrl = "https:$cleanUrl"
            }

            if (cleanUrl.length == 24 && !cleanUrl.contains("/") && !cleanUrl.contains(".")) {
                cleanUrl = "https://tau-video.xyz/embed/$cleanUrl"
            }

            fixUrlNull(cleanUrl)?.let { url ->
                if (isValidVideoUrl(url)) {
                    extractedUrls.add(url)
                }
            }
        }

        // 1. DOM Üzerindeki Iframe'ler ve srcdoc İçeriği
        document.select("iframe").forEach { iframe ->
            addUrl(iframe.attr("src"))
            addUrl(iframe.attr("data-src"))
            addUrl(iframe.attr("data-frame"))
            
            val srcdoc = iframe.attr("srcdoc")
            if (srcdoc.isNotBlank()) {
                Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE).findAll(srcdoc).forEach { match ->
                    addUrl(match.value)
                }
            }
        }

        // 2. Video Attribute Taraması
        document.select("[data-frame], [data-src], [data-embed], [data-video], [data-player], [data-url], [data-id]").forEach { element ->
            listOf("data-frame", "data-src", "data-embed", "data-video", "data-player", "data-url", "data-id").forEach { attr ->
                addUrl(element.attr(attr))
            }
        }

        // 3. Ham HTML İçindeki Oynatıcı/Embed Bağlantı Taraması
        Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE).findAll(rawHtml).forEach { match ->
            val url = match.value
            val lower = url.lowercase()
            if (lower.contains("tau-video") || lower.contains("sibnet") || lower.contains("vidmoly") || 
                lower.contains("dood") || lower.contains("streamtape") || lower.contains("filemoon") || 
                lower.contains("vudeo") || lower.contains("animecix") || lower.contains("vk.com") || 
                lower.contains("ok.ru") || lower.contains(".m3u8") || lower.contains(".mp4")) {
                addUrl(url)
            }
        }

        // 4. Tau Video ID Taraması
        Regex("""["']([a-zA-Z0-9_-]{20,32})["']""").findAll(rawHtml).forEach { match ->
            val id = match.groupValues[1]
            if (id.length == 24 || id.length == 32) {
                addUrl("https://tau-video.xyz/embed/$id")
            }
        }

        // 5. Base64 Kodlanmış Veriler
        Regex("""["']([a-zA-Z0-9+/=]{30,})["']""").findAll(rawHtml).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                if (decoded.contains("http://") || decoded.contains("https://")) {
                    Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE).findAll(decoded).forEach { m ->
                        addUrl(m.value)
                    }
                }
            } catch (_: Exception) {}
        }

        Log.d("CZGM", "Found extracted URLs: $extractedUrls")

        // 6. Bağlantıları Extractor'a Aktarma
        extractedUrls.forEach { videoUrl ->
            Log.d("CZGM", "Loading extractor for URL » $videoUrl")
            if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                callback(
                    ExtractorLink(
                        name,
                        name,
                        videoUrl,
                        mainUrl,
                        Qualities.Unknown.value,
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            } else {
                loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }

    private data class AjaxSearchResponse(
        val status: Boolean? = null,
        val data: AjaxData? = null
    )

    private data class AjaxData(
        val result: List<AjaxResult>? = null
    )

    private data class AjaxResult(
        val sName: String,
        val sLink: String,
        val sImage: String? = null
    )
}
