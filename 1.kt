package com.anime3rb

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class Anime3rb : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "ar"
    override val hasMainPage = true

    [span_3](start_span)[span_4](start_span)// تم ذكر CloudflareKiller في السورس للتعامل مع حماية الموقع[span_3](end_span)[span_4](end_span)
    private val cfKiller = CloudflareKiller()

    [span_5](start_span)// التعابير النمطية الموجودة حرفياً في ملف السورس[span_5](end_span)
    private val TITLE_EP_REGEX = Regex("""[^\d]+""")
    private val NON_DIGITS = Regex("""[^0-9]""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()

        [span_6](start_span)// جلب قائمة الأنميات المثبتة بناءً على الكلاسات الدقيقة من السورس[span_6](end_span)
        val pinnedList = document.select(".glide__slide:not(.glide__slide--clone) a.video-card").mapNotNull {
            it.toSearchResult()
        }
        if (pinnedList.isNotEmpty()) home.add(HomePageList("أنميات مثبتة", pinnedList))

        [span_7](start_span)// جلب أحدث الحلقات[span_7](end_span)
        val latestEpisodesList = document.select(".video-list a, a.video-card").mapNotNull {
            it.toSearchResult()
        }
        if (latestEpisodesList.isNotEmpty()) home.add(HomePageList("أحدث الحلقات", latestEpisodesList))

        return newHomePageResponse(home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        [span_8](start_span)// البحث في video-data كما هو موجود في السورس[span_8](end_span)
        val title = this.selectFirst(".video-data")?.text() ?: this.attr("title")
        val href = fixUrl(this.attr("href"))
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src") ?: "")

        if (title.isBlank()) return null

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        [span_9](start_span)val document = app.get("$mainUrl/search?q=$query").document //[span_9](end_span)
        return document.select("a.video-card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        [span_10](start_span)// الكلاسات الدقيقة المذكورة لجلب العنوان والقصة[span_10](end_span)
        val title = document.selectFirst("h3.title-name")?.text() ?: ""
        val posterUrl = document.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val plot = document.selectFirst("p.synopsis, meta[name='description']")?.attr("content") ?: document.selectFirst("p.synopsis")?.text()

        val episodes = document.select("a.video-card").mapNotNull { element ->
            val epHref = fixUrl(element.attr("href"))
            val epText = element.selectFirst(".video-data")?.text() ?: ""
            [span_11](start_span)// استخراج رقم الحلقة باستخدام NON_DIGITS[span_11](end_span)
            val epNum = NON_DIGITS.replace(epText, "").toIntOrNull()

            [span_12](start_span)[span_13](start_span)// استخدام newEpisode كما طلبت والموجودة في السورس[span_12](end_span)[span_13](end_span)
            newEpisode(epHref) {
                this.name = epText
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    // كلاس لتمرير بيانات الـ JSON التي يستخرجها السورس
    data class VideoSource(
        @JsonProperty("provider") val provider: String?,
        @JsonProperty("url") val url: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val htmlText = document.html()
        
        [span_14](start_span)// التعبير النمطي الخاص بسيرفر video.vid3rb.com والموجود حرفياً في السورس[span_14](end_span)
        val playerPattern = Regex("""https:(?:\\/|/){2}video\.vid3rb\.com(?:\\/|/)player(?:\\/|/)[^"']+""")
        
        [span_15](start_span)// البحث عن رابط المشغل سواء بالـ Regex أو بالـ iframe[span_15](end_span)
        var playerUrl = playerPattern.find(htmlText)?.value ?: document.selectFirst("iframe")?.attr("src")

        if (playerUrl != null) {
            playerUrl = fixUrl(playerUrl)
            val playerResponse = app.get(playerUrl, referer = mainUrl).document

            [span_16](start_span)// التعبير النمطي لاستخراج مصفوفة السيرفرات من الـ Javascript[span_16](end_span)
            val jsonPattern = Regex("""var\s+video_sources\s*=\s*(\[.*?\]);""")
            val jsonStr = jsonPattern.find(playerResponse.html())?.groupValues?.get(1)

            if (jsonStr != null) {
                [span_17](start_span)[span_18](start_span)// تحويل الـ JSON إلى كائنات باستخدام parseJson الموجودة في السورس[span_17](end_span)[span_18](end_span)
                val sources = parseJson<List<VideoSource>>(jsonStr)
                
                sources.forEach { source ->
                    val link = source.url
                    val providerName = source.provider ?: "Anime3rb"
                    
                    if (link != null) {
                        [span_19](start_span)[span_20](start_span)// إضافة newExtractorLink كما طلبت بناءً على السورس[span_19](end_span)[span_20](end_span)
                        callback(
                            newExtractorLink(
                                name = providerName,
                                source = name,
                                url = link,
                                referer = playerUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = link.contains(".m3u8") || link.contains(".mp4")
                            )
                        )
                    }
                }
            }
        }
        return true
    }
}

