package com.example

import com.lagacy.extension.ExtractorApi
import com.lagacy.extension.ExtractorApi.properties.*
import com.lagacy.extension.main.MainPageData
import com.lagacy.extension.main.MainPageRequest
import com.lagacy.extension.main.MainPageResponse
import com.lagacy.extension.models.*
import com.lagacy.extension.search.Search
import com.lagacy.extension.utils.AppUtils.notYetImplemented
import com.lagacy.extension.utils.Log

class ExampleProvider : ExtractorApi(), Search {
    override var name = "Example"
    override var mainUrl = "https://example.com"
    override val supportedTypes = setOf(Type.Movie, Type.TvSeries)
    override var requiresResources = false
    override var language = "en"
    override var iconUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/Hardcore_Logo.png"
    override val tvTypes = listOf(
        TvType.Movie,
        TvType.TvSeries
    )
    override var author = "Flummox"
    override val cloudstream = 3

    override fun getSearchResponse(query: String, page: Int): SearchResponse {
        notYetImplemented
        return SearchResponse(ArrayList(), false)
    }

    override fun getContent(path: String): ContentResponse {
        notYetImplemented
        return ContentResponse(
            data = ContentData(path, "", null, null, null, null, null, null),
            metadata = ContentMetadata(null, null, null)
        )
    }

    override fun getMainPage(page: MainPageRequest): MainPageResponse {
        notYetImplemented
        return MainPageResponse(
            list = listOf(
                MainPageData(
                    name = "Example Provider",
                    path = "",
                    list = ArrayList()
                )
            ),
            hasNext = false
        )
    }

    override fun getVideoSources(path: String): VideoSourceResponse {
        notYetImplemented
        return VideoSourceResponse(ArrayList())
    }
}
