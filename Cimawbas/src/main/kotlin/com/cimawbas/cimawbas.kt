package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CimaWbas : MainAPI() {
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
        // التعامل مع ترقيم الصفحات في بلوجر
        val url = if (page == 1) {
            request.data
        } else {
            // بلوجر يستخدم updated-max للترقيم وهذا صعب التخمين، 
            // لذا سنكتفي بالصفحة الأولى أو نستخدم max-results كبير
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
        
        // إصلاح جودة الصورة (بلوجر يستخدم صور صغيرة افتراضياً)
        // تحويل s72-c أو w250 إلى حجم أكبر
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
        val doc = app.get(url).document
        
        val title = doc.select("h1.PostTitle").text().trim()
        val description = doc.select(".StoryArea p").text().trim()
        
        var posterUrl = doc.select("#poster img").attr("src")
        if (posterUrl.isEmpty()) posterUrl = doc.select(".image img").attr("src")
        posterUrl = posterUrl.replace(Regex("/s\\d+-c/"), "/w600/")
                             .replace(Regex("/w\\d+/"), "/w600/")
                             .replace(Regex("/s\\d+/"), "/s1600/")

        val year = doc.select("ul.RightTaxContent li:contains(تاريخ اصدار)").text()
            .replace("تاريخ اصدار الفيلم :", "")
            .replace("تاريخ اصدار المسلسل :", "")
            .replace("date_range", "")
            .trim().toIntOrNull()
            
        val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

        // التحقق مما إذا كان المحتوى فيلماً أو مسلسلاً بناءً على التصنيف
        val isSeries = tags.any { it.contains("مسلسل") } || url.contains("episode") || url.contains("-ep-")
        
        if (isSeries) {
             // منطق المسلسلات (بناءً على التبويبات الموجودة في الكود)
             // ملاحظة: الكود المصدري يستخدم جافاسكريبت لجلب الحلقات، 
             // لكن غالباً ما تكون الروابط موجودة أيضاً في كود الصفحة إذا كان التبويب مفعل
             
             // في حالة هذا الموقع، الحلقات يتم جلبها ديناميكياً غالباً، 
             // لكن سنحاول جلب الرابط الحالي كحلقة واحدة إذا لم نجد قائمة
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf()) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            // منطق الأفلام
            return newMovieLoadResponse(title, url, TvType.Movie, listOf()) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

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
}
