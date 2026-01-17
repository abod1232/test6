override suspend fun load(url: String): LoadResponse? {
    val headers = getAuthenticatedHeaders()

    val uri = android.net.Uri.parse(url)
    val type = uri.getQueryParameter("type") ?: return null
    val id = uri.getQueryParameter("id") ?: return null

    // ==========================================================
    // 🎬 MOVIE
    // ==========================================================
    if (type == "movie") {
        val detailUrl =
            "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2" +
            "&r=/vod/product-detail&product_id=$id" +
            "&area_id=$areaId&language_flag_id=$languageId"

        val resp = app.get(detailUrl, headers = headers)
            .parsedSafe<ViuDetailResponse>()
            ?: return null

        val product =
            resp.data?.product ?: resp.data?.currentProduct ?: return null

        return newMovieLoadResponse(
            product.name ?: "Unknown",
            url,
            TvType.Movie,
            product.productId
        ) {
            posterUrl = product.coverImage
            plot = product.description ?: product.synopsis
        }
    }

    // ==========================================================
    // 📺 SERIES
    // ==========================================================
    if (type == "series") {
        val epUrl =
            "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2" +
            "&r=/vod/product-list&series_id=$id&size=1000" +
            "&area_id=$areaId&language_flag_id=$languageId"

        val epResp = app.get(epUrl, headers = headers)
            .parsedSafe<ViuEpisodeListResponse>()
            ?: return null

        val products = epResp.data?.products ?: return null
        if (products.isEmpty()) return null

        val episodes = products.mapNotNull { ep ->
            val productId = ep.productId ?: return@mapNotNull null

            newEpisode(productId) {
                this.data = productId        // 🔥 مهم جدًا
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
            posterUrl = first.seriesCoverLandscapeImageUrl ?: first.coverImage
            plot = first.description ?: first.synopsis
        }
    }

    return null
}



override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    // ==========================================================
    // 0️⃣ Headers مطابقة لتطبيق Viu الرسمي
    // ==========================================================
    val playbackHeaders = mapOf(
        "Authorization" to "Bearer ${getAuthToken()}",
        "platform" to "android",
        "content-type" to "application/json",
        "user-agent" to "okhttp/4.12.0",
        "accept-encoding" to "gzip"
    )

    // ==========================================================
    // 1️⃣ جلب تفاصيل الحلقة لاستخراج ccs_product_id
    // ==========================================================
    val detailUrl =
        "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2" +
        "&r=/vod/product-detail&product_id=$data" +
        "&area_id=$areaId&language_flag_id=$languageId"

    val detailResp = app.get(
        detailUrl,
        headers = playbackHeaders
    ).parsedSafe<ViuDetailResponse>() ?: return false

    val product =
        detailResp.data?.product
            ?: detailResp.data?.currentProduct
            ?: return false

    val ccsId = product.ccsProductId ?: return false

    // ==========================================================
    // 2️⃣ طلب playback/distribute
    // ==========================================================
    val playUrl =
        "$playbackUrl?ccs_product_id=$ccsId" +
        "&platform_flag_label=phone" +
        "&language_flag_id=$languageId" +
        "&ut=0" +
        "&area_id=$areaId" +
        "&os_flag_id=2" +
        "&countryCode=$countryCode"

    val playResp = app.get(
        playUrl,
        headers = playbackHeaders
    ).parsedSafe<ViuPlaybackResponse>() ?: return false

    val streams = playResp.data?.stream?.url ?: return false

    // ==========================================================
    // 3️⃣ إضافة روابط m3u8 مباشرة
    // ==========================================================
    streams.forEach { (qualityKey, streamUrl) ->
        if (streamUrl.isNullOrBlank()) return@forEach

        callback(
            newExtractorLink(
                source = name,
                name = "Viu ${qualityKey.uppercase()}",
                url = streamUrl
            ) {
                isM3u8 = true
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
