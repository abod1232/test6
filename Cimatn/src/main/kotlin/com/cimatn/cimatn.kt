package com.cimatn

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

    override suspend fun load(url: String): LoadResponse {
        debugLog("Started loading: $url")

        // 1. منطق الأفلام
        if (url.contains("film-")) {
            debugLog("Detected Type: MOVIE 🎬")
            val newUrl = url.replace("www.cimatn.com", "cimatunisa.blogspot.com")
            debugLog("Redirecting to source: $newUrl")

            val doc = app.get(url).document
            val title = doc.select("h1.PostTitle").text().trim()
            val description = doc.select(".StoryArea p").text().trim()
            var posterUrl = fixPoster(doc.select("#poster img").attr("src"))
            if (posterUrl.isEmpty()) posterUrl = fixPoster(doc.select(".image img").attr("src"))
            val year = extractYear(doc)
            val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

            return newMovieLoadResponse(title, newUrl, TvType.Movie, null) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }

        // 2. منطق المسلسلات
        debugLog("Detected Type: SERIES 📺")
        val cleanUrl = url.substringBefore("?")
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

        // أ. البحث عن المواسم (Seasons Feed - JS)
        val feedMatch = Regex("""const\s+feedURL\s*=\s*['"]([^"']+)['"]""").find(htmlContent)
        if (feedMatch != null) {
            val feedUrlSuffix = feedMatch.groupValues[1]
            val feedUrl = if (feedUrlSuffix.startsWith("http")) feedUrlSuffix else "$mainUrl$feedUrlSuffix"
            val cleanFeedUrl = feedUrl.replace("?alt=json-in-script", "?alt=json&max-results=500")
            debugLog("Found JS Feed for seasons: $cleanFeedUrl")

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
                debugLog("Parsed ${seasonsList.size} seasons from JSON Feed")
            } catch (e: Exception) { 
                debugLog("Error parsing seasons JSON: ${e.message}")
            }
        }

        // ب. البحث في HTML إذا لم نجد Feed
        if (seasonsList.isEmpty()) {
            val htmlSeasons = doc.select(".allseasonss .Small--Box.Season a")
            if (htmlSeasons.isNotEmpty()) {
                htmlSeasons.forEach {
                    val sTitle = it.attr("title").ifEmpty { "Season" }
                    val sLink = it.attr("href")
                    if (sLink.isNotEmpty()) seasonsList.add(sTitle to sLink)
                }
                debugLog("Found ${seasonsList.size} seasons from HTML selectors")
            }
        }

        // ج. إذا لم توجد مواسم، نعتبر الصفحة الحالية هي الموسم الوحيد
        if (seasonsList.isEmpty()) {
            debugLog("No seasons found. Assuming single season (Current Page)")
            seasonsList.add("الموسم 1" to cleanUrl)
        }

        val allEpisodes = mutableListOf<Episode>()

        seasonsList.forEachIndexed { index, (sTitle, sLink) ->
            val seasonNum = index + 1
            debugLog("Processing Season $seasonNum: $sTitle ($sLink)")
            
            // جلب محتوى صفحة الموسم إذا كانت مختلفة عن الصفحة الحالية
            // هذا هو التصحيح الجوهري: ندخل صفحة الموسم لنستخرج الحلقات منها
            val seasonHtml = if (sLink == cleanUrl) htmlContent else app.get(sLink).text
            
            // 1. محاولة جلب الحلقات بالطرق المباشرة من صفحة الموسم (JS & HTML)
            var eps = getEpisodesDirect(seasonHtml, sLink, seasonNum)
            
            // 2. إذا لم نجد حلقات، نجرب طريقة البحث (Fallback)
            if (eps.isEmpty()) {
                debugLog("   -> No episodes found directly. Trying Fallback Feed Search...")
                val slug = sLink.substringAfterLast("/").substringBefore(".").replace("_9", "").replace("-s2", "").replace("-s1", "")
                debugLog("   -> Searching with slug: $slug")
                eps = getEpisodesFromSearchFeed(slug, seasonNum)
            }

            if (eps.isNotEmpty()) {
                debugLog("   -> Added ${eps.size} episodes to Season $seasonNum")
                allEpisodes.addAll(eps)
            } else {
                debugLog("   -> ❌ FAILED to find any episodes for Season $seasonNum")
            }
        }

        debugLog("Total episodes found: ${allEpisodes.size}")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = description
            this.tags = tags
        }
    }

    private fun getEpisodesDirect(htmlContent: String, pageUrl: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. البحث عن متغيرات JS (Ragouj style)
        val countMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+);""").find(htmlContent)
        val baseLinkMatch = Regex("""const\s+baseLink\s*=\s*['"]([^"']+)['"]""").find(htmlContent)

        if (countMatch != null && baseLinkMatch != null) {
            val count = countMatch.groupValues[1].toInt()
            val baseLink = baseLinkMatch.groupValues[1]
            debugLog("   -> Found JS variables: count=$count, base=$baseLink")

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
            return episodes
        }

        // 2. البحث عن روابط HTML (.allepcont .row a)
        val doc = org.jsoup.Jsoup.parse(htmlContent)
        val links = doc.select(".allepcont .row a")
        if (links.isNotEmpty()) {
            debugLog("   -> Found ${links.size} episodes via HTML selectors")
            links.forEach { link ->
                val title = link.select("h2").text().trim().ifEmpty { "Episode" }
                val href = link.attr("href")
                // استخراج رقم الحلقة من العنوان
                val epNum = Regex("""(\d+)""").findAll(title).lastOrNull()?.value?.toIntOrNull()

                if (href.isNotEmpty()) {
                    episodes.add(newEpisode(href) {
                        this.name = title
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
        }
        
        return episodes
    }

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
                
                // شرط مهم: التأكد أن الرابط هو حلقة وليس موسم
                // نستبعد الروابط التي لا تحتوي على مؤشرات الحلقات لتجنب التكرار
                if (l.contains(slug) && (l.contains("ep") || l.contains("hal9a") || t.contains("حلقة"))) {
                     val epNum = Regex("""(\d+)""").findAll(t).lastOrNull()?.value?.toIntOrNull()
                     
                     episodes.add(newEpisode(l) {
                         this.name = t
                         this.season = seasonNum
                         this.episode = epNum
                     })
                }
            }
            episodes.sortBy { it.episode }
        } catch (e: Exception) { 
            debugLog("   -> Error in Fallback Feed Search: ${e.message}")
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("loadLinks started for: $data")
        val doc = app.get(data).document
        val scriptContent = doc.select("script").joinToString(" ") { it.data() }

        var foundServer = false

        // 1. مصفوفة السيرفرات const servers
        val serverRegex = Regex("""const\s+servers\s*=\s*(\[\s*\{.*?\}\s*\])""", RegexOption.DOT_MATCHES_ALL)
        val match = serverRegex.find(scriptContent)

        if (match != null) {
            val jsonString = match.groupValues[1]
            val urlRegex = Regex("""url\s*:\s*['"](.*?)['"]""")
            urlRegex.findAll(jsonString).forEach { matchResult ->
                val serverUrl = matchResult.groupValues[1]
                debugLog("Found Server (JS Array): $serverUrl")
                loadExtractor(serverUrl, data, subtitleCallback, callback)
                foundServer = true
            }
        }

        // 2. Iframe مباشر
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("instagram") && !src.contains("googletagmanager")) {
                debugLog("Found Iframe: $src")
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
                debugLog("Decoded Secure Link: $decodedUrl")
                loadExtractor(decodedUrl, data, subtitleCallback, callback)
                foundServer = true
            } catch (e: Exception) { 
                debugLog("Failed to decode secure link: ${e.message}")
            }
        }

        if (!foundServer) {
            debugLog("No servers found on this page!")
        }

        return foundServer
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
