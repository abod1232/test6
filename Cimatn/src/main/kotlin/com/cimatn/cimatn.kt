package com.cimatn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Base64
import org.jsoup.nodes.Document
import kotlin.text.ifEmpty
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils
class CimaTn : MainAPI() {
    override var mainUrl = "https://www.cimatn.com"
    override var name = "Cima Tn"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/search/label/أحدث الإضافات" to "أحدث الإضافات",
        "$mainUrl/search/label/أفلام تونسية" to "أفلام تونسية",
        "$mainUrl/search/label/مسلسلات تونسية" to "مسلسلات تونسية",
        "$mainUrl/search/label/رمضان2025" to "رمضان 2025",
        "$mainUrl/search/label/دراما" to "دراما",
        "$mainUrl/search/label/كوميديا" to "كوميديا",
        "$mainUrl/search/label/أكشن" to "أكشن"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}?max-results=20"
        }

        val doc = app.get(url).document
        val home = doc.select("#holder a.itempost").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("#item-name").text().trim()
        val url = element.attr("href")
        var posterUrl = element.select("img").attr("src")

        posterUrl = posterUrl.replace(Regex("/s\\d+-c/"), "/w600/")
            .replace(Regex("/w\\d+/"), "/w600/")
            .replace(Regex("/s\\d+/"), "/s1600/")

        val year = element.select(".entry-label").text().trim().toIntOrNull()

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url).document
        return doc.select("#holder a.itempost").mapNotNull { toSearchResult(it) }
    }

    // =========================================================================
    // دالة Load مع سجلات تتبع (Logging) ومنطق مطابق للبايثون
    // =========================================================================
    // =========================================================================
    // دالة Load المعدلة (تقوم بتبديل رابط الفيلم، وتجلب حلقات المسلسل)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        debugLog("Load Function Started: $url")

        // جلب صفحة المعلومات الأصلية لاستخراج البيانات (Title, Poster, Year)
        // نستخدم الرابط الأصلي لجلب المعلومات لأنه يحتوي على التفاصيل
        val cleanUrl = url.substringBefore("?")
        val response = app.get(cleanUrl)
        val doc = response.document
        val htmlContent = response.text

        val title = doc.select("h1.PostTitle").text().trim()
        val description = doc.select(".StoryArea p").text().trim()

        var posterUrl = doc.select("#poster img").attr("src")
        if (posterUrl.isEmpty()) posterUrl = doc.select(".image img").attr("src")
        posterUrl = fixPoster(posterUrl)

        val year = extractYear(doc)
        val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

        // -----------------------------------------------------------
        // 1. معالجة الأفلام (Movie Logic)
        // -----------------------------------------------------------
        if (url.contains("film-")) {
            debugLog("Type: MOVIE detected 🎬")

            // التغيير الجوهري: استبدال الدومين ليكون هو رابط التشغيل المباشر
            // هذا الرابط سيتم تمريره تلقائياً إلى loadLinks عند الضغط على "مشاهدة"
            val watchUrl = url.replace("www.cimatn.com", "cimatunisa.blogspot.com")
            debugLog("Watch URL set to: $watchUrl")

            // نمرر watchUrl بدلاً من url في المعامل الثاني
            return newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }

        // -----------------------------------------------------------
        // 2. معالجة المسلسلات (Series Logic)
        // -----------------------------------------------------------
        debugLog("Type: SERIES detected 📺")
        val seasonsList = mutableListOf<Pair<String, String>>()

        // أ. البحث عن المواسم في كود JS (const feedURL)
        val feedMatch = Regex("""const\s+feedURL\s*=\s*['"]([^"']+)['"]""").find(htmlContent)
        if (feedMatch != null) {
            val feedUrlSuffix = feedMatch.groupValues[1]
            val feedUrl = if (feedUrlSuffix.startsWith("http")) feedUrlSuffix else "$mainUrl$feedUrlSuffix"
            val cleanFeedUrl = feedUrl.replace("?alt=json-in-script", "?alt=json&max-results=500")
            debugLog("Found Season Feed: $cleanFeedUrl")

            try {
                val feedJson = app.get(cleanFeedUrl).text
                val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)
                feedData.feed?.entry?.forEach { entry ->
                    val sTitle = entry.title?.t ?: "Season"
                    val sLink = entry.link?.find { it.rel == "alternate" }?.href
                    if (sLink != null) {
                        seasonsList.add(sTitle to sLink)
                    }
                }
            } catch (e: Exception) {
                debugLog("Error parsing seasons: ${e.message}")
            }
        }

        // ب. البحث في HTML إذا لم نجد Feed
        if (seasonsList.isEmpty()) {
            doc.select(".allseasonss .Small--Box.Season a").forEach {
                val sTitle = it.attr("title").ifEmpty { "Season" }
                val sLink = it.attr("href")
                if (sLink.isNotEmpty()) seasonsList.add(sTitle to sLink)
            }
        }

        // ج. إذا لم توجد مواسم، الصفحة الحالية هي الموسم 1
        if (seasonsList.isEmpty()) {
            seasonsList.add("الموسم 1" to cleanUrl)
        }

        val allEpisodes = mutableListOf<Episode>()

        // د. جلب الحلقات
        seasonsList.forEachIndexed { index, (_, seasonUrl) ->
            val seasonNum = index + 1
            debugLog("Extracting episodes from Season $seasonNum")

            val seasonResponse = app.get(seasonUrl)
            val seasonHtml = seasonResponse.text
            val seasonDoc = seasonResponse.document

            // نفس منطق المسلسل بدون مواسم
            var episodes = getEpisodesDirect(
                seasonHtml,
                seasonUrl,
                seasonNum
            )

            if (episodes.isEmpty()) {
                val slug = getSlugFromUrl(seasonUrl)
                episodes = getEpisodesFromSearchFeed(slug, seasonNum)
            }

            if (episodes.isNotEmpty()) {
                allEpisodes.addAll(episodes)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = description
            this.tags = tags
        }
    }

    // ========================================================
    // الدوال المساعدة (استخراج الحلقات)
    // ========================================================

    private fun getEpisodesDirect(htmlContent: String, pageUrl: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. استخراج من متغيرات JS (مثل Ragouj)
        val countMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+);""").find(htmlContent)
        val baseLinkMatch = Regex("""const\s+baseLink\s*=\s*['"]([^"']+)['"]""").find(htmlContent)

        if (countMatch != null && baseLinkMatch != null) {
            val count = countMatch.groupValues[1].toInt()
            val baseLink = baseLinkMatch.groupValues[1]

            for (i in 1..count) {
                val fullLink = when {
                    baseLink.startsWith("http") -> "$baseLink$i.html"
                    baseLink.startsWith("/") -> "$mainUrl$baseLink$i.html"
                    else -> "$mainUrl/p/${baseLink.removePrefix("/")}$i.html"
                }

                episodes.add(newEpisode(fullLink) {
                    this.name = "الحلقة $i"
                    this.season = seasonNum
                    this.episode = i
                })
            }
            return episodes // إذا نجحت JS نرجع النتائج فوراً
        }

        // 2. استخراج من روابط HTML Class (.allepcont)
        val doc = org.jsoup.Jsoup.parse(htmlContent)
        val links = doc.select(".allepcont .row a")

        links.forEach { link ->
            val epName = link.select("h2").text().trim().ifEmpty { "Episode" }
            val epUrl = link.attr("href")
            // استخراج الرقم من الاسم
            val epNum = Regex("""(\d+)""").findAll(epName).lastOrNull()?.value?.toIntOrNull()

            if (epUrl.isNotEmpty()) {
                episodes.add(newEpisode(epUrl) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
        }

        return episodes
    }

    private suspend fun getEpisodesFromSearchFeed(slug: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        // البحث باستخدام max-results=100 لضمان جلب كل الحلقات
        val pageFeedUrl = "$mainUrl/feeds/pages/default?alt=json&max-results=100&q=$slug"

        try {
            val feedJson = app.get(pageFeedUrl).text
            val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)

            feedData.feed?.entry?.forEach { e ->
                val l = e.link?.find { it.rel == "alternate" }?.href ?: ""
                val t = e.title?.t ?: "Episode"

                // شرط مخفف: يجب أن يحتوي الرابط على اسم المسلسل (slug)
                if (l.contains(slug)) {
                    // محاولة استخراج رقم الحلقة من الرابط أولاً ثم العنوان
                    val epNum = Regex("""(\d+)\.html""").find(l)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\d+""").findAll(t).lastOrNull()?.value?.toIntOrNull()

                    episodes.add(newEpisode(l) {
                        this.name = t
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
            episodes.sortBy { it.episode }
        } catch (e: Exception) {
            debugLog("Feed Search Error: ${e.message}")
        }
        return episodes
    }

    // ========================================================
    // دوال مساعدة أخرى
    // ========================================================

    private fun debugLog(msg: String) {
        println("CimaTnDebug: $msg")
    }

    private fun fixPoster(url: String): String {
        return url.replace(Regex("/s\\d+-c/"), "/w600/")
            .replace(Regex("/w\\d+/"), "/w600/")
            .replace(Regex("/s\\d+/"), "/s1600/")
    }

    private fun extractYear(doc: Element): Int? {
        return doc.select("ul.RightTaxContent li:contains(تاريخ اصدار)").text()
            .replace(Regex("[^0-9]"), "")
            .toIntOrNull()
    }

    // Data Classes for JSON Parsing
    data class BloggerFeed(@JsonProperty("feed") val feed: FeedData? = null)
    data class FeedData(@JsonProperty("entry") val entry: List<FeedEntry>? = null)
    data class FeedEntry(
        @JsonProperty("title") val title: FeedTitle? = null,
        @JsonProperty("link") val link: List<FeedLink>? = null,
        @JsonProperty("media\$thumbnail") val mediaThumbnail: FeedMedia? = null
    )
    data class FeedTitle(@JsonProperty("\$t") val t: String? = null)
    data class FeedLink(
        @JsonProperty("rel") val rel: String? = null,
        @JsonProperty("href") val href: String? = null
    )
    data class FeedMedia(@JsonProperty("url") val url: String? = null)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // الطريقة الأولى: البحث عن متغير servers في الجافاسكريبت (كما يظهر في الكود المصدري)
        // const servers = [ { name: '...', url: '...' }, ... ];
        val scriptContent = doc.select("script").joinToString(" ") { it.data() }

        val serverRegex = Regex("""const\s+servers\s*=\s*(\[\s*\{.*?\}\s*\])""", RegexOption.DOT_MATCHES_ALL)
        val match = serverRegex.find(scriptContent)

        if (match != null) {
            val jsonString = match.groupValues[1]
            try {
                // تنظيف JSON (أحياناً تكون المفاتيح بدون علامات اقتباس في JS)
                // في هذا الموقع، يبدو الـ JS نظيفاً، لكن نستخدم Regex بسيط لاستخراج الروابط
                val urlRegex = Regex("""url\s*:\s*['"](.*?)['"]""")
                val urls = urlRegex.findAll(jsonString).map { it.groupValues[1] }.toList()

                urls.forEach { serverUrl ->
                    loadExtractor(serverUrl, data, subtitleCallback, callback)
                }
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // الطريقة الثانية: البحث عن iframe مباشرة (للحالات البسيطة)
        doc.select("div.WatchIframe iframe").attr("src").let { iframeUrl ->
            if (iframeUrl.isNotEmpty()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // الطريقة الثالثة: زر المشاهدة (قد يحتوي على data-secure-url مشفر)
        val secureUrl = doc.select(".BTNSDownWatch a.watch").attr("data-secure-url")
        if (secureUrl.isNotEmpty() && secureUrl != "#") {
            // فك التشفير البسيط الموجود في كود الموقع
            // let clean = encoded.slice(1, -1).split('').reverse().join('');
            try {
                val clean = secureUrl.substring(1, secureUrl.length - 1).reversed()
                val decodedUrl = String(android.util.Base64.decode(clean, android.util.Base64.DEFAULT))
                loadExtractor(decodedUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                // فشل فك التشفير
            }
        }

        return true
    }
    private fun extractEpisodesFromHtmlOrJs(doc: Document, html: String, pageUrl: String): MutableList<Pair<String, String>> {
        val episodes = mutableListOf<Pair<String, String>>()
        val domain = "https://${java.net.URI(pageUrl).host}"

        // 1. محاولة JS (const totalEpisodes)
        try {
            val countMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+);""").find(html)
            val baseLinkMatch = Regex("""const\s+baseLink\s*=\s*"([^"]+)";""").find(html)

            if (countMatch != null && baseLinkMatch != null) {
                val count = countMatch.groupValues[1].toInt()
                var baseLink = baseLinkMatch.groupValues[1]

                for (i in 1..count) {
                    val fullLink = if (baseLink.startsWith("http")) {
                        "$baseLink$i.html"
                    } else {
                        if (baseLink.startsWith("/")) baseLink = baseLink.substring(1)
                        "$domain/p/$baseLink$i.html"
                    }
                    episodes.add(Pair("الحلقة $i", fullLink))
                }
                return episodes
            }
        } catch (e: Exception) {}

        // 2. محاولة HTML (allepcont)
        val links = doc.select(".allepcont .row a")
        for (link in links) {
            val title = link.select("h2").text().ifEmpty { "Episode" }
            val href = link.attr("href")
            if (href.isNotEmpty()) {
                episodes.add(Pair(title, href))
            }
        }
        return episodes
    }

    private fun getSlugFromUrl(url: String): String {
        return try {
            val filename = url.substringAfterLast("/").replace(".html", "")
            filename.replace(Regex("[_0-9]+$"), "").replace(Regex("[-_]s\\d+"), "")
        } catch (e: Exception) { "" }
    }
}