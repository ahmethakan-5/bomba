override suspend fun loadLinks(
    data: String,
    isCdn: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // 1. Bölüm sayfasının HTML içeriğini çekin
    val response = app.get(data)
    val html = response.text
    val document = response.document

    var foundAnyLink = false

    // 2. DOM üzerindeki tau-video veya benzeri iframe'leri tara
    val iframeSources = document.select("iframe[src], iframe[data-src]").mapNotNull {
        it.attr("src").ifEmpty { it.attr("data-src") }
    }

    for (src in iframeSources) {
        if (src.contains("tau-video")) {
            val fixUrl = if (src.startsWith("//")) "https:$src" else src
            TauVideo().getUrl(fixUrl, data, subtitleCallback, callback)
            foundAnyLink = true
        } else {
            // Diğer standart extractor'ları dene (Doodstream, Vidmoly vb.)
            loadExtractor(src, data, subtitleCallback, callback)
            foundAnyLink = true
        }
    }

    // 3. Eğer iframe DOM'a basılmadıysa HTML text içinden Regex ile TauVideo embed URL veya ID ara
    if (!foundAnyLink) {
        val tauRegex = Regex("""https?://tau-video\.xyz/(?:embed/|api/video/)?([a-zA-Z0-9_-]+)""")
        val matches = tauRegex.findAll(html)

        for (match in matches) {
            val embedId = match.groupValues.getOrNull(1)
            if (!embedId.isNullOfEmpty()) {
                val embedUrl = "https://tau-video.xyz/embed/$embedId"
                TauVideo().getUrl(embedUrl, data, subtitleCallback, callback)
                foundAnyLink = true
            }
        }
    }

    return foundAnyLink
}
