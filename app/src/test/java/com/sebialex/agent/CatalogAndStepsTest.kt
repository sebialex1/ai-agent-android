package com.sebialex.agent

import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.ui.chat.stepSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAndStepsTest {
    private fun step(id: String, kind: StepKind, out: String = "") =
        Step(id, kind, id, id, "", out, status = StepStatus.DONE, startedAt = 0)

    @Test fun interleavedOrderIsKeptAndDuplicatesDropped() {
        val steps = listOf(
            step("t1", StepKind.SEARCH), step("r1", StepKind.REASONING, "first"),
            step("t2", StepKind.SHELL), step("t3", StepKind.SHELL),
            step("r2", StepKind.REASONING, "second"), step("r3", StepKind.REASONING, "second"),
            step("r4", StepKind.REASONING, "  "),
        )
        val ids = stepSegments(steps).map { seg -> seg.map { it.id } }
        assertEquals(listOf(listOf("t1"), listOf("r1"), listOf("t2", "t3"), listOf("r2")), ids)
    }

    @Test fun catalogueIsConsistent() {
        val ids = LocalModels.catalog.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        LocalModels.catalog.forEach { m ->
            assertTrue(m.id, m.ramTiersGb.isNotEmpty() && m.minRamGb == m.ramTiersGb.min())
            assertTrue(m.id, m.url!!.contains("/resolve/") && m.sizeBytes > 0)
            // Nothing new above 9 GB: only Gemma 4 E4B keeps its 12 GB tier.
            if (m.ramTiersGb.any { it > 9 }) assertEquals("local/gemma-4-e4b-it", m.id)
        }
        assertTrue(LocalModels.forTier(1).any { it.id == "local/smollm2-135m-instruct" })
        assertTrue(LocalModels.forTier(8).map { it.id }.containsAll(listOf("local/gemma-4-e2b-it", "local/qwen3-4b", "local/lfm2.5-2.6b")))
    }
}
