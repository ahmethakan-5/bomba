// ------------------------------------------------------------------
    // Güncellenmiş Kart Yardımcıları
    // ------------------------------------------------------------------
    private fun Element.recommendedItem(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = (selectFirst("span.baslik, .series-title, .title")?.text() ?: a.attr("title")).cleanTitle()
        if (title.isBlank()) return null
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.posterItem(): SearchResponse? {
        val a = selectFirst("a.poster-title[href]") 
            ?: selectFirst("a[href*=/diziler/]") 
            ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = a.attr("title").ifBlank { a.text() }.cleanTitle()
        if (title.isBlank()) return null
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.episodeCardItem(byName: Map<String, Pair<String, String>>): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val epHref = fixUrlNull(a.attr("href")) ?: return null
        
        val rawName = selectFirst("b.series-name, .series-title, .title")?.text()?.trim().orEmpty()
        val season = selectFirst("span.season, .season-no")?.text()?.trim().orEmpty()
        val episode = selectFirst("b.episode, .episode-no")?.text()?.trim().orEmpty()

        val hit = byName[norm(rawName)]
        val seriesUrl = hit?.second ?: seriesUrlFromEpisode(epHref) ?: epHref
        val shownName = hit?.first ?: rawName.ifBlank { a.attr("title") }
        
        val title = "$shownName $season $episode".replace(Regex("""\s+"""), " ").trim()
        if (title.isBlank()) return null
        
        val poster = imgUrl()
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { this.posterUrl = poster }
    }

    // ------------------------------------------------------------------
    // Güncellenmiş Ana Sayfa Fonksiyonu
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDoc(mainUrl)

        val index = readSeriesIndex(document)
        if (index.isNotEmpty()) seriesCache = index
        val byName = index.associateBy { norm(it.first) }

        val lists = mutableListOf<HomePageList>()

        // 1. Öne Çıkan / Beklenen Diziler (#recommended-series)
        document.select("#recommended-series li, section.recommended li").mapNotNull { it.recommendedItem() }
            .distinctBy { it.url }
            .takeIf { it.isNotEmpty() }
            ?.let { lists.add(HomePageList("Beklenen / Eklenen Diziler", it)) }

        // 2. Dikkat Çeken Yeni Diziler (#new-serieses)
        document.select("#new-serieses article, #new-series article, article.article-series-poster").mapNotNull { it.posterItem() }
            .distinctBy { it.url }
            .takeIf { it.isNotEmpty() }
            ?.let { lists.add(HomePageList("Dikkat Çeken Yeni Diziler", it)) }

        // 3. Son Bölümler
        val latestEpisodes = document.select("article.article-episode-card, .latest-episodes article")
            .mapNotNull { it.episodeCardItem(byName) }
            .distinctBy { it.url }
        
        if (latestEpisodes.isNotEmpty()) {
            lists.add(HomePageList("Son Bölümler", latestEpisodes))
        }

        // 4. Efsane Diziler (#best-series)
        document.select("#best-series article, article.article-series-small-grid").mapNotNull { it.smallGridItem() }
            .distinctBy { it.url }
            .takeIf { it.isNotEmpty() }
            ?.let { lists.add(HomePageList("Efsane Diziler", it)) }

        // Eğer yukarıdakiler yine de boş dönerse (fallback), sayfadaki alfabetik listeden rastgele/ilk dizileri çek
        if (lists.isEmpty() && index.isNotEmpty()) {
            val fallbackItems = index.take(20).map { (title, url) ->
                newTvSeriesSearchResponse(title, url, TvType.TvSeries)
            }
            lists.add(HomePageList("Tüm Diziler", fallbackItems))
        }

        if (lists.isEmpty()) {
            throw ErrorLoadingException("DiziBox ana sayfası okunamadı (site yapısı değişmiş olabilir).")
        }

        return newHomePageResponse(lists, false)
    }
