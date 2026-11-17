package com.kazeracer.livenote

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import com.kazeracer.livenote.MarkdownCore.Kind

/** Read-only rendering of the note for the notification shade. */
object Markdown {

    private fun appendInline(sb: SpannableStringBuilder, text: String) {
        var pos = 0
        for (r in MarkdownCore.inlines(text)) {
            sb.append(text, pos, r.start)
            val s = sb.length
            sb.append(text, r.cStart, r.cEnd)
            val span: Any = when (r.kind) {
                MarkdownCore.InlineKind.BOLD -> StyleSpan(Typeface.BOLD)
                MarkdownCore.InlineKind.ITALIC -> StyleSpan(Typeface.ITALIC)
                MarkdownCore.InlineKind.CODE -> TypefaceSpan("monospace")
                MarkdownCore.InlineKind.STRIKE -> StrikethroughSpan()
            }
            sb.setSpan(span, s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos = r.end
        }
        sb.append(text, pos, text.length)
    }

    private fun SpannableStringBuilder.style(what: Any, start: Int) {
        if (length > start) setSpan(what, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Renders what fits in about [budgetRows] rows. If something is cut, ends with
     * a "⋯ altre N righe" line so it is obvious the note continues.
     */
    fun toNotification(body: String, collapsed: Set<Int>, budgetRows: Int): CharSequence {
        val lines = body.trimEnd().split('\n')
        val (shown, left) = MarkdownCore.planNotification(body, collapsed, budgetRows)
        val sb = SpannableStringBuilder()
        for ((n, idx) in shown.withIndex()) {
            if (n > 0) sb.append('\n')
            val line = lines[idx]
            val b = MarkdownCore.parse(line)
            sb.append("    ".repeat(b.depth(line)))
            val content = line.substring(b.markerEnd)
            val start = sb.length
            when (b.kind) {
                Kind.DIVIDER -> sb.append("──────────")
                Kind.HEADING -> {
                    appendInline(sb, content)
                    sb.style(StyleSpan(Typeface.BOLD), start)
                    if (b.level == 1) sb.style(RelativeSizeSpan(1.15f), start)
                }
                Kind.BULLET -> { sb.append("•  "); appendInline(sb, content) }
                Kind.NUMBER -> { sb.append("${b.number}.  "); appendInline(sb, content) }
                Kind.TODO -> {
                    sb.append(if (b.checked) "☑  " else "☐  ")
                    val cs = sb.length
                    appendInline(sb, content)
                    if (b.checked) sb.style(StrikethroughSpan(), cs)
                }
                Kind.TOGGLE -> {
                    sb.append(if (idx in collapsed) "▸  " else "▾  ")
                    val cs = sb.length
                    appendInline(sb, content)
                    if (b.level > 0) sb.style(StyleSpan(Typeface.BOLD), cs)
                }
                Kind.QUOTE -> {
                    sb.append("▍ ")
                    val cs = sb.length
                    appendInline(sb, content)
                    sb.style(StyleSpan(Typeface.ITALIC), cs)
                }
                Kind.TEXT -> appendInline(sb, content)
            }
        }
        if (left > 0) {
            if (sb.isNotEmpty()) sb.append('\n')
            val s = sb.length
            sb.append("⋯   altre $left ${if (left == 1) "riga" else "righe"} · tocca per aprire")
            sb.style(StyleSpan(Typeface.ITALIC), s)
            sb.style(RelativeSizeSpan(0.9f), s)
        }
        return sb
    }

    /** First meaningful line as plain text, for the collapsed notification. */
    fun firstLine(body: String): String =
        body.split('\n').map { MarkdownCore.plain(it) }.firstOrNull { it.isNotEmpty() } ?: ""
}
