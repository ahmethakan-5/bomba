package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

class NowTv : MainAPI() {
    override var mainUrl = "https://www.nowtv.com.tr"
    override var name = "NOW TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    // data alanı sadece bölümü ayırt etmek için kullanılıyor, istek her zaman ana sayfaya yapılır
    override val mainPage = mainPageOf(
        "canli" to "Canlı Yayın",
        "yeni" to "Yeni Bölümler",
        "diziler" to "Diziler",
        "programlar" to "Programlar"
    )

    private val trLocale = Locale("tr", "TR")
    private val tag = "NowTv"

    private val liveUrl get() = "$mainUrl/canli-yayin"
    private val livePoster = "https://nqg6bkpsnhen.merlincdn.net/images/playerDefaultPoster.jpg"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val baseHeaders
        get() = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )

    // ---------------------------------------------------------------- Yardımcılar
    // Varsayılan 10 sn zaman aşımı logcat'te "SocketTimeoutException" veriyordu.
    // Süreyi uzatıp, hata olursa bir kez daha deniyoruz.
    private suspend fun getHtml(url: String, referer: String = "$mainUrl/"): String? {
        repeat(2) { attempt ->
            try {
                return app.get(url, headers = baseHeaders, referer = referer, timeout = 30L).text
            } catch (e: Exception) {
                Log.d(tag, "getHtml hata (deneme ${attempt + 1}) $url -> ${e.message}")
            }
        }
        return null
    }

    private suspend fun getDocument(url: String): Document? =
        getHtml(url)?.let { org.jsoup.Jsoup.parse(it, url) }

    private fun cleanLink(raw: String): String =
        raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")

    // Sayfa kaynağındaki tüm m3u8 adreslerini (sırayı bozmadan) toplar
    private fun extractM3u8(html: String): List<String> {
        val found = LinkedHashSet<String>()
        Regex("""https?:(?:\\?/){2}[^"'\s<>\\)]+?\.m3u8[^"'\s<>\\)]*""")
            .findAll(html)
            .forEach { found.add(cleanLink(it.value)) }
        return found.toList()
    }

    private fun extractMp4(html: String): List<String> {
        val found = LinkedHashSet<String>()
        Regex("""https?:(?:\\?/){2}[^"'\s<>\\)]+?\.mp4[^"'\s<>\\)]*""")
            .findAll(html)
            .forEach { found.add(cleanLink(it.value)) }
        return found.toList()
    }

    // ---------------------------------------------------------------- Ana sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        // Canlı yayın: tek bir kart, istek gerekmez
        if (request.data == "canli") {
            val card = newLiveSearchResponse("NOW Canlı Yayın", liveUrl, TvType.Live) {
                this.posterUrl = livePoster
            }
            return newHomePageResponse(request.name, listOf(card), false)
        }

        val document = getDocument(mainUrl) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = when (request.data) {
            "yeni" -> document.select("section.videos")
                .firstOrNull { it.selectFirst("h2")?.text()?.contains("BÖLÜM", true) == true }
                ?.select("div.thumb")
                ?.mapNotNull { it.toEpisodeCard() }
                .orEmpty()

            else -> document.select("div.menu-content-wrapper[v-show*=${request.data}] div.poster")
                .mapNotNull { it.toSeriesCard() }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, false)
    }

    // Poster kartı: <div class="poster"><a href=".../Dizi-Adi/izle"><img data-src alt="Dizi Adı">
    private fun Element.toSeriesCard(): SearchResponse? {
        val link = selectFirst("a[href$=/izle]") ?: return null
        val img = selectFirst("img")
        val title = img?.attr("alt")?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val href = fixUrl(link.attr("href"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { img.attr("src") })
        }
    }

    // "Yeni Bölümler" kartı: link /Dizi/bolum/4 şeklinde, bu yüzden dizi sayfasına çeviriyoruz
    private fun Element.toEpisodeCard(): SearchResponse? {
        val link = selectFirst("a[href*=/bolum/]") ?: return null
        val img = selectFirst("img")
        val seriesName = img?.attr("alt")?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val seriesUrl = fixUrl(link.attr("href")).substringBefore("/bolum/") + "/izle"
        val epLabel = selectFirst(".program-name strong")?.text()?.trim()

        return newTvSeriesSearchResponse(
            if (epLabel.isNullOrBlank()) seriesName else "$seriesName - $epLabel",
            seriesUrl, TvType.TvSeries
        ) {
            this.posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { img.attr("src") })
        }
    }

    // ---------------------------------------------------------------- Arama
    // Sitedeki arama JS ile (harici bir API'den) çalışıyor; o adres elimizdeki HTML'de yok.
    // Bu yüzden ana sayfadaki tüm dizi/program posterleri içinde arıyoruz.
    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDocument(mainUrl) ?: return emptyList()
        val q = query.lowercase(trLocale).trim()
        return document.select("div.poster")
            .mapNotNull { it.toSeriesCard() }
            .distinctBy { it.url }
            .filter { it.name.lowercase(trLocale).contains(q) }
    }

    // ---------------------------------------------------------------- Detay
    override suspend fun load(url: String): LoadResponse {
        // Canlı yayın
        if (url.contains("canli-yayin")) {
            return newLiveStreamLoadResponse("NOW Canlı Yayın", liveUrl, liveUrl) {
                this.posterUrl = livePoster
                this.plot = "NOW kanalının kesintisiz canlı yayını"
            }
        }

        val seriesUrl = fixUrl(url).substringBefore("/bolum/").let {
            if (it.endsWith("/izle")) it else it.trimEnd('/') + "/izle"
        }
        val document = getDocument(seriesUrl)
            ?: throw ErrorLoadingException("Sayfa açılamadı: $seriesUrl")

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore("|")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            ?: "NOW TV"

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.slide-bg")?.attr("data-src")
        )
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, parseEpisodes(document)) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // Sayfadaki /Dizi/bolum/N bağlantılarından bölüm listesi çıkarır
    private fun parseEpisodes(document: Document): List<Episode> {
        val seen = HashSet<String>()
        return document.select("a[href*=/bolum/]").mapNotNull { a ->
            val href = fixUrl(a.attr("href")).substringBefore("?")
            val number = Regex("""/bolum/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null

            newEpisode(href) {
                this.name = "$number. Bölüm"
                this.episode = number
                this.season = 1
                this.posterUrl = fixUrlNull(
                    a.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                )
            }
        }.sortedBy { it.episode }
    }

    // ---------------------------------------------------------------- Video
    private suspend fun sendM3u8(
        label: String,
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(name, label, link, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to referer,
                    "Origin" to mainUrl
                )
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ---- Canlı yayın: adres (token'lı) canlı yayın sayfasındaki oynatıcı ayarında geçiyor.
        // Token süresi dolduğu için her seferinde sayfadan taze çekilir.
        if (data.contains("canli-yayin")) {
            val html = getHtml(liveUrl) ?: return false
            val links = extractM3u8(html)
            Log.d(tag, "canlı yayın m3u8 sayısı = ${links.size}")
            links.forEachIndexed { i, link ->
                val label = if (i == 0) "$name Canlı" else "$name Canlı (Alt ${i})"
                sendM3u8(label, link, "$mainUrl/", callback)
            }
            return links.isNotEmpty()
        }

        // ---- Bölümler
        // Bölüm sayfasında video adresi yok. Sayfadaki oynatıcı, video_id ile
        // POST /ajax/stream isteği atıp cevaptaki "video_url" alanını kullanıyor.
        val page = try {
            app.get(data, headers = baseHeaders, referer = "$mainUrl/", timeout = 30L)
        } catch (e: Exception) {
            Log.d(tag, "bölüm sayfası açılamadı: ${e.message}")
            return false
        }
        val html = page.text
        var ok = false

        val videoId = Regex("""video_id\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-video-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""video_id=(\d+)""").find(html)?.groupValues?.get(1)
        val csrf = Regex("""<meta name=["']csrf-token["'] content=["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        Log.d(tag, "video_id = $videoId, csrf var mı = ${csrf != null} ($data)")

        if (videoId != null) {
            val reqHeaders = baseHeaders + mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl
            ) + (if (csrf != null) mapOf("X-CSRF-TOKEN" to csrf) else emptyMap())

            val streamText = try {
                app.post(
                    "$mainUrl/ajax/stream",
                    data = mapOf("video_id" to videoId),
                    headers = reqHeaders,
                    referer = data,
                    cookies = page.cookies,
                    timeout = 30L
                ).text
            } catch (e: Exception) {
                Log.d(tag, "ajax/stream hata: ${e.message}")
                null
            }
            Log.d(tag, "ajax/stream cevap: ${streamText?.take(400)}")

            if (streamText != null) {
                val candidates = LinkedHashSet<String>()
                Regex(""""video_url"\s*:\s*"([^"]+)"""").find(streamText)
                    ?.groupValues?.get(1)?.let { candidates.add(cleanLink(it)) }
                candidates.addAll(extractM3u8(streamText))
                candidates.addAll(extractMp4(streamText))

                for (link in candidates) {
                    if (link.contains(".mp4", true) && !link.contains(".m3u8", true)) {
                        callback(
                            newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        val generated = try {
                            M3u8Helper.generateM3u8(name, link, "$mainUrl/")
                        } catch (e: Exception) {
                            emptyList()
                        }
                        if (generated.isNotEmpty()) generated.forEach(callback)
                        else sendM3u8(name, link, "$mainUrl/", callback)
                    }
                    ok = true
                }
            }
        }
        if (ok) return true

        // Yedek 1) Sayfa içinde doğrudan geçen m3u8 adresleri
        for (link in extractM3u8(html)) {
            sendM3u8(name, link, "$mainUrl/", callback)
            ok = true
        }
        if (ok) return true

        // Yedek 2) Gömülü iframe / extractor
        Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html).forEach {
            if (loadExtractor(fixUrl(it.groupValues[1]), "$mainUrl/", subtitleCallback, callback)) ok = true
        }
        return ok
    }
}
