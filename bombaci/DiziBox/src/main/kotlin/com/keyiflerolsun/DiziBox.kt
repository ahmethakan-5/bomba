package com.keyiflerolsun

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenen Bölümler",
        "$mainUrl/tum-bolumler/" to "Tüm Bölümler"
    )

    private val seasonEpisodeRegex = Regex("""(\d+)\s*\.\s*Sezon\s*(\d+)\s*\.\s*B""", RegexOption.IGNORE_CASE)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("article, .col-box, .post-box, div.item, a[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    /**
     * Turns a listing element (or a bare <a>) into a result.
     * Accepts both series links (/diziler/...) and episode links (...-bolum-izle/).
     * Episode links are resolved to their series inside load().
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        val isSeries  = href.contains("/diziler/")
        val isEpisode = href.contains("-bolum")
        if (!isSeries && !isEpisode) return null
        if (href == mainUrl || href.contains("/kategori/") || href.contains("/tag/") ||
            href.contains("/page/") || href.contains("/tum-bolumler")) return null

        val rawTitle = selectFirst(".entry-title, h2, h3, h4, .title, strong")?.text()
            ?: anchor.attr("title")
        val title = rawTitle.trim().removeSuffix(" izle").trim()
        if (title.isBlank()) return null

        val img = selectFirst("img")
        val poster = listOf("data-src", "data-lazy-src", "src")
            .firstNotNullOfOrNull { attr -> img?.attr(attr)?.takeIf { it.isNotBlank() && !it.startsWith("data:") } }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded  = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document

        return document.select("article, .col-box, .post-box, div.item, a[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageDoc = app.get(url).document

        // Episode pages carry a link to their series page (/diziler/<slug>/).
        // Series pages are used directly.
        val isEpisodePage = url.contains("-bolum")
        val seriesUrl = if (isEpisodePage) {
            fixUrlNull(pageDoc.selectFirst("a[href*=/diziler/]")?.attr("href")) ?: url
        } else url

        val seriesDoc = if (seriesUrl == url) pageDoc else app.get(seriesUrl).document

        val title = seriesDoc.selectFirst("h1")?.text()?.trim()
            ?.removeSuffix(" izle")?.replace(seasonEpisodeRegex, "")?.trim()
            ?.ifBlank { null }
            ?: return null

        val poster = fixUrlNull(seriesDoc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot   = seriesDoc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes = extractEpisodes(seriesDoc, title).ifEmpty { extractEpisodes(pageDoc, title) }

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    /**
     * Collects episode links. Only keeps links whose label starts with the series title,
     * so unrelated "related shows" links in the sidebar are ignored.
     */
    private fun extractEpisodes(doc: Document, seriesTitle: String): List<Episode> {
        return doc.select("a[href*=-bolum]")
            .mapNotNull { a ->
                val href  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val label = a.attr("title").ifBlank { a.text() }.trim().removeSuffix(" izle").trim()
                if (label.isBlank() || !label.startsWith(seriesTitle, ignoreCase = true)) return@mapNotNull null

                val m = seasonEpisodeRegex.find(label)
                newEpisode(href) {
                    this.name    = label
                    this.season  = m?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = m?.groupValues?.get(2)?.toIntOrNull()
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document

        val sources = document.select("iframe").mapNotNull { iframe ->
            iframe.absUrl("src").ifBlank { iframe.absUrl("data-src") }.takeIf { it.isNotBlank() }
        }

        for (src in sources) {
            if (src.contains("/player/")) {
                // Same-site player (e.g. /player/king/king.php). No external extractor handles it,
                // so read the player page directly.
                resolveSitePlayer(src, data, callback)
            } else {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return sources.isNotEmpty()
    }

    private suspend fun resolveSitePlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(playerUrl, referer = referer).text
            .replace("\\/", "/") // unescape JSON-style slashes

        val m3u8 = Regex("""https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*""").find(html)?.value
        val mp4  = Regex("""https?://[^"'\s<>]+?\.mp4[^"'\s<>]*""").find(html)?.value

        when {
            m3u8 != null -> callback(
                newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            mp4 != null -> callback(
                newExtractorLink(name, name, mp4, ExtractorLinkType.VIDEO) {
                    this.referer = playerUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            else -> {
                // The player did not expose a plain stream URL. It may be encrypted or
                // assembled in JS. Dump the response (Logcat tag below) so the format can be checked.
                android.util.Log.w("DiziBox", "No plain stream found in player page: $playerUrl (len=${html.length})")
            }
        }
    }
}
