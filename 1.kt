override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    // 🔥 data = ccs_product_id مباشرة
    val ccsId = data

    val headers = mapOf(
        "Authorization" to "Bearer ${getAuthToken()}",
        "platform" to "android",
        "content-type" to "application/json",
        "user-agent" to "okhttp/4.12.0",
        "accept-encoding" to "gzip"
    )

    val playUrl =
        "$playbackUrl?ccs_product_id=$ccsId" +
        "&platform_flag_label=phone" +
        "&language_flag_id=$languageId" +
        "&ut=0" +
        "&area_id=$areaId" +
        "&os_flag_id=2" +
        "&countryCode=$countryCode"

    val playResp = app.get(playUrl, headers = headers)
        .parsedSafe<ViuPlaybackResponse>() ?: return false

    val streams = playResp.data?.stream?.url ?: return false

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
