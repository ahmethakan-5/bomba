// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl              = "https://www.fullhdfilmizlesene.now"
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/en-cok-izlenen-filmler-izle-hd/"            to "En Çok izlenen Filmler",
        "${mainUrl}/filmizle/imdb-puani-yuksek-filmler-izle-1/" to "IMDB Puanı Yüksek Filmler",
        "${mainUrl}/filmizle/aile-filmleri-izle-2/"             to "Aile Filmleri",
        "${mainUrl}/filmizle/aksiyon-filmler-izle-1/"           to "Aksiyon Filmleri",
        "${mainUrl}/filmizle/animasyon-filmleri-izle-4/"        to "Animasyon Filmleri",
        "${mainUrl}/filmizle/belgesel-filmleri-izle-2/"         to "Belgeseller",
        "${mainUrl}/filmizle/bilim-kurgu-filmleri-izle-1/"      to "Bilim Kurgu Filmleri",
        "${mainUrl}/filmizle/bluray-filmler-izle-1/"            to "Blu Ray Filmler",
        "${mainUrl}/filmizle/cizgi-filmler-izle-1/"             to "Çizgi Filmler",
        "${mainUrl}/filmizle/dram-filmleri-izle/"               to "Dram Filmleri",
        "${mainUrl}/filmizle/fantastik-filmleri-izle-2/"        to "Fantastik Filmler",
        "${mainUrl}/filmizle/gerilim-filmleri-izle-3/"          to "Gerilim Filmleri",
        "${mainUrl}/filmizle/gizem-filmleri-izle/"              to "Gizem Filmleri",
        "${mainUrl}/filmizle/hint-filmler-fh-hd-izle/"          to "Hint Filmleri",
        "${mainUrl}/filmizle/komedi-filmleri-izle-2/"           to "Komedi Filmleri",
        "${mainUrl}/filmizle/korku-filmleri-izle-2/"            to "Korku Filmleri",
        "${mainUrl}/filmizle/macera-filmleri-izle-1/"           to "Macera Filmleri",
        "${mainUrl}/filmizle/muzikal-filmleri-izle/"            to "Müzikal Filmler",
        "${mainUrl}/filmizle/polisiye-filmleri-izle-1/"         to "Polisiye Filmleri",
        "${mainUrl}/filmizle/psikolojik-filmleri-izle/"         to "Psikolojik Filmler",
        "${mainUrl}/filmizle/romantik-filmler-izle-1/"          to "Romantik Filmler",
        "${mainUrl}/filmizle/savas-filmleri-izle-2/"            to "Savaş Filmleri",
        "${mainUrl}/filmizle/suc-filmleri-izle-3/"              to "Suç Filmleri",
        "${mainUrl}/filmizle/tarih-filmleri-izle/"              to "Tarih Filmleri",
        "${mainUrl}/filmizle/western-filmleri-izle/"            to "Western Filmler",
        "${mainUrl}/filmizle/yerli-filmler-izle-3/"             to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.film, li.film").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("span.film-title") ?: this.selectFirst("a.tt")
        val title     = titleElement?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null

        val imgEl     = this.selectFirst("img")
        var posterUrl = imgEl?.attr("src")
        if (posterUrl.isNullOrEmpty() || posterUrl.startsWith("data:")) {
            posterUrl = imgEl?.attr("data-src")
        }

        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = fixUrlNull(posterUrl) 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/${query}").document

        return document.select("div.film, li.film").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.izle-titles h1, span.film-title, div.izle-titles")?.text()?.trim() ?: return null
        
        val imgEl = document.selectFirst("div.film img, div.detay img")
        var poster = imgEl?.attr("src")
        if (poster.isNullOrEmpty() || poster.startsWith("data:")) {
            poster = imgEl?.attr("data-src")
        }

        val year        = document.selectFirst("span.film-yil, div.dd a.category")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.ozet-ic, div.detay-sag .ozet-ic")?.text()?.trim()
        val tags        = document.select("a[rel='category tag'], div.turlist a").map { it.text() }
        val rating      = document.selectFirst("span.imdb, div.puanx-puan")?.text()?.trim()?.split(" ")?.last()?.toRatingInt()
        val duration    = document.selectFirst("span.sure")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val trailer     = Regex("""embedUrl": "(.*)"""").find(document.html())?.groupValues?.get(1)
        val actors      = document.select("div.film-info ul li:nth-child(2) a > span").map {
            Actor(it.text())
        }

        val recommendations = document.select("div.owl-carousel div.film, section.benzer-filmler div.film").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = fixUrlNull(poster)
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.rating          = rating
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun atob(s: String): String {
        return String(Base64.decode(s, Base64.DEFAULT))
    }

    private fun rtt(s: String): String {
        fun rot13Char(c: Char): Char {
            return when (c) {
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else -> c
            }
        }

        return s.map { rot13Char(it) }.joinToString("")
    }

    private fun getVideoLinks(document: Document): List<Map<String, String>> {
        val scriptElement = document.select("script").firstOrNull { it.data().isNotEmpty() && it.data().contains("scx =") }
        val scriptContent = scriptElement?.data()?.trim() ?: return emptyList()

        val scxData         = Regex("""scx = (.*?);""").find(scriptContent)?.groupValues?.get(1) ?: return emptyList()
        val scxMap: SCXData = jacksonObjectMapper().readValue(scxData)
        val keys            = listOf("atom", "advid", "advidprox", "proton", "fast", "fastly", "tr", "en")

        val linkList = mutableListOf<Map<String, String>>()

        for (key in keys) {
            val t = when (key) {
                "atom"      -> scxMap.atom?.sx?.t
                "advid"     -> scxMap.advid?.sx?.t
                "advidprox" -> scxMap.advidprox?.sx?.t
                "proton"    -> scxMap.proton?.sx?.t
                "fast"      -> scxMap.fast?.sx?.t
                "fastly"    -> scxMap.fastly?.sx?.t
                "tr"        -> scxMap.tr?.sx?.t
                "en"        -> scxMap.en?.sx?.t
                else        -> null
            }

            when (t) {
                is List<*> -> {
                    val links = t.filterIsInstance<String>().map { link -> atob(rtt(link)) }
                    linkList.add(mapOf(key to links.joinToString(",")))
                }
                is Map<*, *> -> {
                    val links = t.mapValues { (_, value) ->
                        if (value is String) atob(rtt(value)) else ""
                    }
                    val safeLinks = links.mapKeys { (k, _) ->
                        k?.toString() ?: "Unknown"
                    }
                    linkList.add(safeLinks)
                }
            }
        }

        return linkList
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FHD", "data » $data")
        val document   = app.get(data).document
        val videoLinks = getVideoLinks(document)
        Log.d("FHD", "videoLinks » $videoLinks")
        if (videoLinks.isEmpty()) return false

        for (videoMap in videoLinks) {
            for ((key, value) in videoMap) {
                val videoUrl = fixUrlNull(value) ?: continue
                if (videoUrl.contains("turbo.imgz.me")) {
                    loadExtractor("${key}||${videoUrl}", "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SCXData(
        @JsonProperty("atom")      val atom: AtomData?      = null,
        @JsonProperty("advid")     val advid: AtomData?     = null,
        @JsonProperty("advidprox") val advidprox: AtomData? = null,
        @JsonProperty("proton")    val proton: AtomData?    = null,
        @JsonProperty("fast")      val fast: AtomData?      = null,
        @JsonProperty("fastly")    val fastly: AtomData?    = null,
        @JsonProperty("tr")        val tr: AtomData?        = null,
        @JsonProperty("en")        val en: AtomData?        = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AtomData(
        @JsonProperty("sx") var sx: SXData
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SXData(
        @JsonProperty("t") var t: Any
    )
}
