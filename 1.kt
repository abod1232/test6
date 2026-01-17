override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // ==========================================================
        // 1️⃣ فك البيانات القادمة من load
        // ==========================================================
        val json = AppUtils.parseJson<Map<String, String>>(data)
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
            val rawUrl = sub.subtitleUrl ?: sub.url

            println("VIU-SUB: name=${sub.name}")
            println("VIU-SUB: iso=${sub.isoCode}")
            println("VIU-SUB: code=${sub.code}")
            println("VIU-SUB: url=$rawUrl")

            if (rawUrl.isNullOrEmpty()) {
                println("VIU-SUB: ❌ URL EMPTY")
                return@forEach
            }

            println("VIU-SUB: ✅ CALLBACK CALLED")

            subtitleCallback(
                SubtitleFile(
                    lang = sub.isoCode ?: sub.code ?: "unknown",
                    url = rawUrl
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



    private fun getQualityFromName(name: String): Int {
        return when {
            name.contains("1080") -> Qualities.P1080.value
            name.contains("720") -> Qualities.P720.value
            name.contains("480") -> Qualities.P480.value
            name.contains("240") -> 240
            else -> Qualities.Unknown.value
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun ViuItem.toSearchResponse(): SearchResponse? {
        val id = this.productId ?: this.id ?: return null
        val title = this.seriesName ?: this.name ?: this.title ?: "Unknown"
        val image = this.coverImage ?: this.posterUrl

        return newMovieSearchResponse(title, "https://www.viu.com/product/$id", TvType.TvSeries) {
            this.posterUrl = image
        }
    }

    data class TokenResponse(
        @JsonProperty("token") val token: String?,
        @JsonProperty("expires_in") val expiresIn: Long?,
        @JsonProperty("data") val data: TokenData?
    )

    data class TokenData(
        @JsonProperty("token") val token: String?,
        @JsonProperty("expires_in") val expiresIn: Long?
    )

    data class ViuResponse(
        @JsonProperty("data") val data: ViuData?
    )

    data class ViuSearchResponse(
        @JsonProperty("data") val data: ViuSearchData?
    )

    data class ViuSearchData(
        @JsonProperty("series") val series: List<ViuItem>?,
        @JsonProperty("movie") val movies: List<ViuItem>?
    )

    data class ViuData(
        @JsonProperty("items") val items: List<ViuItem>?
    )

    data class ViuEpisodeListResponse(
        @JsonProperty("data") val data: ViuEpisodeListData?
    )

    data class ViuEpisodeListData(
        @JsonProperty("product_list") val products: List<ViuItem>?
    )

    data class ViuDetailResponse(
        @JsonProperty("data") val data: ViuDetailData?
    )

    data class ViuDetailData(
        @JsonProperty("product") val product: ViuItem?,
        @JsonProperty("current_product") val currentProduct: ViuItem?,
        @JsonProperty("series") val series: ViuSeries?
    )

    data class ViuSeries(
        @JsonProperty("id") val id: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("series_id") val seriesId: String?,
    )

    data class ViuItem(
        @JsonProperty("product_id") val productId: String?,
        @JsonProperty("id") val id: String?,
        @JsonProperty("series_id") val seriesId: String?,
        @JsonProperty("series_name") val seriesName: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("cover_image_url") val coverImage: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("is_movie") val isMovie: Int?,
        @JsonProperty("number") val number: String?,
        @JsonProperty("ccs_product_id") val ccsProductId: String?,
        @JsonProperty("subtitles") val subtitles: List<ViuSubtitle>?
    )



    data class ViuPlaybackResponse(
        @JsonProperty("data") val data: PlaybackData?
    )

    data class PlaybackData(
        @JsonProperty("stream") val stream: PlaybackStream?
    )

    data class PlaybackStream(
        @JsonProperty("url") val url: Map<String, String>?
    )
