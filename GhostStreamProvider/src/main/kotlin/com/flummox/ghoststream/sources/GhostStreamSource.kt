package com.flummox.ghoststream.sources

import com.lagacy.models.*

interface GhostStreamSource {
    val name: String
    val baseUrl: String
    
    suspend fun search(query: String): List<SearchResponse>
    suspend fun getMainPage(): List<HomePageList>
    suspend fun loadContent(id: String): LoadResponse?
    suspend fun loadLinks(contentId: String, extra: Map<String, String>?): List<EpisodeLoadResponse>
}
