package com.kazeracer.livenote

/**
 * Notion-flavoured markdown, one block per line. Pure Kotlin (no Android types),
 * so it is unit-tested on the JVM.
 *
 * Toggles: a line starting with "> " (optionally combined with a heading:
 * "> ## Title" or "## > Title"). Its content is the following lines that are
 * indented deeper (2 spaces or a tab per level).
 */
object MarkdownCore {

    enum class Kind { TEXT, HEADING, BULLET, NUMBER, TODO, TOGGLE, QUOTE, DIVIDER }

    /**
     * @param indent number of leading whitespace chars
     * @param markerEnd index (within the line) where the block's content starts
     * @param level heading level 1..3 (also for toggle headings), 0 otherwise
     * @param togglePos index of the toggle's '>' (TOGGLE only)
     */
    data class Block(
        val kind: Kind,
        val indent: Int,
        val markerEnd: Int,
        val level: Int = 0,
        val number: Int = 0,
        val checked: Boolean = false,
        val togglePos: Int = -1,
    ) {
        /** Visual nesting depth: 2 spaces or 1 tab per level. */
        fun depth(line: CharSequence): Int {
            var d = 0.0
            for (i in 0 until indent) d += if (line[i] == '\t') 1.0 else 0.5
            return d.toInt()
        }
    }

    private val heading = Regex("^(#{1,3}) ")
    private val todo = Regex("^(?:[-*+] )?\\[( |x|X)?] ")
    private val bullet = Regex("^[-*+] ")
    private val number = Regex("^(\\d{1,4})[.)] ")

    private fun CharSequence.startsAt(prefix: String, at: Int): Boolean =
        at + prefix.length <= length && subSequence(at, at + prefix.length).toString() == prefix

    fun parse(line: CharSequence): Block {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++

        // toString(): the editor passes Spannables, which never equal a String.
        if (i == 0 && line.toString().trim().let { it == "---" || it == "***" || it == "___" }) {
            return Block(Kind.DIVIDER, 0, line.length)
        }

        // Toggle, heading, or both in either order.
        var pos = i
        var toggle = -1
        var level = 0
        if (line.startsAt("> ", pos)) { toggle = pos; pos += 2 }
        heading.find(line.subSequence(pos, line.length))?.let {
            level = it.groupValues[1].length
            pos += it.value.length
        }
        if (toggle < 0 && level > 0 && line.startsAt("> ", pos)) { toggle = pos; pos += 2 }
        if (toggle >= 0) return Block(Kind.TOGGLE, i, pos, level = level, togglePos = toggle)
        if (level > 0) return Block(Kind.HEADING, i, pos, level = level)

        val rest = line.subSequence(i, line.length)
        todo.find(rest)?.let {
            val c = it.groupValues[1]
            return Block(Kind.TODO, i, i + it.value.length, checked = c == "x" || c == "X")
        }
        bullet.find(rest)?.let { return Block(Kind.BULLET, i, i + 2) }
        number.find(rest)?.let {
            return Block(Kind.NUMBER, i, i + it.value.length, number = it.groupValues[1].toInt())
        }
        if (rest.startsWith("\" ") || rest.startsWith("| ")) return Block(Kind.QUOTE, i, i + 2)
        return Block(Kind.TEXT, i, i)
    }

    /** End (exclusive line index) of the content of the toggle at [index]. */
    fun toggleContentEnd(lines: List<String>, index: Int): Int {
        val depth = parse(lines[index]).depth(lines[index])
        var j = index + 1
        while (j < lines.size && lines[j].isNotEmpty() && parse(lines[j]).depth(lines[j]) > depth) j++
        return j
    }

    /** Indices of the lines that are visible, i.e. not inside a collapsed toggle. */
    fun visibleLines(lines: List<String>, collapsed: Set<Int>): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < lines.size) {
            out.add(i)
            i = if (i in collapsed && parse(lines[i]).kind == Kind.TOGGLE) toggleContentEnd(lines, i) else i + 1
        }
        return out
    }

    // ---- inline: **bold**  *italic*  _italic_  `code`  ~~strike~~ ----

    enum class InlineKind { BOLD, ITALIC, CODE, STRIKE }

    /** A styled run; [start,end) covers markers too, [cStart,cEnd) is the content. */
    data class Inline(val kind: InlineKind, val start: Int, val end: Int, val cStart: Int, val cEnd: Int)

    private val inlineRegex =
        Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|~~([^~\\n]+)~~|\\*([^*\\n]+)\\*|(?<![\\w])_([^_\\n]+)_(?![\\w])")

    fun inlines(text: CharSequence): List<Inline> = inlineRegex.findAll(text).map { m ->
        val g = (1..5).first { m.groups[it] != null }
        val kind = when (g) {
            1 -> InlineKind.CODE
            2 -> InlineKind.BOLD
            3 -> InlineKind.STRIKE
            else -> InlineKind.ITALIC
        }
        val markLen = if (g == 2 || g == 3) 2 else 1
        Inline(kind, m.range.first, m.range.last + 1, m.range.first + markLen, m.range.last + 1 - markLen)
    }.toList()

    /** Content of a line without block or inline markers. */
    fun plain(line: String): String {
        val b = parse(line)
        if (b.kind == Kind.DIVIDER) return ""
        val content = line.substring(b.markerEnd)
        val sb = StringBuilder()
        var pos = 0
        for (r in inlines(content)) {
            sb.append(content, pos, r.start).append(content, r.cStart, r.cEnd)
            pos = r.end
        }
        return sb.append(content, pos, content.length).toString().trim()
    }

    // ---- to-dos from the notification ----

    data class Todo(val line: Int, val text: String)

    /** Unchecked, visible to-dos in order. */
    fun openTodos(body: String, collapsed: Set<Int>): List<Todo> {
        val lines = body.split('\n')
        return visibleLines(lines, collapsed)
            .filter { parse(lines[it]).let { b -> b.kind == Kind.TODO && !b.checked } }
            .map { Todo(it, plain(lines[it])) }
    }

    /**
     * Ticks/unticks the to-do on [line], but only if that line still has the text
     * the notification showed (the note may have changed in between). Null if not.
     */
    fun toggleTodo(body: String, line: Int, expectedText: String): String? {
        val lines = body.split('\n').toMutableList()
        if (line !in lines.indices) return null
        val l = lines[line]
        val b = parse(l)
        if (b.kind != Kind.TODO || plain(l) != expectedText) return null
        val br = l.indexOf('[', b.indent)
        lines[line] = if (l[br + 1] == ']') l.substring(0, br + 1) + "x" + l.substring(br + 1)
        else l.substring(0, br + 1) + (if (b.checked) " " else "x") + l.substring(br + 2)
        return lines.joinToString("\n")
    }

    // ---- truncation planning for the notification ----

    /** Rough number of rows a line takes in the notification shade. */
    fun estimateRows(line: String, charsPerRow: Int = 36): Int {
        val len = plain(line).length + parse(line).depth(line) * 3
        return maxOf(1, (len + charsPerRow - 1) / charsPerRow)
    }

    /**
     * Which visible lines fit in [budgetRows]. Returns the lines to show and how
     * many non-blank visible lines are left out (0 = nothing truncated).
     */
    fun planNotification(body: String, collapsed: Set<Int>, budgetRows: Int): Pair<List<Int>, Int> {
        val lines = body.trimEnd().split('\n')
        val visible = visibleLines(lines, collapsed)
        val shown = ArrayList<Int>()
        var used = 0
        var k = 0
        while (k < visible.size) {
            val idx = visible[k]
            val rows = estimateRows(lines[idx])
            if (shown.isNotEmpty() && used + rows > budgetRows) break
            shown.add(idx)
            used += rows
            k++
        }
        val left = visible.drop(k).count { lines[it].isNotBlank() }
        // Don't end on blank lines right before the "more" marker.
        if (left > 0) while (shown.isNotEmpty() && lines[shown.last()].isBlank()) shown.removeAt(shown.size - 1)
        return shown to left
    }
}
