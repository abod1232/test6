override suspend fun load(url: String): LoadResponse? {
    val headers = getAuthenticatedHeaders()

    // الرابط القادم من search مثل:
    // https://www.viu.com/load?type=series&id=27251
    val uri = android.net.Uri.parse(url)
    val seriesId = uri.getQueryParameter("id") ?: return null

    // ==========================================================
    // 1️⃣ جلب قائمة الحلقات
    // ==========================================================
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

    // ==========================================================
    // 2️⃣ بناء الحلقات
    // ==========================================================
    val episodes = products.mapNotNull { ep ->
        val ccsId = ep.ccsProductId ?: return@mapNotNull null
        val productId = ep.productId ?: return@mapNotNull null

        newEpisode(ccsId) {
            // 🔥 نخزن الاثنين معًا (فيديو + ترجمة)
            data = mapOf(
                "ccs" to ccsId,
                "pid" to productId
            ).toJson()

            name = ep.synopsis ?: "Episode ${ep.number}"
            episode = ep.number?.toIntOrNull()
            posterUrl = ep.coverImage
        }
    }.sortedBy { it.episode }

    // ==========================================================
    // 3️⃣ معلومات المسلسل
    // ==========================================================
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

    // ==========================================================
    // 1️⃣ فك البيانات القادمة من load
    // ==========================================================
    val json = data.parseJson<Map<String, String>>()
    val ccsId = json["ccs"] ?: return false
    val productId = json["pid"] ?: return false

    // ==========================================================
    // 2️⃣ Headers رسمية مثل تطبيق Viu
    // ==========================================================
    val headers = mapOf(
        "Authorization" to "Bearer ${getAuthToken()}",
        "User-Agent" to "okhttp/4.12.0",
        "Accept" to "application/json"
    )

    // ==========================================================
    // 3️⃣ جلب الترجمة (vod/detail)
    // ==========================================================
    val subtitleUrl =
        "$mobileApiUrl?r=/vod/detail" +
                "&product_id=$productId" +
                "&platform_flag_label=phone" +
                "&language_flag_id=$languageId" +
                "&ut=0" +
                "&area_id=$areaId" +
                "&os_flag_id=2" +
                "&countryCode=$countryCode"

    val subResp = app.get(subtitleUrl, headers = headers)
        .parsedSafe<ViuDetailResponse>()

    subResp?.data?.currentProduct?.subtitles?.forEach { sub ->
        val url = sub.subtitleUrl ?: sub.url ?: return@forEach
        val lang = sub.isoCode ?: sub.code ?: sub.name ?: "Unknown"

        subtitleCallback(
            SubtitleFile(lang, url)
        )
    }

    // ==========================================================
    // 4️⃣ playback/distribute (الفيديو)
    // ==========================================================
    val playUrl =
        "$playbackUrl?ccs_product_id=$ccsId" +
                "&platform_flag_label=phone" +
                "&language_flag_id=$languageId" +
                "&ut=0" +
                "&area_id=$areaId" +
                "&os_flag_id=2" +
                "&countryCode=$countryCode"

    val playResp = app.get(playUrl, headers = headers)
        .parsedSafe<ViuPlaybackResponse>()
        ?: return false

    val streams = playResp.data?.stream?.url ?: return false

    streams.forEach { (q, link) ->
        if (link.isNullOrBlank()) return@forEach

        callback(
            newExtractorLink(
                source = name,
                name = "Viu ${q.uppercase()}",
                url = link
            ) {
                referer = "https://www.viu.com/"
                quality = when {
                    q.contains("1080") -> Qualities.P1080.value
                    q.contains("720") -> Qualities.P720.value
                    q.contains("480") -> Qualities.P480.value
                    q.contains("240") -> 240
                    else -> Qualities.Unknown.value
                }
            }
        )
    }

    return true
}


data class ViuSubtitle(
    @JsonProperty("name") val name: String?,
    @JsonProperty("code") val code: String?,
    @JsonProperty("iso_code") val isoCode: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("subtitle_url") val subtitleUrl: String?
)
