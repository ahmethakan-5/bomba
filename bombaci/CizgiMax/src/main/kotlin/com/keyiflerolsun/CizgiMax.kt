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
    val detectedUrls = mutableSetOf<String>()

    // 1. DOM üzerindeki iframe etiketlerini tara (src, data-src, data-lazy-src)
    document.select("iframe").forEach { element ->
        val src = element.attr("src").ifEmpty { 
            element.attr("data-src").ifEmpty { 
                element.attr("data-lazy-src") 
            } 
        }
        if (src.isNotBlank()) detectedUrls.add(src)
    }

    // 2. HTML içindeki wargamings.net script bağlantılarını bul ve script içeriğini tara
    val wargamingsRegex = Regex("""https?://[^\s"'<>]*wargamings\.net[^\s"'<>]*""")
    wargamingsRegex.findAll(html).forEach { match ->
        val scriptUrl = match.value
        runCatching {
            val scriptContent = app.get(scriptUrl, referer = data).text
            // Script içindeki TauVideo veya iframe URL'lerini ayıkla
            val embedRegex = Regex("""https?://[^\s"'<>\\]+""")
            embedRegex.findAll(scriptContent).forEach { embedMatch ->
                val embedUrl = embedMatch.value.replace("\\/", "/")
                if (embedUrl.contains("tau-video") || embedUrl.contains("embed")) {
                    detectedUrls.add(embedUrl)
                }
            }
        }
    }

    // 3. Ham HTML metninde TauVideo URL'si varsa yakala
    val tauRegex = Regex("""https?://[^\s"'<>]*tau-video[^\s"'<>]*""")
    tauRegex.findAll(html).forEach { match ->
        detectedUrls.add(match.value)
    }

    // 4. Yakalanan tüm bağlantıları çalıştır
    for (url in detectedUrls) {
        val cleanUrl = if (url.startsWith("//")) "https:$url" else url

        if (cleanUrl.contains("tau-video")) {
            TauVideo().getUrl(cleanUrl, data, subtitleCallback, callback)
            foundAnyLink = true
        } else {
            loadExtractor(cleanUrl, data, subtitleCallback, callback)
            foundAnyLink = true
        }
    }

    return foundAnyLink
}
