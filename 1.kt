package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import java.util.UUID
import com.lagradost.cloudstream3.utils.newExtractorLink

class ViuMenaProvider : MainAPI() {
    override var mainUrl = "https://www.viu.com"
    override var name = "Viu"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    // --- Constants ---
    private val mobileApiUrl = "https://api-gateway-global.viu.com/api/mobile"
    private val tokenUrl = "https://api-gateway-global.viu.com/api/auth/token"
    private val playbackUrl = "https://api-gateway-global.viu.com/api/playback/distribute"

    private val areaId = "1004" // Iraq/MENA
    private val countryCode = "IQ"
    private val languageId = "6" // Arabic

    // Cache for Token
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0
    private val deviceId = UUID.randomUUID().toString()

    // --- Headers ---
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12)",
        "Accept" to "application/json",
        "Referer" to "https://www.viu.com/",
        "Origin" to "https://www.viu.com"
    )

    // =========================================================================
    // Auth
    // =========================================================================

    private suspend fun getAuthToken(): String {
        val currentTime = System.currentTimeMillis() / 1000
        if (cachedToken != null && currentTime < tokenExpiry) {
            return cachedToken!!
        }

        val payload = mapOf(
            "countryCode" to countryCode,
            "platform" to "android",
            "platformFlagLabel" to "phone",
            "language" to languageId,
            "deviceId" to deviceId,
            "dataTrackingDeviceId" to UUID.randomUUID().toString(),
            "osVersion" to "33",
            "appVersion" to "2.23.0",
            "buildVersion" to "790",
            "carrierId" to "0",
            "carrierName" to "null",
            "appBundleId" to "com.vuclip.viu",
            "flavour" to "all"
        )

        val response = app.post(
            tokenUrl,
            headers = baseHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            data = payload
        ).parsedSafe<TokenResponse>()

        val token = response?.token ?: response?.data?.token
        ?: throw Error("Failed to get Auth Token")

        val expiresIn = response?.expiresIn ?: response?.data?.expiresIn ?: 3600

        cachedToken = token
        tokenExpiry = currentTime + expiresIn

        return token
    }

    private suspend fun getAuthenticatedHeaders(): Map<String, String> {
        val token = getAuthToken()
        return baseHeaders + mapOf("Authorization" to "Bearer $token")
    }

    // =========================================================================
    // 1. Main Page
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = getAuthenticatedHeaders()
        val items = ArrayList<HomePageList>()

        val categoryIds = listOf(
            Pair("مسلسلات عربية", "726"),
            Pair("مسلسلات كورية", "846"),
            Pair("مسلسلات تركية", "847"),
            Pair("أفلام", "848")
        )

        categoryIds.forEach { (name, catId) ->
            val url = "$mobileApiUrl?platform_flag_label=phone&area_id=$areaId&language_flag_id=$languageId&r=/product/list&category_id=$catId&size=10&sort=date"
            try {
                val response = app.get(url, headers = headers).parsedSafe<ViuResponse>()
                val results = response?.data?.items?.mapNotNull { it.toSearchResponse() }
                if (!results.isNullOrEmpty()) {
                    items.add(HomePageList(name, results))
                }
            } catch (e: Exception) {
                // Ignore failure
            }
        }

        return HomePageResponse(items)
    }

    // =========================================================================
    // 2. Search
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val headers = getAuthenticatedHeaders()

        val url =
            "$mobileApiUrl?platform_flag_label=web&r=/search/video" +
                    "&keyword=$query&page=1&limit=20" +
                    "&area_id=$areaId&language_flag_id=$languageId"

        val resp = app.get(url, headers = headers)
            .parsedSafe<ViuSearchResponse>()
            ?: return emptyList()

        val results = ArrayList<SearchResponse>()

        // =======================
        // 📺 SERIES
        // =======================
        resp.data?.series?.forEach { item ->
            val seriesId = item.seriesId ?: item.id ?: return@forEach
            val title = item.seriesName ?: item.name ?: return@forEach

            val dataUrl = "$mainUrl/load?type=series&id=$seriesId"

            results.add(
                newTvSeriesSearchResponse(
                    title,
                    dataUrl,
                    TvType.TvSeries
                ) {
                    posterUrl = item.coverImage ?: item.posterUrl
                }
            )
        }

        // =======================
        // 🎬 MOVIES
        // =======================
        resp.data?.movies?.forEach { item ->
            val productId = item.productId ?: return@forEach
            val title = item.name ?: item.title ?: return@forEach

            val dataUrl = "$mainUrl/load?type=movie&id=$productId"

            results.add(
                newMovieSearchResponse(
                    title,
                    dataUrl,
                    TvType.Movie
                ) {
                    posterUrl = item.coverImage ?: item.posterUrl
                }
            )
        }

        return results
    }



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
                posterUrl = posterUrl
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

    data class ViuSubtitle(
        @JsonProperty("name") val name: String?,
        @JsonProperty("code") val code: String?,
        @JsonProperty("url") val url: String?
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
}
