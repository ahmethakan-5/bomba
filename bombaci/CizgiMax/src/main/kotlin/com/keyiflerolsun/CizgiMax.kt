package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class CizgiMax : MainAPI() {
    override var mainUrl = "https://cizgimax.online"
    override var name = "ÇizgiMax"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    // ... (getMainPage, search, load fonksiyonlarınız burada yer almalı)

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val html = response.text
        val document = response.document

        var foundAnyLink = false

        // 1. DOM üzerindeki iframe'leri tara
        val iframeSources = document.select("iframe[src], iframe[data-src]").mapNotNull { element ->
            val src = element.attr("src").ifEmpty { element.attr("data-src") }
            if (src.isEmpty()) null else src
        }

        for (src in iframeSources) {
            if (src.contains("tau-video")) {
                val fixUrl = if (src.startsWith("//")) "https:$src" else src
                TauVideo().getUrl(fixUrl, data, subtitleCallback, callback)
                foundAnyLink = true
            } else {
                loadExtractor(src, data, subtitleCallback, callback)
                foundAnyLink = true
            }
        }

        // 2. HTML text içerisinden TauVideo embed adresi/ID'si tara
        if (!foundAnyLink) {
            val tauRegex = Regex("""https?://tau-video\.xyz/(?:embed/|api/video/)?([a-zA-Z0-9_-]+)""")
            val matches = tauRegex.findAll(html)

            for (match in matches) {
                val embedId = match.groupValues.getOrNull(1)
                if (!embedId.isNullOrEmpty()) {
                    val embedUrl = "https://tau-video.xyz/embed/$embedId"
                    TauVideo().getUrl(embedUrl, data, subtitleCallback, callback)
                    foundAnyLink = true
                }
            }
        }

        return foundAnyLink
    }
}

// TauVideo Extractor Sınıfı
open class TauVideo : ExtractorApi() {
    override val name = "TauVideo"
    override val mainUrl = "https://tau-video.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedId = url.substringAfterLast("/embed/").substringAfterLast("/").substringBefore("?")
        if (embedId.isBlank()) return

        val apiUrl = "$mainUrl/api/video/$embedId"

        val response = app.get(
            apiUrl,
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        ).parsedSafe<TauResponse>()

        response?.file?.let { videoUrl ->
            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name = name,
                    streamUrl = videoUrl,
                    referer = "$mainUrl/"
                ).forEach(callback)
            } else {
                callback(
                    ExtractorLink(
                        name = name,
                        source = name,
                        url = videoUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value
                    )
                )
            }
        }
    }

    data class TauResponse(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("status") val status: Boolean? = null
    )
}
