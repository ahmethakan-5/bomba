// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.
// Güncel site DOM yapısına göre düzenlenmiştir.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class DiziMom : MainAPI() {
    override var mainUrl              = "https://www.dizimom.help"
    override var name                 = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // Ana sayfa bağlantıları dizimom.html.txt menü yapısına göre güncellendi
    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/"             to "Son Bölümler",
        "${mainUrl}/yabanci-dizi-izle/page/"        to "Yabancı Diziler",
        "${mainUrl}/yerli-dizi-izle/page/"          to "Yerli Diziler",
        "${mainUrl}/anime-izle/page/"               to "Animeler",
        "${mainUrl}/turkce-dublaj-diziler-hd/page/" to "Dublajlı Diziler",
        "${mainUrl}/netflix-dizileri-izle/page/"    to "Netflix Dizileri",
        "${mainUrl}/kore-dizileri-izle-hd/page/"    to "Kore Dizileri",
        "${mainUrl}/full-hd-hint-dizileri-izle/page/" to "Hint Dizileri",
        "${mainUrl}/pakistan-dizileri-izle/page/"   to "Pakistan Dizileri",
        "${mainUrl}/tv-programlari-izle/page/"      to "TV Programları"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/").document
        val isSonBolumler = request.data.contains("/tum-bolumler/")
        
        val elements = document.select("div.episode-box, div.single-item")
        val home = elements.mapNotNull { it.toSearchResult(isSonBolumler) }

        return newHomePageResponse(request.name, home)
    }

    // Hem "Son Bölümler" hem de normal dizi kutularını işleyebilen birleştirilmiş ayrıştırıcı
    private suspend fun Element.toSearchResult(isSonBolumler: Boolean = false): SearchResponse? {
        val titleElem = this.selectFirst("div.serie-name a, div.categorytitle a, div.episode-name a") ?: return null
        var title     = titleElem.text().substringBefore(" izle").trim()
        var href      = fixUrlNull(titleElem.attr("href")) ?: return null
        
        // Lazy Load desteği: Öncelikle data-src aranır, yoksa src alınır
        val imgElem   = this.selectFirst("div.img img, div.cat-img img, div.poster img, a img")
        val posterUrl = fixUrlNull(imgElem?.attr("data-src")?.ifEmpty { imgElem.attr("src") })

        // "Son Bölümler" ana sayfasından geliyorsa link bölüme gidecektir, dizinin ana linkini bulmak için istek atılır
        if (isSonBolumler && href.contains("-bolum-")) {
            title = title.replace(".Sezon ", "x").replace(".Bölüm", "")
            try {
                val epDoc = app.get(href).document
                val showHref = epDoc.selectFirst("div#benzerli a")?.attr("href")
                if (showHref != null) {
                    href = fixUrl(showHref)
                }
            } catch (e: Exception) {
                Log.d("DZM", "Ana dizi linki alınamadı: $href")
            }
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.episode-box, div.single-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.title h1")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src")) ?: return null
        val year        = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
        val tags        = document.select("div.genres a").mapNotNull { it.text().trim() }
        val rating      = document.selectXpath("//div[span[contains(text(), 'IMDB')]]").text().substringAfter("IMDB : ").trim().toRatingInt()
        val actors      = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").split(", ").map {
            Actor(it.trim())
        }

        val episodes    = document.select("div.bolumust").mapNotNull {
            val epName    = it.selectFirst("div.baslik")?.text()?.trim() ?: return@mapNotNull null
            val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\.Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\.Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epName.substringBefore(" izle").replace(title, "").trim()
                this.season  = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZM", "data » $data")

        val ua = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")

        app.post(
            "${mainUrl}/wp-login.php",
            headers = ua,
            referer = "${mainUrl}/",
            data    = mapOf(
                "log"         to "keyiflerolsun",
                "pwd"         to "12345",
                "rememberme"  to "forever",
                "redirect_to" to mainUrl,
            )
        )

        val document = app.get(data, headers=ua).document

        val iframes     = mutableListOf<String>()
        val mainIframe = document.selectFirst("div.video p iframe")?.attr("src") ?: return false
        iframes.add(mainIframe)

        document.select("div.sources a").forEach {
            val subDocument = app.get(it.attr("href"), headers=ua).document
            val subIframe   = subDocument.selectFirst("div.video p iframe")?.attr("src") ?: return@forEach

            iframes.add(subIframe)
        }

        for (iframe in iframes) {
            Log.d("DZM", "iframe » $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
