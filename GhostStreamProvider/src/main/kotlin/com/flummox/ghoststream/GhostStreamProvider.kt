package com.flummox.ghoststream

import com.flummox.ghoststream.sources.*
import com.lagacy.extensions.*
import com.lagacy.models.*
import com.lagacy.utils.*

class GhostStreamProvider : MainAPI() {
    override var mainUrl = "https://ghoststream.com"
    override var name = "GhostStream"
    override var lang = "en"
    
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val sources = listOf(
        AllMovieLandSource()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allLists = mutableListOf<HomePageList>()
        
        for (source in sources) {
            try {
                val sourceLists = source.getMainPage()
                if (sourceLists.isNotEmpty()) {
                    allLists.addAll(sourceLists)
                    break
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        return HomePageResponse(allLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        for (source in sources) {
            try {
                val results = source.search(query)
                if (results.isNotEmpty()) {
                    return results
                }
            } catch (e: Exception) {
                continue
            }
        }
        return emptyList()
    }

    override suspend fun loadContent(id: String): LoadResponse? {
        val parts = id.split("|")
        if (parts.size == 2) {
            val sourceIndex = parts[0].toIntOrNull()
            val contentId = parts[1]
            
            if (sourceIndex != null && sourceIndex in sources.indices) {
                return sources[sourceIndex].loadContent(contentId)
            }
        }
        return null
    }

    override suspend fun loadLinks(contentId: String, extra: Map<String, String>?): List<EpisodeLoadResponse> {
        val parts = contentId.split("|")
        if (parts.size == 2) {
            val sourceIndex = parts[0].toIntOrNull()
            val actualContentId = parts[1]
            
            if (sourceIndex != null && sourceIndex in sources.indices) {
                return sources[sourceIndex].loadLinks(actualContentId, extra)
            }
        }
        return emptyList()
    }
}
