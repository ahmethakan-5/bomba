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

    private val ytHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
        "Cookie"          to "CONSENT=YES+1; SOCS=CAI"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Önerilen"
    )

    // ! JSON nesnesini sonundaki fazla karakterlerden etkilenmeden güvenle okur
    private fun String.extractJson(marker: String): JsonNode? {
        val start = this.indexOf(marker)
        if (start == -1) return null

        val jsonStartIndex = this.indexOf('{', start + marker.length)
        if (jsonStartIndex == -1) return null

        return try {
            val parser = mapper.factory.createParser(this.substring(jsonStartIndex))
            parser.readValueAsTree<JsonNode>()
        } catch (e: Exception) {
            null
        }
    }

    private fun videoToSearchResponse(videoId: String, title: String): SearchResponse {
        return newMovieSearchResponse(title, videoId, TvType.Others) {
            this.posterUrl = "https://i.ytimg.com/vi/${videoId}/hqdefault.jpg"
        }
    }

    private fun JsonNode.collectVideos(result: MutableList<SearchResponse>, seen: MutableSet<String>) {
        if (this.isObject) {
            if (this.path("contentType").asText() == "LOCKUP_CONTENT_TYPE_VIDEO") {
                val videoId = this.path("contentId").asText("")
                val title   = this.path("metadata").path("lockupMetadataViewModel").path("title").path("content").asText("")

                if (videoId.isNotEmpty() && title.isNotEmpty() && seen.add(videoId)) {
                    result.add(videoToSearchResponse(videoId, title))
                }
            }

            this.fields().forEach { (key, value) ->
                when (key) {
                    "adSlotRenderer" -> Unit
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
        val data   = html.extractJson("var ytInitialData =") ?: return emptyList()
        val result = mutableListOf<SearchResponse>()
        data.collectVideos(result, mutableSetOf())

        return result
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("${request.data}?hl=tr&gl=TR", headers = ytHeaders).text
        val home = parseVideos(html)

        return newHomePageResponse(request.name, home, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get(
            "${mainUrl}/results?search_query=${query.encodeUri()}&hl=tr&gl=TR",
            headers = ytHeaders
        ).text

        return parseVideos(html)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val videoId = if (url.startsWith("http")) {
            Regex("""(?:v=|youtu\.be/|shorts/)([a-zA-Z0-9_-]{11})""").find(url)?.groupValues?.get(1) ?: return null
        } else {
            url
        }

        val html = app.get("${mainUrl}/watch?v=${videoId}&hl=tr&gl=TR", headers = ytHeaders).text

        val player  = html.extractJson("var ytInitialPlayerResponse =")
        val details = player?.path("videoDetails")

        val title       = details?.path("title")?.asText("")?.takeIf { it.isNotEmpty() } ?: "YouTube Video"
        val description = details?.path("shortDescription")?.asText("") ?: ""
        val author      = details?.path("author")?.asText("") ?: ""

        val recommendations = mutableListOf<SearchResponse>()
        html.extractJson("var ytInitialData =")?.collectVideos(recommendations, mutableSetOf(videoId))

        return newMovieLoadResponse(title, videoId, TvType.Others, videoId) {
            this.posterUrl       = "https://i.ytimg.com/vi/${videoId}/maxresdefault.jpg"
            this.plot            = description
            this.recommendations = recommendations
            if (author.isNotEmpty()) this.actors = listOf(ActorData(Actor(author)))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoUrl = "https://www.youtube.com/watch?v=${data}"

        // 1. Yerleşik CloudStream Extractor'ı dene
        val loaded = loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
        if (loaded) return true

        // 2. Yedek: Invidious API üzerinden stream linklerini al
        return try {
            val invidiousInstances = listOf("https://invidious.nerdvpn.de", "https://inv.tux.im", "https://vid.puffyan.us")
            for (instance in invidiousInstances) {
                val res = app.get("${instance}/api/v1/videos/${data}").text
                val json = mapper.readTree(res)
                val formatStreams = json.path("formatStreams")

                if (formatStreams.isArray && formatStreams.size() > 0) {
                    formatStreams.forEach { stream ->
                        val url     = stream.path("url").asText("")
                        val quality = stream.path("qualityLabel").asText("720p")
                        val container = stream.path("container").asText("mp4")

                        if (url.isNotEmpty()) {
                            callback(
                                ExtractorLink(
                                    source  = "Invidious",
                                    name    = "YouTube (${quality})",
                                    url     = url,
                                    referer = "${instance}/",
                                    quality = getQualityFromName(quality),
                                    isM3u8  = container == "m3u8"
                                )
                            )
                        }
                    }
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
