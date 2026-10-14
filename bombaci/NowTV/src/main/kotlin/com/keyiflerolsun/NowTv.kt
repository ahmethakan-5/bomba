package com.keyiflerolsun

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
    override val supportedTypes = setOf(TvType.TvSeries)

    // data alanı sadece bölümü ayırt etmek için kullanılıyor, istek her zaman ana sayfaya yapılır
    override val mainPage = mainPageOf(
        "yeni" to "Yeni Bölümler",
        "diziler" to "Diziler",
        "programlar" to "Programlar"
    )

    private val trLocale = Locale("tr", "TR")

    // ---------------------------------------------------------------- Ana sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val document = app.get(mainUrl).document
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
        val document = app.get(mainUrl).document
        val q = query.lowercase(trLocale).trim()
        return document.select("div.poster")
            .mapNotNull { it.toSeriesCard() }
            .distinctBy { it.url }
            .filter { it.name.lowercase(trLocale).contains(q) }
    }

    // ---------------------------------------------------------------- Detay
    override suspend fun load(url: String): LoadResponse {
        val seriesUrl = fixUrl(url).substringBefore("/bolum/").let {
            if (it.endsWith("/izle")) it else it.trimEnd('/') + "/izle"
        }
        val document = app.get(seriesUrl).document

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
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, referer = mainUrl).text
        val found = HashSet<String>()

        // 1) Sayfa içinde doğrudan geçen m3u8 adresleri
        Regex("""https?:\\?/\\?/[^"'\s<>\\]+?\.m3u8[^"'\s<>\\]*""").findAll(html).forEach {
            found.add(it.value.replace("\\/", "/"))
        }
        found.forEach { link ->
            M3u8Helper.generateM3u8(name, link, mainUrl).forEach(callback)
        }
        if (found.isNotEmpty()) return true

        // 2) Gömülü iframe / extractor
        var ok = false
        Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html).forEach {
            if (loadExtractor(fixUrl(it.groupValues[1]), mainUrl, subtitleCallback, callback)) ok = true
        }
        return ok
    }
}
