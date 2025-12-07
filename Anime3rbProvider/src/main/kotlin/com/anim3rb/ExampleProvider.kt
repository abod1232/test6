package com.anime3rb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Anime3rb : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // الكوكيز (نفس كود بايثون)
    private val cookiesMap = mapOf(
        "_ga" to "GA1.1.225381945.1765098080",
        "darkMode" to "true",
        "cf_clearance" to "kIRyxYqJyBRws_vQn79r418e3u1p6ms68DZcPWCUuFw-1765098086-1.2.1.1-l7fvSjFOwjZtAyz3gxLppI.V14p1MjXEQqWwI4LVwxMuNPxCA0xnHz7t2gZ4hWxE0Bh5g8KJlvDs4EQbYLzCmeF1f.12VkyUDtdmLU2o_9YL5TdAgA_8B_FNTdlrYAC2Lk4ZGA4pDktAdJVS.WalJCHv8hyBRmISMDtuXonV9hZV9pGHBQy9uB4dGKmiqzgJr__1X0NvmDT6rlrHunsXIfIE1DKFXmhl5WZXv3Wxuw1FdERpmpgCGgWC_EG6MZPo",
        "XSRF-TOKEN" to "eyJpdiI6IlliUWNCeCtzSHYzR0VEcFFLVG1TUGc9PSIsInZhbHVlIjoiVmpjT3cxeWUvQ0F4U01sQjV2WTlZSlluU0p1Kys5MDZiNTJpOWNQQTgzaWIwZnZ6Qmx0WDdxVzFlcmJOSWNWN1AwQ2RqWm5RbWhnTU9GTDhqc0M1S0huS0VUT0FrenJmeXlpb0xuRDAyc0hIQzE5VDBvbjBMam13NGZ6KzRoYjgiLCJtYWMiOiIyM2NkOTU1MmViODk3ZDUzNzgzYjQyODdhNjI5MmQ0ODRlMWVkY2VjZjBkMDFmNGE0OTFmZTYyYjE1NTE3ODhmIiwidGFnIjoiIn0=",
        "anmy_aarb_session" to "eyJpdiI6Imlialh0TnRDUDVmQTBtaThOQ2JuQXc9PSIsInZhbHVlIjoiQXYya0lCaDJXb0Exa2pmazhRZ2l3L2luc2E4ZHBmbmFTWUN4UjdIZ09HMisrazV4Sk1jSUZEWWNCMjRHSmh5S25GYmE3TXhXZDRzZ3dYVzd1SldBVGE3bE52UE56YXo0dGdibGZGSld6MlpXNWhneDhaUFRhWGJhQ3YyQ2tkUGwiLCJtYWMiOiI5MzliOTYyMzIxMDVjMzNjYzNkNTQ3MzQzODMwM2ZmZDM0MWFhMTIzZmU3YjJjYzMwMDk5YTc3MTRmNTM3NmY2IiwidGFnIjoiIn0=",
        "watching-list" to "%7B%22boku-no-hero-academia-final-season%22%3A%7B%22video%22%3A10%2C%22time%22%3A11.032927%2C%22progress%22%3A0.007693222277231176%2C%22updated_at%22%3A1765098360666%7D%2C%22updated_at%22%3A1765098360666%7D",
    )

    private val cookieHeaderValue = cookiesMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private val myHeaders = mapOf(
        "Host" to "anime3rb.com",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "ar-EG,ar;q=0.9",
        "sec-fetch-site" to "none",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-dest" to "document",
        "upgrade-insecure-requests" to "1",
        "Cookie" to cookieHeaderValue
    )

    // ---------------------------------------------------------------
    // 1. الصفحة الرئيسية (Main Page)
    // ---------------------------------------------------------------

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val doc = app.get(request.data, headers = myHeaders).document
        val homeSets = mutableListOf<HomePageList>()

        // 1. الأنميات المثبتة (Pinned Anime)
        // موجودة في أول slider (glide) تحت عنوان "الأنميات المثبتة"
        val pinnedHeader = doc.select("h2:contains(الأنميات المثبتة)").firstOrNull()
        if (pinnedHeader != null) {
            // نعود للأب للعثور على السلايدر المجاور
            val pinnedContainer = pinnedHeader.parent()?.parent()?.parent()
            val pinnedList = pinnedContainer?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull {
                toSearchResult(it)
            }
            if (!pinnedList.isNullOrEmpty()) {
                homeSets.add(HomePageList("الأنميات المثبتة", pinnedList))
            }
        }

        // 2. أحدث الحلقات (Latest Episodes)
        // موجودة داخل div id="videos"
        val latestEpisodesList = doc.select("#videos a.video-card").mapNotNull {
            toSearchResult(it)
        }
        if (latestEpisodesList.isNotEmpty()) {
            homeSets.add(HomePageList("أحدث الحلقات", latestEpisodesList))
        }

        // 3. آخر الأنميات المضافة (Latest Added Anime)
        val addedHeader = doc.select("h3:contains(آخر الأنميات المضافة)").firstOrNull()
        if (addedHeader != null) {
            val addedContainer = addedHeader.parent()?.parent()?.parent()
            val addedList = addedContainer?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull {
                toSearchResult(it)
            }
            if (!addedList.isNullOrEmpty()) {
                homeSets.add(HomePageList("آخر الأنميات المضافة", addedList))
            }
        }

        return newHomePageResponse(homeSets)
    }

    // دالة مساعدة لتحويل عنصر HTML إلى نتيجة بحث
    // دالة مساعدة لتحويل عنصر HTML إلى نتيجة بحث
    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("h3.title-name").text().trim()
        val href = fixUrl(element.attr("href"))
        val posterUrl = element.select("img").attr("src")

        // استخراج النص (مثال: "الحلقة 10")
        val episodeText = element.select("p.number").text().trim()

        // استخراج الرقم فقط من النص
        val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl

            // التصحيح: نمرر false (مترجم) والرقم (Int) بدلاً من النص
            addDubStatus(false, episodeNum)
        }
    }

    // ---------------------------------------------------------------
    // 2. البحث (Search)
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url, headers = myHeaders).document

        return doc.select("a.simple-title-card").mapNotNull {
            val title = it.select("h4.text-lg").text().trim()
            val href = fixUrl(it.attr("href"))
            val posterUrl = it.select("img").attr("src")
            val typeText = it.select("div.details span.badge").text()

            val type = if (typeText.contains("Movie") || title.contains("Film")) TvType.AnimeMovie else TvType.Anime

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ---------------------------------------------------------------
    // 3. تحميل التفاصيل والحلقات (Load)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = myHeaders).document

        val title = doc.select("h1").text().replace("الحلقة \\d+".toRegex(), "").trim()
        val poster = doc.select("img[alt*='بوستر']").attr("src")
        val desc = doc.select("p.synopsis, meta[name='description']").attr("content")

        val episodes = doc.select(".videos-list a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val epText = element.select(".video-data span").text()
            val epNum = epText.replace(Regex("[^0-9]"), "").toIntOrNull()
            val epName = element.select(".video-data p").text()

            if (href.isEmpty()) return@mapNotNull null

            newEpisode(href) {
                this.name = if (epName.isNotBlank()) epName else epText
                this.episode = epNum
                this.posterUrl = element.select("img").attr("src")
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = desc
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------
    // 4. استخراج الروابط (Load Links)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = myHeaders).document

        // 1. استخراج سيرفر المشاهدة (Vid3rb Iframe)
        val iframeSrc = doc.select("iframe[src*='vid3rb.com']").attr("src")
        if (iframeSrc.isNotBlank()) {
            // Vid3rb مدعوم في Cloudstream (StreamHide), نرسل الرابط له
            loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
        }

        // 2. استخراج روابط التحميل المباشرة
        doc.select("a[href*='/download/']").forEach { linkElement ->
            val downloadUrl = linkElement.attr("href")
            val parentDiv = linkElement.parent()
            val qualityLabel = parentDiv?.select("label")?.text() ?: "Unknown"
            val qualityInt = getQualityFromName(qualityLabel)

            callback.invoke(
                newExtractorLink(
                    source = "Anime3rb Download",
                    name = "Anime3rb $qualityLabel",
                    url = downloadUrl,
                ) {
                    referer = data
                    quality = qualityInt
                    headers = myHeaders
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
            else -> Qualities.Unknown.value
        }
    }
}