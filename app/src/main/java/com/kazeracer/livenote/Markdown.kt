package com.kazeracer.livenote

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Notion-flavoured markdown: one block per line. */
object Markdown {

    enum class Kind { TEXT, HEADING, BULLET, NUMBER, TODO, TOGGLE, QUOTE, DIVIDER }

    /**
     * @param indent number of leading whitespace chars
     * @param markerEnd index (within the line) where the block's content starts
     */
    data class Block(
        val kind: Kind,
        val indent: Int,
        val markerEnd: Int,
        val level: Int = 0,
        val number: Int = 0,
        val checked: Boolean = false,
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

    fun parse(line: CharSequence): Block {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        val rest = line.subSequence(i, line.length)

        if (i == 0 && rest.trim().let { it == "---" || it == "***" || it == "___" }) {
            return Block(Kind.DIVIDER, 0, line.length)
        }
        heading.find(rest)?.let {
            return Block(Kind.HEADING, i, i + it.value.length, level = it.groupValues[1].length)
        }
        todo.find(rest)?.let {
            val c = it.groupValues[1]
            return Block(Kind.TODO, i, i + it.value.length, checked = c == "x" || c == "X")
        }
        bullet.find(rest)?.let { return Block(Kind.BULLET, i, i + 2) }
        number.find(rest)?.let {
            return Block(Kind.NUMBER, i, i + it.value.length, number = it.groupValues[1].toInt())
        }
        if (rest.startsWith("> ")) return Block(Kind.TOGGLE, i, i + 2)
        if (rest.startsWith("\" ") || rest.startsWith("| ")) return Block(Kind.QUOTE, i, i + 2)
        return Block(Kind.TEXT, i, i)
    }

    // ---- inline: **bold**  *italic*  _italic_  `code`  ~~strike~~ ----

    enum class InlineKind { BOLD, ITALIC, CODE, STRIKE }

    /** A styled run; [start,end) covers markers too, [cStart,cEnd) is the content. */
    data class Inline(val kind: InlineKind, val start: Int, val end: Int, val cStart: Int, val cEnd: Int)

    private val inlineRegex = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|~~([^~\\n]+)~~|\\*([^*\\n]+)\\*|(?<![\\w])_([^_\\n]+)_(?![\\w])")

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

    private fun appendInline(sb: SpannableStringBuilder, text: String) {
        var pos = 0
        for (r in inlines(text)) {
            sb.append(text, pos, r.start)
            val s = sb.length
            sb.append(text, r.cStart, r.cEnd)
            val span: Any = when (r.kind) {
                InlineKind.BOLD -> StyleSpan(Typeface.BOLD)
                InlineKind.ITALIC -> StyleSpan(Typeface.ITALIC)
                InlineKind.CODE -> TypefaceSpan("monospace")
                InlineKind.STRIKE -> StrikethroughSpan()
            }
            sb.setSpan(span, s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos = r.end
        }
        sb.append(text, pos, text.length)
    }

    /** Read-only rendering for the notification shade (markers replaced by symbols). */
    fun toNotification(body: String): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = body.trimEnd().split('\n')
        for ((idx, line) in lines.withIndex()) {
            if (idx > 0) sb.append('\n')
            val b = parse(line)
            sb.append("    ".repeat(b.depth(line)))
            val content = line.substring(b.markerEnd)
            val start = sb.length
            when (b.kind) {
                Kind.DIVIDER -> sb.append("──────────")
                Kind.HEADING -> {
                    appendInline(sb, content)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (b.level == 1) sb.setSpan(RelativeSizeSpan(1.15f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Kind.BULLET -> { sb.append("•  "); appendInline(sb, content) }
                Kind.NUMBER -> { sb.append("${b.number}.  "); appendInline(sb, content) }
                Kind.TODO -> {
                    sb.append(if (b.checked) "☑  " else "☐  ")
                    val cs = sb.length
                    appendInline(sb, content)
                    if (b.checked) sb.setSpan(StrikethroughSpan(), cs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Kind.TOGGLE -> { sb.append("▸  "); appendInline(sb, content) }
                Kind.QUOTE -> {
                    sb.append("▍ ")
                    val cs = sb.length
                    appendInline(sb, content)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), cs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Kind.TEXT -> appendInline(sb, content)
            }
        }
        return sb
    }

    /** First meaningful line as plain text, for the collapsed notification. */
    fun firstLine(body: String): String {
        for (line in body.split('\n')) {
            val b = parse(line)
            if (b.kind == Kind.DIVIDER) continue
            val plain = toNotification(line).toString().trim()
            if (plain.isNotEmpty()) return plain
        }
        return ""
    }
}
