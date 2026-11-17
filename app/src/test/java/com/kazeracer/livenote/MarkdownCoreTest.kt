package com.kazeracer.livenote

import com.kazeracer.livenote.MarkdownCore.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownCoreTest {

    @Test fun toggleHeadingBothOrders() {
        val a = MarkdownCore.parse("> ## Spesa")
        assertEquals(Kind.TOGGLE, a.kind); assertEquals(2, a.level); assertEquals(0, a.togglePos); assertEquals(5, a.markerEnd)
        val b = MarkdownCore.parse("## > Spesa")
        assertEquals(Kind.TOGGLE, b.kind); assertEquals(2, b.level); assertEquals(3, b.togglePos); assertEquals(5, b.markerEnd)
        val c = MarkdownCore.parse("  > dentro")
        assertEquals(Kind.TOGGLE, c.kind); assertEquals(0, c.level); assertEquals(2, c.togglePos); assertEquals(1, c.depth("  > dentro"))
    }

    @Test fun blocks() {
        assertEquals(Kind.HEADING, MarkdownCore.parse("# T").kind)
        assertEquals(Kind.BULLET, MarkdownCore.parse("- a").kind)
        assertEquals(Kind.NUMBER, MarkdownCore.parse("12. a").kind)
        assertEquals(Kind.TODO, MarkdownCore.parse("[] a").kind)
        assertEquals(true, MarkdownCore.parse("- [x] a").checked)
        assertEquals(Kind.QUOTE, MarkdownCore.parse("\" a").kind)
        assertEquals(Kind.DIVIDER, MarkdownCore.parse("---").kind)
        assertEquals(Kind.TEXT, MarkdownCore.parse(">no space").kind)
    }

    @Test fun collapsedToggleHidesChildren() {
        val lines = listOf("> ## A", "  - x", "  - y", "fuori", "> B", "  z")
        assertEquals(listOf(0, 3, 4, 5), MarkdownCore.visibleLines(lines, setOf(0)))
        assertEquals(listOf(0, 1, 2, 3, 4), MarkdownCore.visibleLines(lines, setOf(4)))
    }

    @Test fun todosFromNotification() {
        val body = "> Spesa\n  [] Latte\n  [x] Pane\n  [ ] **Uova**"
        val todos = MarkdownCore.openTodos(body, emptySet())
        assertEquals(listOf(1, 3), todos.map { it.line })
        assertEquals("Uova", todos[1].text)
        assertEquals(emptyList<Int>(), MarkdownCore.openTodos(body, setOf(0)).map { it.line })

        val ticked = MarkdownCore.toggleTodo(body, 1, "Latte")!!
        assertEquals("  [x] Latte", ticked.split('\n')[1])
        assertEquals("  [x] **Uova**", MarkdownCore.toggleTodo(body, 3, "Uova")!!.split('\n')[3])
        assertNull(MarkdownCore.toggleTodo(body, 1, "Altro")) // note changed meanwhile
    }

    @Test fun truncationPlan() {
        val body = (1..20).joinToString("\n") { "- voce $it" }
        val (shown, left) = MarkdownCore.planNotification(body, emptySet(), 8)
        assertEquals(8, shown.size)
        assertEquals(12, left)
        val (all, none) = MarkdownCore.planNotification("a\nb", emptySet(), 8)
        assertEquals(2, all.size); assertEquals(0, none)
    }

    @Test fun plainStripsMarkers() {
        assertEquals("ciao mondo", MarkdownCore.plain("## **ciao** _mondo_"))
    }
}
