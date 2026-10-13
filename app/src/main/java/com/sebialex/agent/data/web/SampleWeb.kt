package com.sebialex.agent.data.web

import com.sebialex.agent.domain.model.SearchHit

/** Deterministic offline web corpus used when no TinyFish key is configured. */
object SampleWeb : WebGateway {

    data class Page(val url: String, val title: String, val snippet: String, val keywords: Set<String>, val body: String)

    val pages = listOf(
        Page(
            "https://docs.tinyfish.ai/search-api", "Search API - TinyFish",
            "Search the web and get structured results: titles, snippets and URLs ready for LLM consumption.",
            setOf("tinyfish", "search", "web", "api", "agent", "results"),
            "# Search API\nGET https://api.search.tinyfish.ai?query=...\nAll requests require an X-API-Key header. Results include title, snippet and url. Use location and language for geo-targeting.",
        ),
        Page(
            "https://docs.tinyfish.ai/fetch-api", "Fetch API - TinyFish",
            "Fetch URLs and extract clean text. Renders JavaScript-heavy pages and returns Markdown.",
            setOf("tinyfish", "fetch", "read", "page", "markdown", "extract", "web"),
            "# Fetch API\nPOST https://api.fetch.tinyfish.ai with {\"urls\": [...]}. Up to 10 URLs per request. format defaults to markdown. Failed URLs are reported in errors[].",
        ),
        Page(
            "https://developer.android.com/privacy-and-security/keystore", "Android Keystore system",
            "The Android Keystore system lets you store cryptographic keys in a container to make them more difficult to extract.",
            setOf("android", "keystore", "security", "key", "encrypted", "secure", "storage", "api"),
            "# Android Keystore system\nKey material never enters the application process. Keys can be bound to secure hardware (TEE or StrongBox). Use EncryptedSharedPreferences with a MasterKey for small secrets such as API tokens.",
        ),
        Page(
            "https://developer.android.com/develop/ui/compose/lists", "Lists and grids | Jetpack Compose",
            "LazyColumn and LazyRow only compose and lay out items visible in the viewport.",
            setOf("compose", "android", "list", "lazycolumn", "ui", "chat", "timeline"),
            "# Lists and grids\nUse LazyColumn with stable keys for chat timelines. animateItem() animates placement changes. Use reverseLayout for bottom-anchored chats.",
        ),
        Page(
            "https://github.com/termux/termux-app", "termux/termux-app - GitHub",
            "Termux - a terminal emulator application for Android OS extendible by a variety of packages.",
            setOf("termux", "terminal", "shell", "android", "sandbox", "linux", "packages", "github"),
            "# Termux\nTermux combines terminal emulation with a Linux package collection. Packages install into \$PREFIX inside the app's private data directory, isolating them from the rest of the system.",
        ),
        Page(
            "https://github.com/termux/termux-packages", "termux/termux-packages - GitHub",
            "Build scripts and patches for the packages available in Termux.",
            setOf("termux", "packages", "pkg", "install", "build", "github"),
            "# termux-packages\nAll packages are cross-compiled for Android with a custom prefix. Use pkg install to add packages.",
        ),
        Page(
            "https://source.android.com/docs/security/app-sandbox", "Application Sandbox | Android Open Source Project",
            "Android assigns a unique UID to each app and runs it in its own process, forming a kernel-level sandbox.",
            setOf("android", "sandbox", "security", "process", "isolation", "terminal", "architecture"),
            "# Application Sandbox\nEach app gets a unique Linux UID. SELinux enforces mandatory access control. Apps cannot read each other's private files.",
        ),
        Page(
            "https://openrouter.ai/docs/quickstart", "OpenRouter Quickstart",
            "OpenRouter provides a unified OpenAI-compatible API to hundreds of models.",
            setOf("openrouter", "llm", "model", "api", "chat", "completions", "ai"),
            "# Quickstart\nPOST https://openrouter.ai/api/v1/chat/completions with Authorization: Bearer <key>. Supports tool calling with the OpenAI tools schema.",
        ),
        Page(
            "https://kotlinlang.org/docs/coroutines-overview.html", "Coroutines | Kotlin Documentation",
            "Coroutines are Kotlin's approach to asynchronous programming with structured concurrency.",
            setOf("kotlin", "coroutines", "async", "flow", "concurrency"),
            "# Coroutines\nUse structured concurrency with CoroutineScope. Flow models streams of values. Channels and StateFlow connect producers and consumers.",
        ),
        Page(
            "https://en.wikipedia.org/wiki/Cat", "Cat - Wikipedia",
            "The cat (Felis catus) is a small domesticated carnivorous mammal.",
            setOf("cat", "cats", "felis", "pet", "animal"),
            "# Cat\nThe cat is a small domesticated carnivorous mammal. Domestic cats sleep 12 to 16 hours a day and communicate through vocalisations and body language.",
        ),
        Page(
            "https://www.commonmark.org/help/", "Markdown Reference - CommonMark",
            "A quick reference for Markdown syntax: headings, emphasis, lists, links, code and quotes.",
            setOf("markdown", "commonmark", "syntax", "render", "md", "docs"),
            "# Markdown Reference\nHeadings use #. Emphasis uses * or _. Code fences use ```. Tables and task lists are GitHub Flavored Markdown extensions.",
        ),
    )

    override suspend fun search(query: String): List<SearchHit> = rank(query).map { SearchHit(it.title, it.url, it.snippet) }

    fun rank(query: String): List<Page> {
        val tokens = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
        val scored = pages.map { p ->
            val title = p.title.lowercase()
            p to tokens.sumOf { t -> (if (t in p.keywords) 3 else 0) + (if (title.contains(t)) 1 else 0).toInt() }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return (scored.ifEmpty { pages.take(3) }).take(5)
    }

    override suspend fun fetch(url: String): FetchedPage {
        val p = pages.firstOrNull { it.url == url }
        return if (p != null) FetchedPage(p.url, p.title, p.body) else FetchedPage(url, hostOf(url), "# ${hostOf(url)}\nNo offline copy of this page.")
    }
}
