package com.sebialex.agent

import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.domain.agent.Plans
import com.sebialex.agent.domain.agent.Todos
import com.sebialex.agent.domain.model.Plan
import com.sebialex.agent.domain.model.PlanStatus
import com.sebialex.agent.domain.model.PlanStep
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.TodoItem
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.ui.chat.middleEllipsis
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** set_plan, ask_user and todo: parsing, the todo list logic, plan progress, old saves. */
class ChatToolsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun todoAddNumbersEntriesAfterTheHighest() {
        val start = listOf(TodoItem(3, "a"))
        val out = Todos.apply(start, "add", listOf(" b ", "", "c"), emptyList())
        assertTrue(out.ok)
        assertEquals(listOf(3, 4, 5), out.items.map { it.id })
        assertEquals(listOf("a", "b", "c"), out.items.map { it.text })
        assertTrue("4. [ ] b" in out.message)
    }

    @Test fun todoDoneReopenRemoveAndClear() {
        val list = Todos.apply(emptyList(), "add", listOf("one", "two", "three"), emptyList()).items
        val done = Todos.apply(list, "done", emptyList(), listOf(1, 3)).items
        assertEquals(listOf(true, false, true), done.map { it.done })
        assertEquals("two", Todos.nextOpen(done)?.text)
        val reopened = Todos.apply(done, "reopen", emptyList(), listOf(3)).items
        assertEquals(listOf(true, false, false), reopened.map { it.done })
        val removed = Todos.apply(reopened, "remove", emptyList(), listOf(2)).items
        assertEquals(listOf(1, 3), removed.map { it.id })
        val cleared = Todos.apply(removed, "clear_done", emptyList(), emptyList()).items
        assertEquals(listOf(3), cleared.map { it.id })
    }

    @Test fun todoMistakesLeaveTheListAlone() {
        val list = listOf(TodoItem(1, "x"))
        for (out in listOf(
            Todos.apply(list, "done", emptyList(), listOf(7)),
            Todos.apply(list, "done", emptyList(), emptyList()),
            Todos.apply(list, "add", listOf(" "), emptyList()),
            Todos.apply(list, "archive", emptyList(), emptyList()),
        )) {
            assertFalse(out.ok)
            assertEquals(list, out.items)
        }
        assertEquals("The todo list is empty.", Todos.render(emptyList()))
        assertNull(Todos.nextOpen(listOf(TodoItem(1, "x", done = true))))
    }

    @Test fun planProgressAndStatusWords() {
        val plan = Plan("Ship", listOf(PlanStep("a", PlanStatus.DONE), PlanStep("b", PlanStatus.SKIPPED), PlanStep("c", PlanStatus.ACTIVE), PlanStep("d")))
        assertEquals(2 to 4, Plans.progress(plan))
        assertTrue("in progress: c" in Plans.summary(plan))
        assertEquals(PlanStatus.ACTIVE, Plans.status("in_progress"))
        assertEquals(PlanStatus.DONE, Plans.status("Completed"))
        assertEquals(PlanStatus.PENDING, Plans.status("whatever"))
    }

    @Test fun setPlanParsesObjectsAndStrings() {
        val c = OpenRouterBrain.parseToolCall("1", "set_plan", """{"title":"Build","steps":[{"text":"Fetch","status":"done"},"Render",{"text":" "}]}""")
        c as ToolCall.SetPlan
        assertEquals("Build", c.title)
        assertEquals(listOf(PlanStep("Fetch", PlanStatus.DONE), PlanStep("Render")), c.steps)
        assertTrue(OpenRouterBrain.parseToolCall("2", "set_plan", """{"title":"x","steps":[]}""") is ToolCall.Invalid)
    }

    @Test fun askUserAndTodoParse() {
        val a = OpenRouterBrain.parseToolCall("1", "ask_user", """{"question":"Which size?","choices":["S"," ","M"]}""") as ToolCall.AskUser
        assertEquals(listOf("S", "M"), a.choices)
        assertTrue(OpenRouterBrain.parseToolCall("2", "ask_user", "{}") is ToolCall.Invalid)
        val t = OpenRouterBrain.parseToolCall("3", "todo", """{"action":"Done","ids":[2,"3"]}""") as ToolCall.Todo
        assertEquals("done", t.action)
        assertEquals(listOf(2, 3), t.ids)
        val add = OpenRouterBrain.parseToolCall("4", "todo", """{"action":"add","items":["write tests"]}""") as ToolCall.Todo
        assertEquals(listOf("write tests"), add.items)
    }

    @Test fun theNewToolsAreOfferedToCloudModelsOnly() {
        val full = OpenRouterBrain.toolsFor(memory = false).toString()
        val compact = OpenRouterBrain.toolsFor(memory = false, compact = true).toString()
        for (name in listOf("set_plan", "ask_user", "todo")) {
            assertTrue("\"$name\"" in full)
            assertFalse("\"$name\"" in compact)
        }
    }

    @Test fun oldSavesWithoutPlansQuestionsOrTodosLoad() {
        val old = """{"id":"s","title":"t","createdAt":0,"turns":[{"id":"a","role":"ASSISTANT","text":"hi","createdAt":1}]}"""
        val s = json.decodeFromString(Session.serializer(), old)
        assertTrue(s.todos.isEmpty())
        assertNull(s.turns.single().plan)
        assertTrue(s.turns.single().questions.isEmpty())
        val again = json.decodeFromString(Session.serializer(), json.encodeToString(Session.serializer(), s.copy(todos = listOf(TodoItem(1, "x")))))
        assertEquals("x", again.todos.single().text)
    }

    @Test fun middleEllipsisKeepsBothEnds() {
        assertEquals("short", middleEllipsis("short", 20))
        val cut = middleEllipsis("Write the introduction and then the final section", 21)
        assertTrue(cut.length <= 21)
        assertTrue(cut.startsWith("Write the"))
        assertTrue(cut.endsWith("section"))
        assertTrue('\u2026' in cut)
    }
}
