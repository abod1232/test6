package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.utils.HlsPlaylistParser.MimeTypes
import android.R.attr.resource
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import java.util.UUID
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils
import android.R.attr.mimeType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.HlsPlaylistParser
import com.lagradost.cloudstream3.utils.getQualityFromName
class ViuMenaProvider : MainAPI() {
    override var mainUrl = "https://www.viu.com"
    override var name = "Viu1"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

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
        val first = products.first()

        // ==========================================================
        // 3️⃣ معلومات المسلسل
        // ==========================================================
        val seriesTitle =
            first.seriesName
                ?: first.title
                ?: first.name
                ?: "Unknown Series"

        return newTvSeriesLoadResponse(
            seriesTitle,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = first.coverImage ?: first.posterUrl
            plot = first.description ?: first.synopsis
        }
    }


    // =========================================================================
    // 3. Load Links (Extract Video + Subtitles)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[VIU-DEBUG] ================= START LOADLINKS =================")
        return try {
            // 1️⃣ فك البيانات الأولية
            val json = AppUtils.parseJson<Map<String, String>>(data)
            val ccsId = json["ccs"] ?: return false.also { println("[VIU-DEBUG] ❌ Error: ccsId is null") }
            val productId = json["pid"] ?: return false.also { println("[VIU-DEBUG] ❌ Error: productId is null") }

            println("[VIU-DEBUG] Processing Product ID: $productId | CCS ID: $ccsId")

            val headers = mapOf(
                "Authorization" to "Bearer ${getAuthToken()}",
                "User-Agent" to "okhttp/4.12.0",
                "Accept" to "application/json",
                "Referer" to "https://www.viu.com/"
            )

            // 2️⃣ جلب التفاصيل (Subtitle extraction)
            val detailUrl = "$mobileApiUrl?r=/vod/detail" +
                    "&product_id=$productId" +
                    "&platform_flag_label=phone" +
                    "&language_flag_id=$languageId" +
                    "&area_id=$areaId" +
                    "&os_flag_id=2" +
                    "&countryCode=$countryCode"

            println("[VIU-DEBUG] Fetching Detail URL: $detailUrl")

            val rawResponse = app.get(detailUrl, headers = headers).text
            // طباعة جزء من الرد للتأكد (اختياري)
            // println("[VIU-DEBUG] Raw Response snippet: ${rawResponse.take(500)}")

            val detailResp = AppUtils.parseJson<ViuDetailResponse>(rawResponse)
            val currentProduct = detailResp.data?.currentProduct

            if (currentProduct == null) {
                println("[VIU-DEBUG] ⚠️ Warning: current_product is null in JSON response!")
            } else {
                val subsList = currentProduct.subtitles
                println("[VIU-DEBUG] Found Subtitles List Size: ${subsList?.size ?: 0}")

                subsList?.forEachIndexed { index, sub ->
                    val subUrl = sub.url ?: sub.subtitleUrl
                    val subName = sub.name ?: "Unknown"
                    val subCode = sub.isoCode ?: sub.code ?: "und"

                    println("[VIU-DEBUG] [$index] Sub: $subName ($subCode) -> URL: $subUrl")

                    if (!subUrl.isNullOrEmpty()) {
                        subtitleCallback(
                            newSubtitleFile(
                                lang = subCode,
                                url = subUrl
                            )
                        )
                        println("[VIU-DEBUG] ✅ Added Subtitle: $subName")
                    } else {
                        println("[VIU-DEBUG] ❌ Skipped Subtitle: URL is empty")
                    }
                }
            }

            // 3️⃣ جلب رابط الفيديو (Playback extraction)
            println("[VIU-DEBUG] Fetching Playback Stream...")
            val playUrl = "$playbackUrl?ccs_product_id=$ccsId" +
                    "&platform_flag_label=phone" +
                    "&language_flag_id=$languageId" +
                    "&area_id=$areaId"

            val playResp = app.get(playUrl, headers = headers).parsedSafe<ViuPlaybackResponse>()
            val streams = playResp?.data?.stream?.url

            if (streams.isNullOrEmpty()) {
                println("[VIU-DEBUG] ❌ No video streams found!")
            } else {
                println("[VIU-DEBUG] Found ${streams.size} video qualities.")
                streams.entries
                    .sortedBy { getQualityFromName(it.key) }
                    .forEach { (qualityName, streamUrl) ->

                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Viu ${qualityName.uppercase()}",
                                url = streamUrl
                            ) {
                                referer = "https://www.viu.com/"
                                quality = getQualityFromName(qualityName)
                            }
                        )
                    }

            }

            println("[VIU-DEBUG] ================= END LOADLINKS =================")
            true
        } catch (e: Exception) {
            println("[VIU-DEBUG] 💥 Critical Error in loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // =========================================================================
    // Corrected Data Classes (Very Important)
    // =========================================================================

    data class ViuDetailResponse(
        @JsonProperty("data") val data: ViuDetailData?
    )

    data class ViuDetailData(
        // لاحظ هنا استخدام current_product كما في الـ JSON
        @JsonProperty("current_product") val currentProduct: ViuProductDetail?
    )

    data class ViuProductDetail(
        @JsonProperty("product_id") val productId: String?,
        // الخطأ كان هنا سابقاً: المفتاح في JSON هو "subtitle" وليس "subtitles"
        @JsonProperty("subtitle") val subtitles: List<ViuSubtitle>?
    )

    data class ViuSubtitle(
        @JsonProperty("name") val name: String?,
        @JsonProperty("code") val code: String?,
        @JsonProperty("iso_code") val isoCode: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("subtitle_url") val subtitleUrl: String?
    )




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
}
