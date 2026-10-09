package com.keyiflerolsun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziBox : MainAPI() {
    override var mainUrl        = "https://www.dizibox.live"
    override var name           = "DiziBox"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ------------------------------------------------------------------
    // Cloudflare
    // NOT: User-Agent / Sec-Fetch gibi başlıkları ELLE vermiyoruz.
    // Cloudflare çerezi WebView'ın User-Agent'ına bağlıdır; farklı bir
    // User-Agent gönderirsek çerez geçersiz olur ve site 403 verir.
    // ------------------------------------------------------------------
    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val challenge = response.code in listOf(403, 503) &&
            runCatching { response.peekBody(1024 * 1024).string() }
                .getOrDefault("")
                .let { it.contains("Just a moment") || it.contains("challenge-platform") }

        if (challenge) {
            response.close()
            cloudflareKiller.intercept(chain)
        } else {
            response
        }
    }

    // Sitenin oynatıcı sayfaları için istediği çerezler
    private val siteCookies = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
        "dbxu"          to "1744054959089"
    )

    private suspend fun getDoc(url: String, referer: String? = null): Document =
        app.get(
            url,
            referer = referer ?: "$mainUrl/",
            cookies = siteCookies,
            interceptor = interceptor
        ).document

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun String.cleanTitle() =
        this.replace(Regex("""\s+izle\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun Element.imgUrl(): String? {
        val img = selectFirst("img") ?: return null
        return listOf("data-src", "data-lazy-src", "src")
            .map { img.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrlNull(it) }
    }

    // Sitenin HER sayfasında ~4800 dizilik alfabetik liste var (başlık -> URL).
    // Hem arama hem de "son bölümler" kartlarını diziye çevirmek için kullanıyoruz.
    private var seriesCache: List<Pair<String, String>>? = null

    private fun readSeriesIndex(doc: Document): List<Pair<String, String>> =
        doc.select("ul.alphabetical-category-list a[href*=/diziler/]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { a.attr("title") }.cleanTitle()
            if (title.isBlank()) null else title to href
        }

    private fun seriesUrlFromEpisode(href: String): String? =
        Regex("""/([^/]+?)-\d+-sezon-\d+-bolum""").find(href)
            ?.groupValues?.getOrNull(1)
            ?.let { "$mainUrl/diziler/$it/" }

    // ------------------------------------------------------------------
    // Ana sayfa
    // ------------------------------------------------------------------
    private fun Element.recommendedItem(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = (selectFirst("span.baslik")?.text() ?: a.attr("title")).cleanTitle()
        if (title.isBlank()) return null
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.posterItem(): SearchResponse? {
        val a = selectFirst("a.poster-title[href]") ?: selectFirst("a[href*=/diziler/]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = a.attr("title").ifBlank { a.text() }.cleanTitle()
        if (title.isBlank()) return null
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.smallGridItem(): SearchResponse? {
        val a = selectFirst("a.series-details[href]") ?: selectFirst("a[href*=/diziler/]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = (selectFirst("div.tv-title")?.text() ?: a.text()).cleanTitle()
        if (title.isBlank()) return null
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.episodeCardItem(byName: Map<String, Pair<String, String>>): SearchResponse? {
        val a = selectFirst("a.episode-card-title[href]") ?: return null
        val epHref = fixUrlNull(a.attr("href")) ?: return null
        val rawName = selectFirst("b.series-name")?.text()?.trim().orEmpty()
        val season = selectFirst("span.season")?.text()?.trim().orEmpty()
        val episode = selectFirst("b.episode")?.text()?.trim().orEmpty()

        val hit = byName[norm(rawName)]
        val seriesUrl = hit?.second ?: seriesUrlFromEpisode(epHref) ?: return null
        val shownName = hit?.first ?: rawName
        val title = "$shownName $season $episode".replace(Regex("""\s+"""), " ").trim()
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Bilerek try/catch YOK: hata olursa uygulamada hata mesajı olarak görünsün.
        val document = getDoc(mainUrl)

        val index = readSeriesIndex(document)
        if (index.isNotEmpty()) seriesCache = index
        val byName = index.associateBy { norm(it.first) }

        val lists = mutableListOf<HomePageList>()

        document.select("section.recommended li").mapNotNull { it.recommendedItem() }
            .distinctBy { it.url }
            .takeIf { it.isNotEmpty() }
            ?.let { lists.add(HomePageList("Beklenen / Eklenen Diziler", it)) }

        document.select("section#new-series article.article-series-poster").mapNotNull { it.posterItem() }
            .distinctBy { it.url }
            .takeIf { it.isNotEmpty() }
            ?.let { lists.add(HomePageList("Dikkat Çeken Yeni Diziler", it)) }

        for (section in document.select("section.m-b-1")) {
            val listName = section.selectFirst("h2")?.text()?.trim().orEmpty().ifBlank { "Son Bölümler" }
            val items = section.select("article.article-episode-card")
                .mapNotNull { it.episodeCardItem(byName) }
                .distinctBy { it.name }
            if (items.isNotEmpty()) lists.add(HomePageList(listName, items))
        }

        document.select("section#best-series article.article-series-small-grid")
            .mapNotNull { it.smallGridItem() }
            .distinctBy { it.url }
            .takeIf { it.isNotEmpty() }
            ?.let { lists.add(HomePageList("Efsane Diziler", it)) }

        if (lists.isEmpty()) {
            throw ErrorLoadingException("DiziBox ana sayfası okunamadı (site yapısı değişmiş olabilir).")
        }

        return newHomePageResponse(lists, false)
    }

    // ------------------------------------------------------------------
    // Arama (sitedeki alfabetik listeden süzüyoruz, ayrı bir arama sayfasına gerek yok)
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()

        val index = seriesCache ?: readSeriesIndex(getDoc(mainUrl)).also { seriesCache = it }

        return index
            .filter { norm(it.first).contains(q) }
            .sortedBy { norm(it.first).indexOf(q) }
            .take(60)
            .map { (title, url) -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) }
    }

    // ------------------------------------------------------------------
    // Dizi detayı
    // ------------------------------------------------------------------
    private val epRegex = Regex("""-(\d+)-sezon-(\d+)-bolum""")

    private fun parseEpisodes(doc: Document): List<Episode> {
        // Sayfadaki gürültüyü (4800'lük liste, yan paneller) at
        doc.select(
            "ul.alphabetical-category-list, #alphabetical-category, #best-series, " +
                "#translation-status, #recommended-series, section.recommended"
        ).remove()

        val found = doc.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val m = epRegex.find(href) ?: return@mapNotNull null
            if (!href.contains("izle")) return@mapNotNull null
            val prefix = href.substringBefore(m.value).substringAfterLast("/")
            Triple(prefix, href, m)
        }

        // Yan panellerde başka dizilerin bölümleri de olabilir:
        // en çok bölümü olan "ön ek" grubu bu dizinin kendisidir.
        val bestGroup = found.groupBy { it.first }.maxByOrNull { it.value.size }?.value ?: return emptyList()

        return bestGroup.map { (_, href, m) ->
            val s = m.groupValues[1].toIntOrNull()
            val e = m.groupValues[2].toIntOrNull()
            newEpisode(href) {
                this.name = if (s != null && e != null) "$s. Sezon $e. Bölüm" else null
                this.season = s
                this.episode = e
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    override suspend fun load(url: String): LoadResponse? {
        // Bir bölüm adresi gelirse diziye çevir
        val seriesUrl =
            if (!url.contains("/diziler/") && epRegex.containsMatchIn(url))
                seriesUrlFromEpisode(url) ?: url
            else url

        val doc = getDoc(seriesUrl)

        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text())
            ?.substringBefore(" | ")?.substringBefore(" - DiziBox")?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("div.tv-story p, div.series-summary, div.plot")?.text()
        val tags = doc.select("a[href*=/tur/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Sezon sayfaları ayrıysa onları da gez
        val seasonLinks = doc.select("#seasons-list a[href], .seasons-list a[href], .season-list a[href]")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .distinct()
            .filter { it != seriesUrl }
            .take(40)

        val episodes = mutableListOf<Episode>()
        episodes.addAll(parseEpisodes(doc))
        for (link in seasonLinks) {
            runCatching { parseEpisodes(getDoc(link)) }.getOrNull()?.let { episodes.addAll(it) }
        }

        val finalEpisodes = episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ------------------------------------------------------------------
    // Video bağlantıları
    // Bölüm sayfası -> iframe (oynatıcı sayfası) -> (iç içe iframe) -> m3u8 / extractor
    // ------------------------------------------------------------------
    private fun decodeBase64(s: String): String? =
        runCatching { String(Base64.decode(s, Base64.DEFAULT)) }.getOrNull()

    private suspend fun emitM3u8(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    // Bir metnin (sayfa / çözülmüş base64) içinde video ara
    private suspend fun scan(
        text0: String,
        pageUrl: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 4) return false
        val text = text0.replace("\\/", "/")
        var found = false

        val m3u8s = Regex("""https?://[^"'\s\\<>]+?\.m3u8[^"'\s\\<>]*""")
            .findAll(text).map { it.value }.distinct().toList()
        for (m in m3u8s) {
            emitM3u8(m, pageUrl, callback)
            found = true
        }
        if (found) return true

        val blobs = Regex("""atob\(\s*["']([A-Za-z0-9+/=]{20,})["']""")
            .findAll(text).map { it.groupValues[1] }.distinct().toList()
        for (b in blobs) {
            val decoded = decodeBase64(b) ?: continue
            if (scan(decoded, pageUrl, depth + 1, subtitleCallback, callback)) found = true
        }
        if (found) return true

        val frames = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1] }.distinct().toList()
        for (src in frames) {
            if (openPlayer(src, pageUrl, depth + 1, subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun openPlayer(
        raw: String,
        referer: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 5) return false

        val url = fixUrl(
            raw.replace("&amp;", "&").replace("king.php?v=", "king.php?wmode=opaque&v=")
        )
        if (!url.startsWith("http")) return false
        if (listOf("youtube.com", "googletagmanager", "doubleclick", "facebook.com").any { url.contains(it) }) {
            return false
        }

        if (url.contains(".m3u8")) {
            emitM3u8(url, referer, callback)
            return true
        }

        // Bilinen bir video sitesiyse hazır extractor'lar halleder
        if (loadExtractor(url, referer, subtitleCallback, callback)) return true

        // Değilse oynatıcı sayfasını açıp içinde ara
        val res = runCatching {
            app.get(url, referer = referer, cookies = siteCookies, interceptor = interceptor)
        }.getOrNull() ?: return false

        return scan(res.text, url, depth, subtitleCallback, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)
        var found = false

        val pages = mutableListOf(data to doc)

        // Alternatif kaynaklar (varsa)
        val alternatives = doc.select("div.video-toolbar option[value]")
            .mapNotNull { fixUrlNull(it.attr("value")) }
            .filter { it.startsWith(mainUrl) && it != data }
            .distinct()
            .take(5)
        for (alt in alternatives) {
            runCatching { getDoc(alt) }.getOrNull()?.let { pages.add(alt to it) }
        }

        for ((pageUrl, d) in pages) {
            val iframes = d.select("div#video-area iframe[src], #video-area iframe[src]")
                .ifEmpty { d.select("iframe[src]") }
                .map { it.attr("src") }

            for (src in iframes) {
                if (openPlayer(src, pageUrl, 0, subtitleCallback, callback)) found = true
            }

            if (!found) {
                if (scan(d.html(), pageUrl, 0, subtitleCallback, callback)) found = true
            }
        }

        return found
    }
}
