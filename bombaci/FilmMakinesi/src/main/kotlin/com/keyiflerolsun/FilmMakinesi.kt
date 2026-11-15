// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! Cloudflare Bypass Ayarları
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    // Kategori adresleri sitenin güncel HTML'indeki bağlantılardan alındı.
    // Site slug'ları (-fmXXXX) değiştiği için eskiler boş döner.
    // Sayfalama: ilk sayfa taban adres, sonrakiler  <taban>/sayfa/<n>/
    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-fmuyqb"                                  to "Son Filmler",
        "${mainUrl}/film-izle/olmeden-izlenmesi-gerekenler-fm1"      to "Ölmeden İzle",
        "${mainUrl}/tur/aksiyon-fmkwm8/film"                         to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu-fmsq8z/film"                     to "Bilim Kurgu",
        "${mainUrl}/tur/macera-fm1/film"                             to "Macera",
        "${mainUrl}/tur/komedi-fma6cz/film"                          to "Komedi",
        "${mainUrl}/tur/romantik-fm1/film"                           to "Romantik",
        "${mainUrl}/tur/belgesel/film"                               to "Belgesel",
        "${mainUrl}/tur/fantastik-fmxwpc/film"                       to "Fantastik",
        "${mainUrl}/tur/polisiye-fmzhhn/film"                        to "Polisiye",
        "${mainUrl}/tur/korku-fmr7du/film"                           to "Korku"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = if (page == 1) request.data else "${request.data}/sayfa/$page/"
        val document = app.get(url).document
        val home     = document.select("a.item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Kart bağlantısının kendisi <a class="item" href="/film/..." data-title="..."> şeklinde.
        val href = fixUrlNull(attr("href").ifBlank { selectFirst("a")?.attr("href") }) ?: return null

        val title = attr("data-title").takeIf { it.isNotBlank() }
            ?: selectFirst(".item-title, h6 a, h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val img       = selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.takeIf { it.isNotBlank() })
            ?: fixUrlNull(img?.attr("src")?.takeIf { it.isNotBlank() })

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val link  = selectFirst("a") ?: return null
        val href  = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("data-title").ifBlank { link.text() }.trim().ifBlank { return null }
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama formu: <form action="/arama/"> ve girdi adı "s"
        val encoded  = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/arama/?s=$encoded").document

        return document.select("a.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div#film_izle h1, h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.select("section#film_single article p, div.entry-content p").lastOrNull()?.text()?.trim()
        val tags        = document.selectFirst("dt:contains(Tür:) + dd")?.text()?.split(",")?.map { it.trim() }
        val rating      = document.selectFirst("dt:contains(IMDB Puanı:) + dd")?.text()?.trim()?.toRatingInt()
        val year        = document.selectFirst("dt:contains(Yapım Yılı:) + dd")?.text()?.trim()?.toIntOrNull()

        val recommendations = document.select("div.film-list a.item").mapNotNull { it.toRecommendResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.rating          = rating
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FLMM", "data » $data")
        val document = app.get(data, referer = "${mainUrl}/").document

        val iframeElement = document.selectFirst("div.player-div iframe, div#player iframe, iframe[data-src], iframe[src]")
        val rawIframe = iframeElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: iframeElement?.attr("src")?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w("FLMM", "iframe bulunamadı: $data")
                return false
            }

        val iframe = fixUrl(rawIframe)
        Log.d("FLMM", "iframe » $iframe")

        val loaded = loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        if (!loaded) {
            CloseLoad().getUrl(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
