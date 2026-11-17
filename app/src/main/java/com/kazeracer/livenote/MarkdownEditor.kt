package com.kazeracer.livenote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.EditText
import com.kazeracer.livenote.MarkdownCore.Kind

/**
 * A plain EditText that styles markdown as you type, Notion style:
 * block markers turn into bullets / checkboxes / toggles, Enter continues lists,
 * Enter on an empty item ends the list, tapping a checkbox or toggle acts on it.
 */
class MarkdownEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    var onChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val cText = context.getColor(R.color.text)
    private val cSecondary = context.getColor(R.color.text_secondary)
    private val cFaint = context.getColor(R.color.text_faint)
    private val cDivider = context.getColor(R.color.divider)
    private val cCodeBg = context.getColor(R.color.code_bg)
    private val cCodeText = context.getColor(R.color.code_text)
    private val cAccent = context.getColor(R.color.accent)

    private var busy = false
    private var pendingNewline = -1

    /** Collapsed toggles: the span sits on the toggle's '>' and holds the hidden children. */
    private class Collapsed(val hidden: String) { var lastLineEnd = 0 }
    private val collapsed = mutableListOf<Collapsed>()

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (busy) return
                pendingNewline = if (count == 1 && before == 0 && s[start] == '\n') start else -1
            }
            override fun afterTextChanged(e: Editable) {
                if (busy) return
                busy = true
                try {
                    if (pendingNewline >= 0) handleEnter(e, pendingNewline)
                    pendingNewline = -1
                    validateCollapsed(e)
                    restyle(e)
                } finally {
                    busy = false
                }
                onChanged?.invoke()
            }
        })
    }

    // ------------------------------------------------------------------ public

    /** @param closed line indices (in [md]) of toggles to show closed */
    fun setMarkdown(md: String, closed: Set<Int> = emptySet()) {
        busy = true
        try {
            collapsed.clear()
            setText(md)
            val e = text
            // Bottom-up, so collapsing a toggle never shifts the lines still to process.
            for (idx in closed.sortedDescending()) {
                var ls = 0
                var n = 0
                while (n < idx && ls < e.length) {
                    ls = lineEnd(e, ls) + 1
                    n++
                }
                if (n != idx || ls > e.length) continue
                val b = MarkdownCore.parse(e.subSequence(ls, lineEnd(e, ls)))
                if (b.kind == Kind.TOGGLE && collapsedAt(e, ls) == null) toggleAt(e, ls, b)
            }
            restyle(e)
        } finally {
            busy = false
        }
    }

    fun getMarkdown(): String = expandedText(text, 0, text.length)

    /** Line indices, in [getMarkdown], of the toggles that are closed. */
    fun getCollapsedLines(): Set<Int> {
        val e = text
        val out = HashSet<Int>()
        var hiddenLines = 0
        for (c in e.getSpans(0, e.length, Collapsed::class.java).sortedBy { e.getSpanStart(it) }) {
            val s = e.getSpanStart(c)
            var visible = 0
            for (i in 0 until s) if (e[i] == '\n') visible++
            out.add(visible + hiddenLines)
            hiddenLines += c.hidden.count { it == '\n' }
        }
        return out
    }

    // ------------------------------------------------------------------ lines

    private fun lineStart(s: CharSequence, pos: Int): Int {
        var i = pos.coerceAtMost(s.length)
        while (i > 0 && s[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(s: CharSequence, pos: Int): Int {
        var i = pos.coerceAtLeast(0)
        while (i < s.length && s[i] != '\n') i++
        return i
    }

    // ------------------------------------------------------------------ enter

    private fun handleEnter(e: Editable, nl: Int) {
        if (nl >= e.length || e[nl] != '\n') return
        val ps = lineStart(e, nl)
        val prev = e.subSequence(ps, nl).toString()
        val b = MarkdownCore.parse(prev)
        val content = prev.substring(b.markerEnd)
        val nextLineRest = e.subSequence(nl + 1, lineEnd(e, nl + 1)).toString()
        val indent = prev.substring(0, b.indent)
        val emptyItem = content.isBlank() && nextLineRest.isEmpty()

        fun outdentOrClear(clearMarker: Boolean) {
            e.delete(nl, nl + 1)
            if (b.indent > 0) {
                val cut = if (prev[0] == '\t') 1 else minOf(2, b.indent)
                e.delete(ps, ps + cut)
            } else if (clearMarker) {
                e.delete(ps, ps + b.markerEnd)
            }
        }

        when (b.kind) {
            Kind.BULLET, Kind.QUOTE, Kind.TODO, Kind.NUMBER, Kind.TOGGLE -> {
                if (emptyItem) {
                    outdentOrClear(clearMarker = true)
                    return
                }
                val marker = prev.substring(b.indent, b.markerEnd)
                val prefix = when (b.kind) {
                    Kind.NUMBER -> "${b.number + 1}${marker.trimEnd().last()} "
                    Kind.TODO -> marker.replace('x', ' ').replace('X', ' ')
                    Kind.TOGGLE -> if (isCollapsedLine(e, ps)) "" else "  "
                    else -> marker
                }
                e.insert(nl + 1, indent + prefix)
            }
            Kind.TEXT, Kind.HEADING -> {
                if (b.indent == 0) return
                if (emptyItem) outdentOrClear(clearMarker = false) else e.insert(nl + 1, indent)
            }
            Kind.DIVIDER -> {}
        }
    }

    // ------------------------------------------------------------------ toggles

    private fun collapsedAt(e: Spanned, lineStart: Int): Collapsed? {
        val le = lineEnd(e, lineStart)
        return e.getSpans(lineStart, le, Collapsed::class.java).firstOrNull()
    }

    private fun isCollapsedLine(e: Spanned, lineStart: Int) = collapsedAt(e, lineStart) != null

    /** Text of [start,end) with every collapsed toggle's children put back in place. */
    private fun expandedText(e: Spanned, start: Int, end: Int): String {
        val sb = StringBuilder(e.subSequence(start, end).toString())
        e.getSpans(start, end, Collapsed::class.java)
            .sortedByDescending { e.getSpanStart(it) }
            .forEach { c ->
                val at = lineEnd(e, e.getSpanStart(c)).coerceAtMost(end) - start
                sb.insert(at, c.hidden)
            }
        return sb.toString()
    }

    private fun toggleAt(e: Editable, ls: Int, b: MarkdownCore.Block) {
        val existing = collapsedAt(e, ls)
        if (existing != null) {
            expand(e, existing)
            return
        }
        val le = lineEnd(e, ls)
        val depth = b.depth(e.subSequence(ls, le))
        var end = le
        while (end < e.length) {
            val ns = end + 1
            val ne = lineEnd(e, ns)
            val line = e.subSequence(ns, ne)
            if (line.isEmpty() || MarkdownCore.parse(line).depth(line) <= depth) break
            end = ne
        }
        val hidden = expandedText(e, le, end)
        e.getSpans(le, end, Collapsed::class.java).forEach { e.removeSpan(it); collapsed.remove(it) }
        e.delete(le, end)
        val c = Collapsed(hidden).also { it.lastLineEnd = le }
        e.setSpan(c, ls + b.togglePos, ls + b.togglePos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        collapsed.add(c)
    }

    private fun expand(e: Editable, c: Collapsed) {
        val s = e.getSpanStart(c)
        val at = if (s >= 0) lineEnd(e, s) else c.lastLineEnd.coerceIn(0, e.length)
        e.removeSpan(c)
        collapsed.remove(c)
        e.insert(at, c.hidden)
    }

    /** Never lose hidden children: if a collapsed toggle stops being a toggle, open it. */
    private fun validateCollapsed(e: Editable) {
        for (c in collapsed.toList()) {
            val s = e.getSpanStart(c)
            if (s < 0) {
                expand(e, c)
                continue
            }
            val ls = lineStart(e, s)
            val b = MarkdownCore.parse(e.subSequence(ls, lineEnd(e, ls)))
            if (b.kind != Kind.TOGGLE || ls + b.togglePos != s) expand(e, c)
            else c.lastLineEnd = lineEnd(e, s)
        }
    }

    // ------------------------------------------------------------------ taps

    private var downHit = -1

    /** Returns the paragraph start if (x,y) is on a checkbox / toggle marker. */
    private fun hitMarker(x: Float, y: Float): Int {
        val l: Layout = layout ?: return -1
        val line = l.getLineForVertical((y - totalPaddingTop + scrollY).toInt())
        val ls = l.getLineStart(line)
        if (ls > 0 && text[ls - 1] != '\n') return -1
        val b = MarkdownCore.parse(text.subSequence(ls, lineEnd(text, ls)))
        if (b.kind != Kind.TODO && b.kind != Kind.TOGGLE) return -1
        val x0 = l.getPrimaryHorizontal(ls + b.indent)
        val x1 = l.getPrimaryHorizontal(ls + b.markerEnd)
        val tx = x - totalPaddingLeft + scrollX
        val slop = 8 * density
        return if (tx >= x0 - slop && tx <= x1 + slop) ls else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downHit = hitMarker(event.x, event.y)
                if (downHit >= 0) return true
            }
            MotionEvent.ACTION_MOVE -> if (downHit >= 0) return true
            MotionEvent.ACTION_UP -> if (downHit >= 0) {
                val ls = downHit
                downHit = -1
                if (hitMarker(event.x, event.y) == ls) activateMarker(ls)
                return true
            }
            MotionEvent.ACTION_CANCEL -> downHit = -1
        }
        return super.onTouchEvent(event)
    }

    private fun activateMarker(ls: Int) {
        val e = text
        val b = MarkdownCore.parse(e.subSequence(ls, lineEnd(e, ls)))
        when (b.kind) {
            Kind.TODO -> {
                val br = e.indexOf("[", ls + b.indent)
                if (e[br + 1] == ']') e.insert(br + 1, "x")
                else e.replace(br + 1, br + 2, if (b.checked) " " else "x")
            }
            Kind.TOGGLE -> {
                busy = true
                try {
                    toggleAt(e, ls, b)
                    restyle(e)
                } finally {
                    busy = false
                }
                onChanged?.invoke()
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ styling

    private interface Md
    private class MdFg(c: Int) : ForegroundColorSpan(c), Md
    private class MdBg(c: Int) : BackgroundColorSpan(c), Md
    private class MdStyle(s: Int) : StyleSpan(s), Md
    private class MdSize(f: Float) : RelativeSizeSpan(f), Md
    private class MdMono : TypefaceSpan("monospace"), Md
    private class MdStrike : StrikethroughSpan(), Md

    /** Draws a fixed-width shape in place of the marker characters. */
    private abstract class Marker(val widthEm: Float) : ReplacementSpan(), Md {
        override fun getSize(p: Paint, t: CharSequence?, s: Int, e: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) p.getFontMetricsInt(fm)
            return (p.textSize * widthEm).toInt()
        }
        override fun draw(c: Canvas, t: CharSequence?, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
            val fm = p.fontMetrics
            val cy = y + (fm.ascent + fm.descent) / 2f
            paint(c, x, cy, p.textSize, p)
        }
        abstract fun paint(c: Canvas, x: Float, cy: Float, size: Float, p: Paint)
    }

    private class Hidden : Marker(0f) { override fun paint(c: Canvas, x: Float, cy: Float, size: Float, p: Paint) {} }

    private class Indent(em: Float) : Marker(em) { override fun paint(c: Canvas, x: Float, cy: Float, size: Float, p: Paint) {} }

    private class Bullet(val depth: Int, val color: Int) : Marker(1.5f) {
        override fun paint(c: Canvas, x: Float, cy: Float, size: Float, p: Paint) {
            val cx = x + size * 0.6f
            val r = size * 0.13f
            val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@Bullet.color }
            when (depth % 3) {
                0 -> c.drawCircle(cx, cy, r, q)
                1 -> { q.style = Paint.Style.STROKE; q.strokeWidth = size * 0.07f; c.drawCircle(cx, cy, r, q) }
                else -> c.drawRect(cx - r, cy - r, cx + r, cy + r, q)
            }
        }
    }

    private inner class Check(val checked: Boolean) : Marker(1.6f) {
        override fun paint(c: Canvas, x: Float, cy: Float, size: Float, p: Paint) {
            val s = size * 0.95f
            val left = x + size * 0.05f
            val box = RectF(left, cy - s / 2, left + s, cy + s / 2)
            val q = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = 3 * density
            if (checked) {
                q.color = cAccent
                c.drawRoundRect(box, radius, radius, q)
                q.color = Color.WHITE
                q.style = Paint.Style.STROKE
                q.strokeWidth = s * 0.12f
                q.strokeCap = Paint.Cap.ROUND
                q.strokeJoin = Paint.Join.ROUND
                val path = Path().apply {
                    moveTo(box.left + s * 0.22f, cy + s * 0.02f)
                    lineTo(box.left + s * 0.42f, cy + s * 0.22f)
                    lineTo(box.left + s * 0.78f, cy - s * 0.2f)
                }
                c.drawPath(path, q)
            } else {
                q.color = cText
                q.style = Paint.Style.STROKE
                q.strokeWidth = 1.5f * density
                box.inset(q.strokeWidth / 2, q.strokeWidth / 2)
                c.drawRoundRect(box, radius, radius, q)
            }
        }
    }

    private inner class Toggle(val closed: Boolean) : Marker(1.5f) {
        override fun paint(c: Canvas, x: Float, cy: Float, size: Float, p: Paint) {
            val h = size * 0.42f
            val cx = x + size * 0.55f
            val path = Path()
            if (closed) {
                path.moveTo(cx - h * 0.4f, cy - h / 2); path.lineTo(cx + h * 0.5f, cy); path.lineTo(cx - h * 0.4f, cy + h / 2)
            } else {
                path.moveTo(cx - h / 2, cy - h * 0.4f); path.lineTo(cx + h / 2, cy - h * 0.4f); path.lineTo(cx, cy + h * 0.5f)
            }
            path.close()
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cText })
        }
    }

    private inner class QuoteBar : LeadingMarginSpan, Md {
        override fun getLeadingMargin(first: Boolean) = (16 * density).toInt()
        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence?, start: Int, end: Int, first: Boolean, layout: Layout?,
        ) {
            val q = Paint().apply { color = cText }
            c.drawRect(x.toFloat(), top.toFloat(), x + 3 * density, bottom.toFloat(), q)
        }
    }

    private inner class DividerLine : LineBackgroundSpan, Md {
        override fun drawBackground(
            c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lineNumber: Int,
        ) {
            val q = Paint().apply { color = cDivider }
            val y = (top + bottom) / 2f
            c.drawRect(left.toFloat(), y, right.toFloat(), y + density, q)
        }
    }

    private fun Editable.span(what: Any, s: Int, e: Int) {
        if (e > s) setSpan(what, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun restyle(e: Editable) {
        e.getSpans(0, e.length, Md::class.java).forEach { e.removeSpan(it) }
        var ls = 0
        while (ls <= e.length) {
            val le = lineEnd(e, ls)
            styleLine(e, ls, le)
            ls = le + 1
        }
    }

    private fun styleLine(e: Editable, ls: Int, le: Int) {
        val line = e.subSequence(ls, le)
        val b = MarkdownCore.parse(line)
        val ms = ls + b.indent
        val me = ls + b.markerEnd
        val depth = b.depth(line)
        if (depth > 0) e.span(Indent(1.5f * depth), ls, ms)

        when (b.kind) {
            Kind.DIVIDER -> {
                e.span(MdFg(Color.TRANSPARENT), ls, le)
                e.span(DividerLine(), ls, le)
                return
            }
            Kind.HEADING -> {
                e.span(Hidden(), ms, me)
                e.span(MdStyle(Typeface.BOLD), ls, le)
                e.span(MdSize(floatArrayOf(1.875f, 1.5f, 1.25f)[b.level - 1]), ls, le)
            }
            Kind.BULLET -> e.span(Bullet(depth, cText), ms, me)
            Kind.TODO -> {
                e.span(Check(b.checked), ms, me)
                if (b.checked) {
                    e.span(MdStrike(), me, le)
                    e.span(MdFg(cSecondary), me, le)
                }
            }
            Kind.TOGGLE -> {
                e.span(Toggle(isCollapsedLine(e, ls)), ms, me)
                if (b.level > 0) {
                    e.span(MdStyle(Typeface.BOLD), ls, le)
                    e.span(MdSize(floatArrayOf(1.875f, 1.5f, 1.25f)[b.level - 1]), ls, le)
                }
            }
            Kind.QUOTE -> {
                e.span(Hidden(), ms, me)
                e.span(QuoteBar(), ls, maxOf(le, ls + 1).coerceAtMost(e.length))
            }
            Kind.NUMBER, Kind.TEXT -> {}
        }

        for (r in MarkdownCore.inlines(line.subSequence(b.markerEnd, line.length))) {
            val o = me
            e.span(MdFg(cFaint), o + r.start, o + r.cStart)
            e.span(MdFg(cFaint), o + r.cEnd, o + r.end)
            val cs = o + r.cStart
            val ce = o + r.cEnd
            when (r.kind) {
                MarkdownCore.InlineKind.BOLD -> e.span(MdStyle(Typeface.BOLD), cs, ce)
                MarkdownCore.InlineKind.ITALIC -> e.span(MdStyle(Typeface.ITALIC), cs, ce)
                MarkdownCore.InlineKind.STRIKE -> e.span(MdStrike(), cs, ce)
                MarkdownCore.InlineKind.CODE -> {
                    e.span(MdBg(cCodeBg), o + r.start, o + r.end)
                    e.span(MdMono(), o + r.start, o + r.end)
                    e.span(MdSize(0.9f), o + r.start, o + r.end)
                    e.span(MdFg(cCodeText), cs, ce)
                }
            }
        }
    }
}
