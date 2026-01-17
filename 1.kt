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
    override var name = "Viu MENA"
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
        val url = "$mobileApiUrl?platform_flag_label=web&r=/search/video&keyword=$query&page=1&limit=20&area_id=$areaId&language_flag_id=$languageId"

        val response = app.get(url, headers = headers).parsedSafe<ViuSearchResponse>()

        val series = response?.data?.series?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val movies = response?.data?.movies?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        return series + movies
    }

    // =========================================================================
    // 3. Load Details
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val headers = getAuthenticatedHeaders()
        val id = url.substringAfterLast("/")

        val detailUrl = "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2&r=/vod/product-detail&product_id=$id&area_id=$areaId&language_flag_id=$languageId"

        val response = app.get(detailUrl, headers = headers).parsedSafe<ViuDetailResponse>()

        // Fix: Safe access for response and data
        val responseData = response?.data ?: return null
        val data = responseData.product ?: responseData.currentProduct ?: return null
        val seriesData = responseData.series

        val title = data.seriesName ?: seriesData?.name ?: data.name ?: "Unknown"
        val poster = data.coverImage
        val description = data.description ?: data.synopsis
        val isMovie = data.isMovie == 1

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, data.productId) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val seriesId = data.seriesId ?: seriesData?.id ?: return null
            val epUrl = "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2&r=/vod/product-list&series_id=$seriesId&size=1000&area_id=$areaId&language_flag_id=$languageId"

            val epResponse = app.get(epUrl, headers = headers).parsedSafe<ViuEpisodeListResponse>()
            val episodes = epResponse?.data?.products?.map { ep ->
                newEpisode(ep.productId!!) {
                    this.name = ep.synopsis ?: "Episode ${ep.number}"
                    this.episode = ep.number?.toIntOrNull()
                    this.posterUrl = ep.coverImage
                }
            }?.sortedBy { it.episode } ?: emptyList()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // =========================================================================
    // 4. Load Links
    // =========================================================================
    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = getAuthenticatedHeaders()
        val detailUrl = "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2&r=/vod/product-detail&product_id=$data&area_id=$areaId&language_flag_id=$languageId"

        val detailResp = app.get(detailUrl, headers = headers).parsedSafe<ViuDetailResponse>()

        // Fix: Safe access with ?.
        val productData = detailResp?.data?.product ?: detailResp?.data?.currentProduct ?: return false

        val ccsId = productData.ccsProductId

        productData.subtitles?.forEach { sub ->
            val subUrl = sub.url ?: return@forEach
            val subLang = sub.name ?: sub.code ?: "Unknown"
            subtitleCallback(SubtitleFile(subLang, subUrl))
        }

        if (ccsId.isNullOrEmpty()) return false

        val playbackHeaders = headers + mapOf(
            "platform" to "android",
            "content-type" to "application/json"
        )

        val playUrl = "$playbackUrl?ccs_product_id=$ccsId&platform_flag_label=phone&language_flag_id=$languageId&ut=0&area_id=$areaId&os_flag_id=2&countryCode=$countryCode"

        val playResp = app.get(playUrl, headers = playbackHeaders).parsedSafe<ViuPlaybackResponse>()
        val streamData = playResp?.data?.stream?.url

        if (streamData != null) {
            for ((key, link) in streamData) {
                if (link.isNullOrBlank()) continue

                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        this.name,
                        link,
                        "https://www.viu.com/",
                        headers = mapOf("User-Agent" to baseHeaders["User-Agent"]!!)
                    ).forEach(callback)
                } else {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = link,
                        ){
                            referer = "https://www.viu.com/"
                            quality = getQualityFromName(key)

                        }
                    )
                }
            }
            return true
        }

        return false
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
