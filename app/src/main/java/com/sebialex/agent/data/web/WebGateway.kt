package com.sebialex.agent.data.web

import com.sebialex.agent.domain.model.SearchHit

data class FetchedPage(val url: String, val title: String, val text: String)

/** Web search and retrieval. Every implementation talks to the real network. */
interface WebGateway {
    suspend fun search(query: String): List<SearchHit>
    suspend fun fetch(url: String): FetchedPage
}

/**
 * Uses TinyFish when the user has stored a key; otherwise direct HTTP (a plain GET for fetches,
 * DuckDuckGo's HTML endpoint for search). Errors are propagated so they show up as failed steps.
 * There is no offline or canned corpus.
 */
class RoutingWebGateway(
    private val keyProvider: () -> String?,
    private val direct: WebGateway = DirectWebClient(),
) : WebGateway {
    private fun live(): WebGateway = keyProvider()?.let { TinyFishClient(it) } ?: direct

    override suspend fun search(query: String): List<SearchHit> = live().search(query)

    override suspend fun fetch(url: String): FetchedPage = live().fetch(url)
}

fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url.substringAfter("://").substringBefore('/')
