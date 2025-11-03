package com.flummox.ghoststream.sources

import com.lagacy.models.*
import com.lagacy.utils.*

class AllMovieLandSource : GhostStreamSource {
    override val name = "AllMovieLand"
    override val baseUrl = "https://allmovieland.fun"

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun getMainPage(): List<HomePageList> {
        return listOf(
            HomePageList("Popular Movies", emptyList()),
            HomePageList("Latest Movies", emptyList())
        )
    }

    override suspend fun loadContent(id: String): LoadResponse? {
        return null
    }

    override suspend fun loadLinks(contentId: String, extra: Map<String, String>?): List<EpisodeLoadResponse> {
        return emptyList()
    }
}
