// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri

class YouTube : MainAPI() {
    override var mainUrl              = "https://www.youtube.com"
    override var name                 = "YouTube"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Others)

    private val mapper = jacksonObjectMapper()

    // * Türkçe sonuçlar + çerez onay ekranını atlamak için
    private val ytHeaders = mapOf(
        "Accept-Language" to "tr-TR,tr;q=0.9",
        "Cookie"          to "CONSENT=YES+1; SOCS=CAI"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Önerilen"
    )

    // ! Sayfa içindeki "var ytInitialData = {...};" JSON'unu ayıklar
    private fun String.extractJson(marker: String): JsonNode? {
        val start = this.indexOf(marker)
        if (start == -1) return null

        return try {
            mapper.readTree(this.substring(start + marker.length))
        } catch (e: Exception) {
            null
        }
    }

    private fun videoToSearchResponse(videoId: String, title: String): SearchResponse {
        return newMovieSearchResponse(title, "${mainUrl}/watch?v=${videoId}", TvType.Others) {
            this.posterUrl = "https://i.ytimg.com/vi/${videoId}/hqdefault.jpg"
        }
    }

    // ! ytInitialData içinde dolaşıp videoları toplar (ana sayfa, arama ve önerilenler için ortak)
    private fun JsonNode.collectVideos(result: MutableList<SearchResponse>, seen: MutableSet<String>) {
        if (this.isObject) {
            // * 2025+ yeni görünüm: lockupViewModel
            if (this.path("contentType").asText() == "LOCKUP_CONTENT_TYPE_VIDEO") {
                val videoId = this.path("contentId").asText("")
                val title   = this.path("metadata").path("lockupMetadataViewModel").path("title").path("content").asText("")

                if (videoId.isNotEmpty() && title.isNotEmpty() && seen.add(videoId)) {
                    result.add(videoToSearchResponse(videoId, title))
                }
            }

            this.fields().forEach { (key, value) ->
                when (key) {
                    "adSlotRenderer"  -> Unit // ? Reklamları atla
                    // * Eski görünüm: videoRenderer (arama sonuçlarında hâlâ olabilir)
                    "videoRenderer", "compactVideoRenderer" -> {
                        val videoId = value.path("videoId").asText("")
                        val title   = value.path("title").path("runs").path(0).path("text").asText(
                            value.path("title").path("simpleText").asText("")
                        )

                        if (videoId.isNotEmpty() && title.isNotEmpty() && seen.add(videoId)) {
                            result.add(videoToSearchResponse(videoId, title))
                        }
                    }
                    else -> value.collectVideos(result, seen)
                }
            }
        } else if (this.isArray) {
            this.forEach { it.collectVideos(result, seen) }
        }
    }

    private fun parseVideos(html: String): List<SearchResponse> {
        val data   = html.extractJson("var ytInitialData = ") ?: return emptyList()
        val result = mutableListOf<SearchResponse>()
        data.collectVideos(result, mutableSetOf())

        return result
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("${request.data}?hl=tr&gl=TR", headers = ytHeaders).text
        val home = parseVideos(html)

        // ? Sonraki sayfalar innertube "continuation" ister, o yüzden yalnızca ilk sayfa
        return newHomePageResponse(request.name, home, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get(
            "${mainUrl}/results?search_query=${query.encodeUri()}&sp=EgIQAQ%253D%253D&hl=tr&gl=TR",
            headers = ytHeaders
        ).text

        return parseVideos(html)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("""(?:v=|youtu\.be/|shorts/)([a-zA-Z0-9_-]{11})""").find(url)?.groupValues?.get(1) ?: return null
        val html    = app.get("${mainUrl}/watch?v=${videoId}&hl=tr&gl=TR", headers = ytHeaders).text

        val player  = html.extractJson("var ytInitialPlayerResponse = ")
        val details = player?.path("videoDetails")

        val title       = details?.path("title")?.asText("")?.takeIf { it.isNotEmpty() } ?: return null
        val description = details.path("shortDescription").asText("")
        val author      = details.path("author").asText("")

        val recommendations = mutableListOf<SearchResponse>()
        html.extractJson("var ytInitialData = ")?.collectVideos(recommendations, mutableSetOf(videoId))

        return newMovieLoadResponse(title, "${mainUrl}/watch?v=${videoId}", TvType.Others, videoId) {
            this.posterUrl       = "https://i.ytimg.com/vi/${videoId}/maxresdefault.jpg"
            this.plot            = description
            this.recommendations = recommendations
            if (author.isNotEmpty()) this.actors = listOf(ActorData(Actor(author)))
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // * CloudStream'in kendi YoutubeExtractor'ı watch bağlantısını işler
        loadExtractor("https://www.youtube.com/watch?v=${data}", "${mainUrl}/", subtitleCallback, callback)

        return true
    }
}
