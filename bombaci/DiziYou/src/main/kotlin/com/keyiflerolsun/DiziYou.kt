// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class DiziYou : MainAPI() {
    override var mainUrl              = "https://www.diziyou.one"
    override var name                 = "DiziYou"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Dizi Arşivi (A-Z)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Yeni yapıda tüm A-Z dizi listesi tek sayfada bulunduğu için sayfalama iptal edildi
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val document = app.get(request.data).document
        
        // HTML'deki ul.alphabetical-category-list altındaki li > a etiketlerini çekiyoruz
        val home = document.select("ul.alphabetical-category-list li a").mapNotNull {
            val title = it.attr("title").ifEmpty { it.text().trim() }
            val href = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
            
            if (title.isEmpty()) return@mapNotNull null

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                // Alfabetik ana sayfada afiş URL'si bulunmuyor
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        // Arama sonuçları sayfa yapısı eski düzeni kullanıyor olabileceği için korunmuştur
        return document.select("div.incontent div#list-series").mapNotNull {
            val title     = it.selectFirst("div#categorytitle a")?.text() ?: return@mapNotNull null
            val href      = fixUrlNull(it.selectFirst("div#categorytitle a")?.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src"))
        val description     = document.selectFirst("div.diziyou_desc")?.ownText()?.trim()
        val year            = document.selectFirst("span.dizimeta:contains(Yapım Yılı)")?.nextSibling()?.toString()?.trim()?.toIntOrNull()
        val tags            = document.select("div.genres a").map { it.text() }
        val rating          = document.selectFirst("span.dizimeta:contains(IMDB)")?.nextSibling()?.toString()?.trim()?.toRatingInt()
        val actors          = document.selectFirst("span.dizimeta:contains(Oyuncular)")?.nextSibling()?.toString()?.trim()?.split(", ")?.map { Actor(it) }
        val trailer         = document.selectFirst("iframe.trailer-video")?.attr("src")

        val episodes = document.select("div.bolumust").mapNotNull {
            val epName    = it.selectFirst("div.baslik")?.ownText()?.trim() ?: return@mapNotNull null
            val epHref    = it.closest("a")?.attr("href")?.let { href -> fixUrlNull(href) } ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\. Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\. Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name = it.selectFirst("div.bolumismi")?.text()?.trim()?.replace(Regex("""[()]"""), "")?.trim() ?: epName
                this.season = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.rating    = rating
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZY", "data » $data")
        val document = app.get(data).document

        val itemId     = document.selectFirst("iframe#diziyouPlayer")?.attr("src")?.split("/")?.lastOrNull()?.substringBefore(".html") ?: return false
        Log.d("DZY", "itemId » $itemId")

        val subTitles  = mutableListOf<DiziyouSubtitle>()
        val streamUrls = mutableListOf<DiziyouStream>()
        val storage    = mainUrl.replace("www", "storage")

        document.select("span.diziyouOption").forEach {
            val optId   = it.attr("id")

            if (optId == "turkceAltyazili") {
                subTitles.add(DiziyouSubtitle("Turkish", "${storage}/subtitles/${itemId}/tr.vtt"))
                streamUrls.add(DiziyouStream("Orjinal Dil", "${storage}/episodes/${itemId}/play.m3u8"))
            }

            if (optId == "ingilizceAltyazili") {
                subTitles.add(DiziyouSubtitle("English", "${storage}/subtitles/${itemId}/en.vtt"))
                streamUrls.add(DiziyouStream("Orjinal Dil", "${storage}/episodes/${itemId}/play.m3u8"))
            }

            if (optId == "turkceDublaj") {
                streamUrls.add(DiziyouStream("Türkçe Dublaj", "${storage}/episodes/${itemId}_tr/play.m3u8"))
            }
        }

        for (sub in subTitles) {
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = sub.name,
                    url  = fixUrl(sub.url)
                )
            )
        }

        for (stream in streamUrls) {
            callback.invoke(
                ExtractorLink(
                    source  = stream.name,
                    name    = stream.name,
                    url     = stream.url,
                    referer = "${mainUrl}/",
                    quality = Qualities.Unknown.value,
                    isM3u8  = true
                )
            )
        }

        return true
    }

    data class DiziyouSubtitle(val name: String, val url: String)
    data class DiziyouStream(val name: String, val url: String)
}
