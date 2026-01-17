override suspend fun load(url: String): LoadResponse? {
    val headers = getAuthenticatedHeaders()

    val uri = android.net.Uri.parse(url)
    val seriesId = uri.getQueryParameter("id") ?: return null

    val epUrl =
        "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2" +
                "&r=/vod/product-list" +
                "&series_id=$seriesId" +
                "&size=1000" +
                "&area_id=$areaId" +
                "&language_flag_id=$languageId"

    val resp = app.get(epUrl, headers = headers)
        .parsedSafe<ViuEpisodeListResponse>()
        ?: return null

    val products = resp.data?.products ?: return null
    if (products.isEmpty()) return null

    val episodes = products.mapNotNull { ep ->
        val ccsId = ep.ccsProductId ?: return@mapNotNull null

        newEpisode(ccsId) {
            data = ccsId                     // 🔥 نفس بايثون
            name = ep.synopsis ?: "Episode ${ep.number}"
            episode = ep.number?.toIntOrNull()
            posterUrl = ep.coverImage
        }
    }.sortedBy { it.episode }

    val first = products.first()

    return newTvSeriesLoadResponse(
        first.seriesName ?: "Unknown",
        url,
        TvType.TvSeries,
        episodes
    ) {
        posterUrl = first.seriesCoverPortraitImageUrl
        plot = first.description ?: first.synopsis
    }
}


override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val headers = mapOf(
        "Authorization" to "Bearer ${getAuthToken()}",
        "User-Agent" to "okhttp/4.12.0",
        "Accept" to "application/json"
    )

    val playUrl =
        "$playbackUrl?ccs_product_id=$data" +
                "&platform_flag_label=phone" +
                "&language_flag_id=$languageId" +
                "&ut=0" +
                "&area_id=$areaId" +
                "&os_flag_id=2" +
                "&countryCode=$countryCode"

    val resp = app.get(playUrl, headers = headers)
        .parsedSafe<ViuPlaybackResponse>()
        ?: return false

    val streams = resp.data?.stream?.url ?: return false

    streams.forEach { (qualityKey, streamUrl) ->
        if (streamUrl.isNullOrBlank()) return@forEach

        callback(
            newExtractorLink(
                source = name,
                name = "Viu ${qualityKey.uppercase()}",
                url = streamUrl
            ) {
                referer = "https://www.viu.com/"
                quality = when {
                    qualityKey.contains("1080") -> Qualities.P1080.value
                    qualityKey.contains("720") -> Qualities.P720.value
                    qualityKey.contains("480") -> Qualities.P480.value
                    qualityKey.contains("240") -> 240
                    else -> Qualities.Unknown.value
                }
            }
        )
    }

    return true
}
