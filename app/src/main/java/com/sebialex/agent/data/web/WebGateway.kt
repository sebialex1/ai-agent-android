package com.sebialex.agent.data.web

import com.sebialex.agent.domain.model.SearchHit

data class FetchedPage(val url: String, val title: String, val text: String)

/** Web search and retrieval. Backed by TinyFish when a key is configured. */
interface WebGateway {
    suspend fun search(query: String): List<SearchHit>
    suspend fun fetch(url: String): FetchedPage
}

/** Uses TinyFish when the user has stored a key; otherwise the deterministic offline corpus. */
class RoutingWebGateway(
    private val keyProvider: () -> String?,
    private val offline: WebGateway = SampleWeb,
) : WebGateway {
    private fun live(): WebGateway? = keyProvider()?.let { TinyFishClient(it) }

    override suspend fun search(query: String): List<SearchHit> =
        live()?.let { runCatching { it.search(query) }.getOrNull()?.takeIf { r -> r.isNotEmpty() } } ?: offline.search(query)

    override suspend fun fetch(url: String): FetchedPage =
        live()?.let { runCatching { it.fetch(url) }.getOrNull() } ?: offline.fetch(url)
}

fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url.substringAfter("://").substringBefore('/')
