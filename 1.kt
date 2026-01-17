override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    // ==========================================================
    // 1️⃣ فك البيانات القادمة من load
    // data = {"ccs":"xxxx","pid":"yyyy"}
    // ==========================================================
    val json = AppUtils.parseJson<Map<String, String>>(data)
    val ccsId = json["ccs"] ?: return false
    val productId = json["pid"] ?: return false

    // ==========================================================
    // 2️⃣ Headers مطابقة لتطبيق Viu
    // ==========================================================
    val headers = mapOf(
        "Authorization" to "Bearer ${getAuthToken()}",
        "User-Agent" to "okhttp/4.12.0",
        "Accept" to "application/json"
    )

    // ==========================================================
    // 3️⃣ جلب الترجمة من vod/detail
    // ==========================================================
    val detailUrl =
        "$mobileApiUrl?r=/vod/detail" +
                "&product_id=$productId" +
                "&platform_flag_label=phone" +
                "&language_flag_id=$languageId" +
                "&ut=0" +
                "&area_id=$areaId" +
                "&os_flag_id=2" +
                "&countryCode=$countryCode"

    val detailResp = app.get(detailUrl, headers = headers)
        .parsedSafe<ViuDetailResponse>()

    detailResp?.data?.currentProduct?.subtitles?.forEach { sub ->
        val subUrl = sub.subtitleUrl ?: sub.url ?: return@forEach

        subtitleCallback(
            SubtitleFile(
                lang = sub.isoCode ?: sub.code ?: "unknown",
                url = subUrl,
                headers = mapOf(
                    "Authorization" to "Bearer ${getAuthToken()}",
                    "User-Agent" to "okhttp/4.12.0",
                    "Accept" to "text/vtt, application/octet-stream"
                ),
                mimeType = "text/vtt"
            )
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
