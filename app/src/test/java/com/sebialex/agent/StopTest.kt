package com.sebialex.agent

import com.sebialex.agent.data.web.cancellableIo
import com.sebialex.agent.domain.agent.TurnStop
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stop is immediate: the stream is closed, the turn keeps what it has, the queue waits. */
class StopTest {

    private fun step(id: String, status: StepStatus) =
        Step(id, StepKind.SHELL, "ls", "shell", "ls", status = status, startedAt = 1)

    @Test fun stoppedTurnKeepsTextAndClosesLiveSteps() {
        val turn = Turn(
            "a", Role.ASSISTANT, "", createdAt = 0, complete = false,
            steps = listOf(step("1", StepStatus.DONE), step("2", StepStatus.RUNNING), step("3", StepStatus.AWAITING_APPROVAL)),
        )
        val out = TurnStop.stopped(turn, now = 5)
        assertTrue(out.complete)
        assertEquals(TurnStop.STOPPED_TEXT, out.text)
        assertEquals(listOf(StepStatus.DONE, StepStatus.FAILED, StepStatus.FAILED), out.steps.map { it.status })
        assertEquals("cancelled", out.steps[1].output)
        assertEquals(5L, out.steps[1].finishedAt)
    }

    @Test fun answeredTurnIsUntouched() {
        val turn = Turn("a", Role.ASSISTANT, "Done", createdAt = 0, complete = true, steps = listOf(step("1", StepStatus.DONE)))
        assertEquals(turn, TurnStop.stopped(turn, now = 9))
    }

    @Test fun userStopDoesNotDrainTheQueue() {
        assertFalse(TurnStop.drainsQueue(stoppedByUser = true))
        assertTrue(TurnStop.drainsQueue(stoppedByUser = false))
    }

    @Test fun cancelClosesABlockingRead() = runBlocking {
        val closed = AtomicBoolean(false)
        val reading = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val job = async(Dispatchers.IO) {
            cancellableIo(close = { closed.set(true); unblock.countDown() }) {
                reading.countDown()
                // A socket read that only ends when the connection is closed.
                if (!unblock.await(10, TimeUnit.SECONDS)) error("never closed")
                throw IOException("socket closed")
            }
        }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        delay(50)
        job.cancel()
        try {
            withTimeout(2_000) { job.await() }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // The read ended as a cancellation, not as a network error.
        }
        assertTrue(closed.get())
    }

    @Test fun normalCompletionDoesNotClose() = runBlocking(Dispatchers.IO) {
        val closed = AtomicBoolean(false)
        val v = cancellableIo(close = { closed.set(true) }) { 42 }
        assertEquals(42, v)
        assertFalse(closed.get())
    }
}
