package com.sebialex.agent

import com.sebialex.agent.domain.agent.Resume
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A run killed with the app: detected on load, closed as interrupted, continued on request. */
class ResumeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun session(vararg turns: Turn) = Session("s", "t", turns.toList(), createdAt = 0)
    private val user = Turn("u1", Role.USER, "download the video", createdAt = 1)
    private val cut = Turn(
        "a1", Role.ASSISTANT, "", createdAt = 2, complete = false,
        steps = listOf(
            Step("1", StepKind.SHELL, "yt-dlp", "shell", "yt-dlp", status = StepStatus.DONE, startedAt = 2, finishedAt = 3),
            Step("2", StepKind.SHELL, "ffmpeg", "shell", "ffmpeg", status = StepStatus.RUNNING, startedAt = 4),
        ),
    )

    @Test fun unfinishedTurnIsClosedAsInterrupted() {
        val out = Resume.markInterrupted(session(user, cut), running = emptySet(), now = 10)
        val t = out.turns.last()
        assertTrue(t.complete)
        assertTrue(t.interrupted)
        assertEquals(Resume.INTERRUPTED_TEXT, t.text)
        // Finished steps stay as they were; the one in flight is closed.
        assertEquals(listOf(StepStatus.DONE, StepStatus.FAILED), t.steps.map { it.status })
        assertEquals(10L, t.steps[1].finishedAt)
        assertEquals(t, Resume.candidate(out))
        assertEquals("download the video", Resume.taskFor(out, t.id))
    }

    @Test fun aRunningTurnIsLeftAlone() {
        val s = session(user, cut)
        assertSame(s, Resume.markInterrupted(s, running = setOf("a1"), now = 10))
    }

    @Test fun completeSessionsAreUnchanged() {
        val s = session(user, Turn("a1", Role.ASSISTANT, "Done", createdAt = 2))
        assertSame(s, Resume.markInterrupted(s, emptySet(), 10))
        assertNull(Resume.candidate(s))
    }

    @Test fun onlyTheLastTurnCanBeContinued() {
        val marked = Resume.markInterrupted(session(user, cut), emptySet(), 10)
        val later = marked.copy(turns = marked.turns + Turn("u2", Role.USER, "thanks", createdAt = 20))
        assertNull(Resume.candidate(later))
    }

    @Test fun continuationKeepsTheRequestAndWarnsAboutSideEffects() {
        val task = Resume.continuationTask("download the video")
        assertTrue(task.startsWith("download the video"))
        assertTrue("do not redo side effects" in task)
    }

    @Test fun oldSavesWithoutTheFieldStillLoad() {
        val old = """{"id":"s","title":"t","createdAt":0,"turns":[{"id":"a","role":"ASSISTANT","text":"","createdAt":1,"complete":false}]}"""
        val s = json.decodeFromString(Session.serializer(), old)
        assertFalse(s.turns.single().interrupted)
        assertTrue(Resume.markInterrupted(s, emptySet(), 5).turns.single().interrupted)
    }

    @Test fun onlyDurableStepUpdatesAreCheckpoints() {
        // A tool that finished (or failed, or was denied) is a state a resume can build on.
        listOf(StepStatus.DONE, StepStatus.FAILED, StepStatus.DENIED).forEach { st ->
            assertTrue(Resume.durable(Step("1", StepKind.SHELL, "", "shell", "", status = st, startedAt = 0)))
        }
        // The throttled live-reasoning stream and a card still waiting for the user are not.
        listOf(StepStatus.RUNNING, StepStatus.AWAITING_APPROVAL).forEach { st ->
            assertFalse(Resume.durable(Step("1", StepKind.SHELL, "", "shell", "", status = st, startedAt = 0)))
        }
    }
}
