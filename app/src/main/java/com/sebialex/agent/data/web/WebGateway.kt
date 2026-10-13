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
    /** Marks a failing TinyFish key and returns the next stored one (null when none is left). */
    private val rotate: (failed: String, reason: String) -> String? = { _, _ -> null },
    private val direct: WebGateway = DirectWebClient(),
) : WebGateway {
    override suspend fun search(query: String): List<SearchHit> = withKeys { it.search(query) }

    override suspend fun fetch(url: String): FetchedPage = withKeys { it.fetch(url) }

    /** TinyFish with the active key; a key error (401/402/403/429) moves on to the next stored key. */
    private suspend fun <T> withKeys(block: suspend (WebGateway) -> T): T {
        var key = keyProvider() ?: return block(direct)
        val tried = HashSet<String>()
        while (true) {
            tried += key
            try {
                return block(TinyFishClient(key))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                val message = e.message.orEmpty()
                if (!com.sebialex.agent.domain.agent.KeyPool.isKeyFailure(message)) throw e
                val next = rotate(key, message)
                if (next == null || next in tried) throw e
                key = next
            }
        }
    }
}

fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url.substringAfter("://").substringBefore('/')
