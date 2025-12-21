package com.syrialive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.JsUnpacker
import org.jsoup.nodes.Element

class SyriaLiveProvider : MainAPI() {
    override var mainUrl = "https://www.syria-live.tv"
    override var name = "Syria Live"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(TvType.Live, TvType.Movie)

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (!url.startsWith("http")) return "$mainUrl$url"
        return url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // 1. المباريات
        val matches = document.select(".match-container").mapNotNull { element ->
            val rightTeam = element.selectFirst(".right-team .team-name")?.text() ?: return@mapNotNull null
            val leftTeam = element.selectFirst(".left-team .team-name")?.text() ?: return@mapNotNull null
            val status = element.selectFirst(".date")?.text() ?: ""
            val time = element.selectFirst(".match-time")?.text() ?: ""
            val title = "$rightTeam vs $leftTeam"
            val href = element.selectFirst("a.ahmed")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst(".right-team img")?.attr("data-src")
                ?: element.selectFirst(".right-team img")?.attr("src")

            newLiveSearchResponse(title, fixUrl(href), TvType.Live) {
                this.posterUrl = fixUrl(poster ?: "")
                this.extraDescription = "$status | $time"
            }
        }

        if (matches.isNotEmpty()) {
            homePageList.add(HomePageList("مباريات اليوم", matches, isHorizontalImages = true))
        }

        // 2. الأخبار
        val news = document.select(".AY-PItem").mapNotNull { element ->
            val titleElement = element.selectFirst(".AY-PostTitle a") ?: return@mapNotNull null
            val title = titleElement.text()
            val href = titleElement.attr("href")
            val poster = element.selectFirst("img")?.attr("data-src")
                ?: element.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster ?: "")
            }
        }

        if (news.isNotEmpty()) {
            homePageList.add(HomePageList("آخر الأخبار", news))
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select(".AY-PItem").mapNotNull { element ->
            val titleElement = element.selectFirst(".AY-PostTitle a") ?: return@mapNotNull null
            val title = titleElement.text()
            val href = titleElement.attr("href")
            val poster = element.selectFirst("img")?.attr("data-src")
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster ?: "")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".EntryTitle")?.text() ?: document.selectFirst("h1")?.text() ?: "No Title"
        val poster = document.selectFirst(".teamlogo")?.attr("data-src")
            ?: document.selectFirst(".EntryHeader img")?.attr("src")
            ?: document.selectFirst(".post-thumb img")?.attr("src")

        val tableRows = document.select(".table-bordered tr")
        val isMatch = url.contains("/matches/") || tableRows.isNotEmpty()
        
        val desc = if (isMatch) {
             tableRows.joinToString("\n") { row ->
                "${row.select("th, td").first()?.text()}: ${row.select("td").last()?.text()}"
            }
        } else {
            document.select(".entry-content p").text()
        }

        return if (isMatch) {
            newLiveLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = desc
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = desc
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // البحث في أزرار السيرفرات (القنوات)
        document.select(".video-serv a").forEach { btn ->
            val serverName = btn.text()
            val serverLink = btn.attr("href")

            if (serverLink.isNotBlank()) {
                val fixedServerLink = fixUrl(serverLink)
                try {
                    // الدخول لصفحة السيرفر (مثل spoort.en-yalla.live)
                    // نستخدم Referer الصفحة الأصلية
                    val serverDoc = app.get(fixedServerLink, referer = data).document
                    
                    // البحث عن iframe داخل صفحة السيرفر
                    serverDoc.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src")
                        if (src.isNotBlank()) {
                            val fixedSrc = fixUrl(src)
                            
                            // التحقق مما إذا كان الرابط هو Wallplaster
                            if (fixedSrc.contains("wallplaster") || fixedSrc.contains("pjxvijl")) {
                                // استدعاء المستخرج الخاص بـ Wallplaster
                                val extractor = WallplasterExtractor()
                                extractor.getUrl(fixedSrc, fixedServerLink, subtitleCallback, callback)
                                foundLinks = true
                            } else {
                                // محاولة المستخرجات العامة لباقي السيرفرات
                                loadExtractor(fixedSrc, fixedServerLink, subtitleCallback, callback)
                                foundLinks = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return foundLinks
    }
}

// --- مستخرج خاص لفك تشفير Wallplaster ---
class WallplasterExtractor : ExtractorApi() {
    override val name = "Wallplaster"
    override val mainUrl = "https://wallplaster.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // جلب محتوى صفحة الـ Iframe مع الـ Referer الصحيح
            val response = app.get(url, referer = referer).text

            // البحث عن كود Packed JS
            // النمط يبحث عن: eval(function(p,a,c,k,e,d)...
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""")
            val packedMatch = packedRegex.find(response)

            if (packedMatch != null) {
                val packedCode = packedMatch.value
                // فك التشفير باستخدام أداة CloudStream المدمجة
                val unpackedCode = JsUnpacker(packedCode).unpack()

                if (!unpackedCode.isNullOrEmpty()) {
                    // البحث عن المتغير var src="..." أو var stream="..." داخل الكود المفكوك
                    // النمط: يبحث عن رابط يبدأ بـ http وينتهي بـ .m3u8
                    val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    val m3u8Match = m3u8Regex.find(unpackedCode)

                    if (m3u8Match != null) {
                        val streamUrl = m3u8Match.groupValues[1]
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "Wallplaster (HD)",
                                streamUrl,
                                ){
                                referer ?: url
                                Qualities.Unknown.value
                                isM3u8 = true
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
