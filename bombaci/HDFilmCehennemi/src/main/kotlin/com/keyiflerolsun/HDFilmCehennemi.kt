package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Anasayfa",
        "${mainUrl}/category/film-izle-2/"                to "Filmler",
        "${mainUrl}/yabancidiziizle-5/"                   to "Diziler",
        "${mainUrl}/category/tavsiye-filmler-izle2/"      to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmler/"           to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar-1/"              to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle/"      to "En Çok Beğenilenler",
        "${mainUrl}/yil/2025-filmleri-izle-3/"            to "2025 Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-3/"      to "Aksiyon",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-4/"  to "Animasyon",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-2/" to "Bilim Kurgu",
        "${mainUrl}/tur/komedi-filmlerini-izleyin-1/"     to "Komedi",
        "${mainUrl}/tur/korku-filmlerini-izle-2/"        to "Korku",
        "${mainUrl}/tur/romantik-filmleri-izle-1/"        to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        // Sitedeki poster kartlarını (Slider ve Izgara liste) yakalar
        val home = document.select("a.poster").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("strong.poster-title")?.text() 
            ?: this.attr("title").takeIf { it.isNotBlank() } 
            ?: return null
            
        val href      = fixUrlNull(this.attr("href")) ?: return null
        
        // Base64 harici gerçek webp/jpg resim URL'sini alır
        val imgElement = this.selectFirst("img")
        var posterUrl  = imgElement?.attr("src")?.takeIf { !it.startsWith("data:") }
            ?: imgElement?.attr("data-src")?.takeIf { !it.startsWith("data:") }
        posterUrl = fixUrlNull(posterUrl)

        val isTv = href.contains("/dizi/") || this.selectFirst("span.poster-lang")?.text()?.contains("Sezon", ignoreCase = true) == true
        val tvType = if (isTv) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title     = document.selectFirst("h4.title, strong.poster-title, a")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val img       = document.selectFirst("img")
            val posterUrl = fixUrlNull(img?.attr("src")?.takeIf { !it.startsWith("data:") } ?: img?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.section-title, h1.title, h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val imgElem     = document.select("aside.post-info-poster img, div.poster-wrapper img").lastOrNull()
        val poster      = fixUrlNull(imgElem?.attr("src")?.takeIf { !it.startsWith("data:") } ?: imgElem?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a, div.genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a, span.year")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons, div.seasons-tab-content").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p, div.description")?.text()?.trim()
        val rating      = document.selectFirst("div.post-info-imdb-rating span, span.imdb")?.text()?.substringBefore("(")?.trim()?.toRatingInt()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")?.text() ?: it.text(), fixUrlNull(it.select("img").attr("data-src")))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide, div.recommendations a.poster").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: it.selectFirst("strong")?.text() ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href") ?: it.attr("href")) ?: return@mapNotNull null
            val recImg       = it.selectFirst("img")
            val recPosterUrl = fixUrlNull(recImg?.attr("src")?.takeIf { s -> !s.startsWith("data:") } ?: recImg?.attr("data-src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button, button[data-modal*='trailer']")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
            val episodes = document.select("div.seasons-tab-content a, div.episodes-list a").mapNotNull {
                val epName    = it.selectFirst("h4, span")?.text()?.trim() ?: it.text()
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button, button[data-modal*='trailer']")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, webViewDene: Boolean = false): Boolean {
        val document = app.get(url, referer = "${mainUrl}/").document
        val script   = document.select("script").find { it.data().contains("sources:") }?.data()

        if (script == null) {
            Log.d("HDCH", "HATA: 'sources:' iceren script yok » $url » sayfa basi: ${document.html().take(300)}")
            return false
        }

        val unpacked = try { getAndUnpack(script) } catch (e: Exception) { script }
        val temiz    = unpacked.replace("\\/", "/")

        var videoUrl: String? = null

        // 1) Eski yontem: file_link="<base64>"
        if (unpacked.contains("file_link=\"")) {
            val b64 = unpacked.substringAfter("file_link=\"").substringBefore("\";")
            videoUrl = try { base64Decode(b64) } catch (e: Exception) { null }
        }

        // 2) Dogrudan m3u8 / mp4 adresi ara (reklam videolarini ele)
        if (videoUrl.isNullOrBlank()) {
            val bulunanlar = Regex("""https?://[^"'\s<>\\]+?\.(?:m3u8|mp4)[^"'\s<>\\]*""")
                .findAll(temiz).map { it.value }.filter { !it.contains("rekla") }.toList()
            videoUrl = bulunanlar.firstOrNull { it.contains(".m3u8") } ?: bulunanlar.firstOrNull()
        }

        // 3) file: "http..." kalibi
        if (videoUrl.isNullOrBlank()) {
            videoUrl = Regex("""file\s*:\s*"(https?://[^"]+)"""").find(temiz)?.groupValues?.get(1)
        }

        // 4) Video adresi sayfadaki sifreli JS ile uretiliyor: sayfayi WebView'da calistirip m3u8 istegini yakala
        if (videoUrl.isNullOrBlank() && webViewDene) {
            val yakala = Regex("""\.m3u8|\.txt|/hls/|/master""")
            videoUrl = try {
                Log.d("HDCH", "WEBVIEW deneniyor » $url")
                val cevap = app.get(
                    url,
                    referer     = "${mainUrl}/",
                    interceptor = WebViewResolver(yakala)
                )
                cevap.url.takeIf { yakala.containsMatchIn(it) }
            } catch (e: Exception) {
                Log.d("HDCH", "HATA: WebView » ${e.message}")
                null
            }
            Log.d("HDCH", "WEBVIEW sonuc » $videoUrl")
        }

        val finalUrl = videoUrl
        if (finalUrl.isNullOrBlank()) {
            Log.d("HDCH", "TESHIS » $url » script=${script.length} unpacked=${unpacked.length} file_link=${unpacked.contains("file_link")} m3u8=${unpacked.contains("m3u8")} mp4=${unpacked.contains(".mp4")} atob=${unpacked.contains("atob")} dc_=${unpacked.contains("dc_")}")
            val idx = unpacked.indexOf("sources:").coerceAtLeast(0)
            val bas = (idx - 200).coerceAtLeast(0)
            unpacked.substring(bas).take(2400).chunked(800).forEachIndexed { i, parca ->
                Log.d("HDCH", "KOD[$i] » $parca")
            }
            return false
        }

        Log.d("HDCH", "VIDEO BULUNDU » $source » $finalUrl")

        val hls = finalUrl.contains(".m3u8") || script.contains("\"hls\"")

        callback.invoke(
            newExtractorLink(
                source = source,
                name   = source,
                url    = finalUrl,
                type   = if (hls) ExtractorLinkType.M3U8 else null
            ) {
                this.referer = "${mainUrl}/"
                this.quality = Qualities.Unknown.value
            }
        )

        val subData = script.substringAfter("tracks: [", "").substringBefore("]")
        if (subData.isNotBlank()) {
            AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" }?.map {
                subtitleCallback.invoke(
                    SubtitleFile(it.label.toString(), fixUrl(it.file.toString()))
                )
            }
        }

        return true
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("HDCH", "data » $data")
        val document = app.get(data).document

        val groups = document.select("div.alternative-links")
        Log.d("HDCH", "alternative-links sayisi » ${groups.size}")

        groups.map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/",
                    headers = mapOf(
                        "Content-Type"     to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = data
                ).text

                Log.d("HDCH", "API » $source » $videoID » ${apiGet.take(300)}")

                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
                if (iframe == null) {
                    Log.d("HDCH", "HATA: API cevabinda iframe yok » $source » $videoID")
                    return@forEach
                }
                iframe = fixUrl(iframe)

                // Site artik oynaticiyi /rplayer/<id>/ adresinden aciyor; sirayla dene
                val adaylar = mutableListOf<String>()
                if (iframe.contains("?rapidrame_id=")) {
                    val rid = iframe.substringAfter("?rapidrame_id=").substringBefore("&").trim('/')
                    adaylar.add("${mainUrl}/rplayer/$rid/")
                    adaylar.add(iframe)
                    adaylar.add("${mainUrl}/playerr/$rid")
                } else {
                    adaylar.add(iframe)
                }

                for (aday in adaylar) {
                    Log.d("HDCH", "$source » $videoID » deneniyor » $aday")
                    val basarili = try {
                        invokeLocalSource(source, aday, subtitleCallback, callback, webViewDene = (aday == adaylar.first()))
                    } catch (e: Exception) {
                        Log.d("HDCH", "HATA: $aday » ${e.message}")
                        false
                    }
                    if (basarili) break
                }
            }
        }

        return true
    }

    private data class SubSource(
        @JsonProperty("file")  val file: String?  = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind")  val kind: String?  = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
}
