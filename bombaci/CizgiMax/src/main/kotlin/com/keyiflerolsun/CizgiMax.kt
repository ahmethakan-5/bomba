// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

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

    // Yeni temanın menü yapısına göre güncellenen URL dizilimleri
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
        // Yeni temadaki sayfalama yapısı: /page/2/
        val url = if (page == 1) {
            "${mainUrl}${request.data}"
        } else {
            "${mainUrl}${request.data}page/${page}/"
        }

        val document = app.get(url).document
        // Yeni tema seçicisi (div.film-item) ve eski tema fallback'i
        val home     = document.select("div.film-list div.film-item, ul.filter-results li").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a.film-name")?.text()?.trim()
                        ?: this.selectFirst("h2.truncate")?.text()?.trim() ?: return null
        
        val href      = fixUrlNull(this.selectFirst("a.film-name")?.attr("href")
                        ?: this.selectFirst("div.poster-subject a")?.attr("href")) ?: return null
        
        var posterUrl = this.selectFirst("a.poster img")?.attr("src")
                        ?: this.selectFirst("div.poster-media img")?.attr("data-src")

        // Resim etiketine direkt erişim yedeği
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("img")?.attr("src")
        }

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) { 
            this.posterUrl = fixUrlNull(posterUrl) 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Yeni temada arama için doğrudan HTML endpoint'i kullanılıyor
        val document = app.get("${mainUrl}/ara/?q=${query}").document
        val results = document.select("div.film-list div.film-item, ul.filter-results li").mapNotNull { it.toSearchResult() }

        if (results.isNotEmpty()) {
            return results
        }

        // HTML'den sonuç alınamazsa eski Ajax sistemine Fallback atar
        val response = app.get("${mainUrl}/ajaxservice/index.php?qr=${query}").parsedSafe<SearchResult>()?.data?.result ?: return listOf()

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

        // Genel seçiciler eklenerek hata payı düşürüldü
        val title       = document.selectFirst("h1.page-title, h1")?.text() ?: return null
        val poster      = fixUrlNull(document.selectFirst("img.series-profile-thumb, .poster img, .film-poster img")?.attr("src"))
        val description = document.selectFirst("p#tv-series-desc, .description, .summary, .plot")?.text()?.trim()
        val tags        = document.select("div.genre-item a, .genres a, .tags a").mapNotNull { it.text().trim() }
        val rating      = document.selectFirst("div.color-imdb, .rating, .imdb-rating")?.text()?.trim()?.toRatingInt()

        val epElements = document.select("div.asisotope div.ajax_post")
        val episodes = if (epElements.isNotEmpty()) {
            epElements.mapNotNull {
                val epName     = it.selectFirst("span.episode-names")?.text()?.trim() ?: return@mapNotNull null
                val epHref     = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epEpisode  = Regex("""(\d+)\.Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val seasonName = it.selectFirst("span.season-name")?.text()?.trim() ?: ""
                val epSeason   = Regex("""(\d+)\.Sezon""").find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }
        } else {
            // Yeni Tema: Direkt bölüm linklerindeki yapıdan (örn: 6-sezon-26-bolum-izle) Sezon/Bölüm Regex ile yakalanıyor
            document.select("a[href*=-bolum-izle]").mapNotNull {
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epName = it.text().trim().ifEmpty { it.attr("title").trim() }

                val epEpisode = Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                
                val epSeason = Regex("""(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                if (epEpisode == null) return@mapNotNull null

                newEpisode(epHref) {
                    this.name = epName.ifEmpty { "$epSeason. Sezon $epEpisode. Bölüm" }
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }.distinctBy { it.data } // Aynı bölüme giden mükerrer butonları filtreler
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("CZGM", "data » $data")
        val document = app.get(data).document

        // Iframe ayıklama HTML'deki yapı ("ul.linkler li a[data-frame]") değişmediği için korunmuştur.
        document.select("ul.linkler li").forEach {
            val iframe = fixUrlNull(it.selectFirst("a")?.attr("data-frame")) ?: return@forEach
            Log.d("CZGM", "iframe » $iframe")

            // Extractor'a "https://cizgimax.online/" referer olarak gider ve CizgiDuo.kt içindeki app.get(..., referer) methoduyla buluşur.
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
