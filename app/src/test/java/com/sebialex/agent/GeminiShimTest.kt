package com.sebialex.agent

import com.sebialex.agent.data.llm.GeminiShim
import com.sebialex.agent.data.secure.AgtFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gemini account shim is upgraded by the app, not trusted: these lock the two patches that
 * surface the account's real model registry (an unknown name must reach gemini_webapi's
 * per-account resolver instead of the shim's silent default) and the wiring that keeps them
 * applied across reinstalls.
 */
class GeminiShimTest {

    private val marker = "# [ai-agent-android patch]"

    @Test
    fun patchCoversBothUpstreamFilesWithMarkers() {
        // config.py: the silent-default fallback line gets replaced by the pass-through.
        assertTrue(GeminiShim.PATCH.contains("return _CANON.get(key2) or _ALIASES.get(key2) or DEFAULT_MODEL"))
        assertTrue(GeminiShim.PATCH.contains("return key2 or DEFAULT_MODEL"))
        // server.py: /v1/models merges the live account registry.
        assertTrue(GeminiShim.PATCH.contains("config.list_public_models()"))
        assertTrue(GeminiShim.PATCH.contains("client.list_models()"))
        assertTrue(GeminiShim.PATCH.contains(marker))
    }

    @Test
    fun setupAndStartReapplyThePatch() {
        // One-time setup ends in the patch (idempotent via the marker); every start runs it
        // first, so a pip upgrade cannot silently drop the account-registry behaviour.
        assertTrue("setup runs the patch", GeminiShim.SETUP.contains("&& " + GeminiShim.PATCH.trim()))
        assertTrue(GeminiShim.PATCH.contains("'PYPATCH'"))
        assertTrue("start applies the patch first", GeminiShim.startScript(AgtFile.Payload()).startsWith(GeminiShim.PATCH))
    }
}
