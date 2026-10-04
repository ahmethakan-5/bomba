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

        val epElements = document.select("div.asisotope div.ajax_post")
        val episodes = if (epElements.isNotEmpty()) {
            epElements.mapNotNull {
                val epName     = it.selectFirst("span.episode-names")?.text()?.trim() ?: return@mapNotNull null
                val epHref     = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epEpisode  = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val seasonName = it.selectFirst("span.season-name")?.text()?.trim() ?: ""
                val epSeason   = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }
        } else {
            val rawEpisodes = document.select("a[href*=-bolum], a[href*=-sezon], div.film-list a, .episode-list a, .dizi-bolumleri a, ul.bolumler a").mapNotNull { element ->
                val epHref = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                val epText = element.text().trim().ifEmpty { element.attr("title").trim() }

                if (!epHref.contains("-bolum") && !epHref.contains("-sezon") && !epHref.contains("-izle")) {
                    return@mapNotNull null
                }

                val epEpisode = Regex("""(\d+)[-.\s]*(?:bolum|bölüm)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()

                val epSeason = Regex("""(\d+)[-.\s]*(?:sezon)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                if (epEpisode == null) return@mapNotNull null

                newEpisode(epHref) {
                    this.name = if (epText.isNotBlank()) epText else "$epSeason. Sezon $epEpisode. Bölüm"
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }.distinctBy { it.data }

            if (rawEpisodes.isEmpty()) {
                listOf(
                    newEpisode(url) {
                        this.name = title
                        this.season = 1
                        this.episode = 1
                    }
                )
            } else {
                rawEpisodes
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
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

        val iframeUrls = mutableSetOf<String>()

        fun isValidVideoUrl(urlStr: String): Boolean {
            val lower = urlStr.lowercase()
            if (urlStr == data || lower.contains("-izle") || lower.contains("/diziler/")) return false
            if (lower.endsWith(".css") || lower.endsWith(".png") || lower.endsWith(".jpg") || 
                lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".svg") || 
                lower.endsWith(".gif") || lower.endsWith(".ico") || lower.endsWith(".woff") || lower.endsWith(".woff2")) return false
            if (lower.contains("google") || lower.contains("bunny.net") || lower.contains("fontawesome") || 
                lower.contains("swiper") || lower.contains("discord.com") || lower.contains("youtube.com") || 
                lower.contains("facebook.com") || lower.contains("twitter.com") || lower.contains("cloudflare") ||
                lower.contains("wargamings.net") || lower.contains("reklam")) return false
            if (lower.contains("/api/") || lower.contains("dosya secilmedi") || lower.contains("/ajaxservice/")) return false
            return true
        }

        fun cleanAndAddUrl(urlStr: String?) {
            if (urlStr.isNullOrBlank()) return
            var cleanUrl = urlStr.trim().replace("\\/", "/")

            if (cleanUrl.startsWith("//")) {
                cleanUrl = "https:$cleanUrl"
            }

            if (cleanUrl.length == 24 && !cleanUrl.contains("/") && !cleanUrl.contains(".")) {
                cleanUrl = "https://tau-video.xyz/embed/$cleanUrl"
            }

            fixUrlNull(cleanUrl)?.let { url ->
                if (isValidVideoUrl(url)) {
                    iframeUrls.add(url)
                }
            }
        }

        // 1. Doğrudan Video/Player Adresi İçeren Embed Linklerini Yakala
        Regex("""src=["']([^"']*(?:tau-video|animecix|sibnet|vidmoly|dood|streamtape|vudeo|ruframe|filemoon)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(rawHtml).forEach { match ->
                cleanAndAddUrl(match.groupValues[1])
            }

        // 2. DOM Üzerindeki Gerçek Iframe ve Player Etiketleri
        document.select("iframe").forEach { iframe ->
            cleanAndAddUrl(iframe.attr("src"))
            cleanAndAddUrl(iframe.attr("data-src"))
            cleanAndAddUrl(iframe.attr("data-frame"))
        }

        document.select("[data-frame], [data-src], [data-embed], [data-video], [data-player]").forEach { element ->
            listOf("data-frame", "data-src", "data-embed", "data-video", "data-player").forEach { attr ->
                cleanAndAddUrl(element.attr(attr))
            }
        }

        // 3. Tau Video Hash Taraması
        Regex("""data-id=["']([a-zA-Z0-9_-]{20,32})["']""", RegexOption.IGNORE_CASE).findAll(rawHtml).forEach { match ->
            cleanAndAddUrl("https://tau-video.xyz/embed/${match.groupValues[1]}")
        }

        // 4. Base64 Kodlanmış Video Linkleri
        Regex("""["']([a-zA-Z0-9+/=]{30,})["']""").findAll(rawHtml).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                if (decoded.contains("http://") || decoded.contains("https://") || decoded.contains("tau-video")) {
                    cleanAndAddUrl(decoded)
                }
            } catch (_: Exception) {}
        }

        Log.d("CZGM", "Found iframe URLs: $iframeUrls")

        // 5. Bulunan Video Bağlantılarını Extractor'a Gönder
        iframeUrls.forEach { iframe ->
            Log.d("CZGM", "Loading extractor for iframe » $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
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
