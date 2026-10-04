package com.keyiflerolsun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainUrlPlugin
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class CizgiMax : MainUrlPlugin() {
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

        // Iframe elemanlarını tarama
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

        // HTML içinden regex ile TauVideo linki arama
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
