package com.cimawbas

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Base64

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
        val url = if (page == 1) request.data else "${request.data}?max-results=20"
        val doc = app.get(url).document
        val home = doc.select("#holder a.itempost").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("#item-name").text().trim()
        val url = element.attr("href")
        var posterUrl = element.select("img").attr("src")
        posterUrl = fixPoster(posterUrl)
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
    // دالة Load (المحسنة للمسلسلات والأفلام)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        debugLog("🔵 Load Function Started: $url")
        val cleanUrl = url.substringBefore("?")

        // -----------------------------------------------------------
        // 1. منطق الأفلام
        // -----------------------------------------------------------
        if (cleanUrl.contains("film-")) {
            debugLog("🎬 Type: MOVIE detected")
            val watchUrl = cleanUrl.replace("www.cimatn.com", "cimatunisa.blogspot.com")
            debugLog("✅ Redirecting to: $watchUrl")

            val doc = app.get(cleanUrl).document
            val title = doc.select("h1.PostTitle").text().trim()
            val description = doc.select(".StoryArea p").text().trim()
            var posterUrl = doc.select("#poster img").attr("src")
            if (posterUrl.isEmpty()) posterUrl = doc.select(".image img").attr("src")
            posterUrl = fixPoster(posterUrl)
            val year = extractYear(doc)
            val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

            return newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }

        // -----------------------------------------------------------
        // 2. منطق المسلسلات
        // -----------------------------------------------------------
        debugLog("📺 Type: SERIES detected")
        val response = app.get(cleanUrl)
        val htmlContent = response.text
        val doc = response.document

        val title = doc.select("h1.PostTitle").text().trim()
        val description = doc.select(".StoryArea p").text().trim()
        var posterUrl = fixPoster(doc.select("#poster img").attr("src"))
        if (posterUrl.isEmpty()) posterUrl = fixPoster(doc.select(".image img").attr("src"))
        val year = extractYear(doc)
        val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

        val seasonsList = mutableListOf<Pair<String, String>>()

        // أ. البحث عن المواسم (JS Feed)
        val feedMatch = Regex("""const\s+feedURL\s*=\s*['"]([^"']+)['"]""").find(htmlContent)
        if (feedMatch != null) {
            val feedUrlSuffix = feedMatch.groupValues[1]
            val feedUrl = if (feedUrlSuffix.startsWith("http")) feedUrlSuffix else "$mainUrl$feedUrlSuffix"
            val cleanFeedUrl = feedUrl.replace("?alt=json-in-script", "?alt=json&max-results=500")
            
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
                debugLog("✅ Parsed ${seasonsList.size} seasons from JSON")
            } catch (e: Exception) { debugLog("Season parsing error: ${e.message}") }
        }

        // ب. البحث عن المواسم (HTML)
        if (seasonsList.isEmpty()) {
            doc.select(".allseasonss .Small--Box.Season a").forEach {
                val sTitle = it.attr("title").ifEmpty { "Season" }
                val sLink = it.attr("href")
                if (sLink.isNotEmpty()) seasonsList.add(sTitle to sLink)
            }
        }

        // ج. حالة موسم واحد
        if (seasonsList.isEmpty()) {
            seasonsList.add("الموسم 1" to cleanUrl)
        }

        val allEpisodes = mutableListOf<Episode>()

        // د. معالجة كل موسم
        seasonsList.forEachIndexed { index, (sTitle, sLink) ->
            val seasonNum = index + 1
            debugLog("🔄 Processing Season $seasonNum: $sTitle")
            
            val seasonHtml = if (sLink == cleanUrl) htmlContent else app.get(sLink).text
            
            // 1. محاولة مباشرة (JS & HTML)
            var eps = getEpisodesDirect(seasonHtml, sLink, seasonNum)
            
            // 2. إذا فشل الاستخراج المباشر، نستخدم البحث الاحتياطي (Fallback)
            // هذا الجزء هو الذي سيحل مشكلة Flash Back
            if (eps.isEmpty()) {
                debugLog("   -> No episodes found directly. Trying Feed Search...")
                // استخراج slug من الرابط (اسم المسلسل)
                val slug = sLink.substringAfterLast("/").substringBefore(".").replace("_9", "").replace("-s2", "").replace("-s1", "")
                eps = getEpisodesFromSearchFeed(slug, seasonNum)
            }

            if (eps.isNotEmpty()) {
                debugLog("✅ Found ${eps.size} episodes in Season $seasonNum")
                allEpisodes.addAll(eps)
            } else {
                debugLog("❌ FAILED to find episodes in Season $seasonNum")
                if (seasonsList.size == 1) printLargeLog(seasonHtml) // طباعة HTML للتحليل إذا فشل كل شيء
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
    // دالة استخراج الحلقات (المباشرة)
    // ========================================================
    private fun getEpisodesDirect(htmlContent: String, pageUrl: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. JS Variables (Ragouj Style)
        val countMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+);""").find(htmlContent)
        val baseLinkMatch = Regex("""const\s+baseLink\s*=\s*['"]([^"']+)['"]""").find(htmlContent)

        if (countMatch != null && baseLinkMatch != null) {
            val count = countMatch.groupValues[1].toInt()
            val baseLink = baseLinkMatch.groupValues[1]
            val domain = "https://${java.net.URI(pageUrl).host}"

            for (i in 1..count) {
                val fullLink = when {
                    baseLink.startsWith("http") -> "$baseLink$i.html"
                    baseLink.startsWith("/") -> "$domain$baseLink$i.html"
                    else -> "$domain/p/${baseLink.removePrefix("/")}$i.html"
                }
                
                episodes.add(newEpisode(fullLink) {
                    this.name = "الحلقة $i"
                    this.season = seasonNum
                    this.episode = i
                })
            }
            return episodes
        }

        // 2. HTML Selectors
        val doc = org.jsoup.Jsoup.parse(htmlContent)
        val selectors = listOf(
            ".allepcont .row a",          
            ".EpisodesList a",            
            "#EpisodesList a",            
            ".episodes-container a",
            "div[class*='Episodes'] a",
            ".post-body a[href*='-ep-']",
            ".post-body a[href*='hal9a']"
        )

        for (selector in selectors) {
            val links = doc.select(selector)
            if (links.isNotEmpty()) {
                links.forEach { link ->
                    val epName = link.select("h2").text().trim().ifEmpty { link.text().trim() }.ifEmpty { "Episode" }
                    val epUrl = link.attr("href")
                    
                    val epNum = Regex("""(\d+)""").findAll(epName).lastOrNull()?.value?.toIntOrNull()

                    if (epUrl.isNotEmpty() && !epUrl.contains("#") && epUrl != pageUrl) {
                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
                if (episodes.isNotEmpty()) break
            }
        }
        
        return episodes
    }

    // ========================================================
    // دالة البحث الاحتياطي (Feed Search)
    // ========================================================
    private suspend fun getEpisodesFromSearchFeed(slug: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val pageFeedUrl = "$mainUrl/feeds/pages/default?alt=json&max-results=100&q=$slug"
        debugLog("   -> Fetching Feed: $pageFeedUrl")
        
        try {
            val feedJson = app.get(pageFeedUrl).text
            val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)
            feedData.feed?.entry?.forEach { e ->
                val l = e.link?.find { it.rel == "alternate" }?.href ?: ""
                val t = e.title?.t ?: "Episode"
                
                // شرط: الرابط يحتوي على slug ويحتوي على مؤشرات الحلقة
                if (l.contains(slug) && (l.contains("ep") || l.contains("hal9a"))) {
                     val epNum = Regex("""(\d+)""").findAll(t).lastOrNull()?.value?.toIntOrNull()
                     
                     episodes.add(newEpisode(l) {
                         this.name = t
                         this.season = seasonNum
                         this.episode = epNum
                     })
                }
            }
            episodes.sortBy { it.episode }
        } catch (e: Exception) { debugLog("Feed Error: ${e.message}") }
        return episodes
    }

    // ========================================================
    // دالة LoadLinks
    // ========================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("loadLinks started: $data")
        val doc = app.get(data).document
        val scriptContent = doc.select("script").joinToString(" ") { it.data() }
        var foundServer = false

        // 1. مصفوفة const servers
        val serverRegex = Regex("""const\s+servers\s*=\s*(\[\s*\{.*?\}\s*\])""", RegexOption.DOT_MATCHES_ALL)
        val match = serverRegex.find(scriptContent)

        if (match != null) {
            val jsonString = match.groupValues[1]
            val urlRegex = Regex("""url\s*:\s*['"](.*?)['"]""")
            urlRegex.findAll(jsonString).forEach { matchResult ->
                val serverUrl = matchResult.groupValues[1]
                debugLog("Found Server: $serverUrl")
                loadExtractor(serverUrl, data, subtitleCallback, callback)
                foundServer = true
            }
        }

        // 2. Iframe مباشر
        doc.select("div.WatchIframe iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("instagram")) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundServer = true
            }
        }
        
        // 3. زر المشاهدة المشفر
        val secureUrl = doc.select(".BTNSDownWatch a.watch").attr("data-secure-url")
        if (secureUrl.isNotEmpty() && secureUrl != "#") {
            try {
                val clean = secureUrl.substring(1, secureUrl.length - 1).reversed()
                val decodedUrl = String(Base64.decode(clean, Base64.DEFAULT))
                loadExtractor(decodedUrl, data, subtitleCallback, callback)
                foundServer = true
            } catch (e: Exception) { }
        }

        return foundServer
    }

    private fun printLargeLog(content: String) {
        if (content.length > 4000) {
            println("CimaTnDebug: HTML DUMP PART 1:")
            println(content.substring(0, 4000))
            printLargeLog(content.substring(4000))
        } else {
            println(content)
        }
    }

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
}
