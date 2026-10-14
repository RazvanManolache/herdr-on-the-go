package io.github.razvanmanolache.herdronthego

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** One styled run; offsets index [ParsedAnsi.text]. */
data class AnsiSpan(val start: Int, val end: Int, val style: SpanStyle)

/** Plain terminal text plus the color/style runs parsed out of it. */
data class ParsedAnsi(val text: String, val spans: List<AnsiSpan>)

/**
 * Minimal SGR parser so agent output looks like it does on desktop.
 * Only SGR (CSI ... m) carries style; every other escape (cursor moves,
 * erases, OSC hyperlinks, charset selects) is dropped. Runs that never
 * set an attribute emit no span, so the theme's own ink stays in charge.
 * SGR state intentionally does not cross line boundaries: each rendered
 * line starts from the default style, matching how the chat is sliced.
 */
object Ansi {
    private val seq = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +
        "|\u001B\\[[0-9;?]*[A-Za-z]" +
        "|\u001B[()][0-9A-Za-z]" +
        "|\u001B[=>M78c]" +
        // NOTE: a chunk cut mid-escape (streaming reads) drops the partial tail.
        "|\u001B\\][^\u0007\u001B]*" +
        "|\u001B\\[[0-9;?]*" +
        "|\u001B$")

    // NOTE: xterm palette. Index 0 (black) maps to a readable grey on
    // dark themes instead of invisible black; light themes get darkened
    // variants plus a true black.
    private val dark16 = listOf(
        0xFF9AA0AA, 0xFFF87171, 0xFF7EE787, 0xFFE3B341,
        0xFF79C0FF, 0xFFD2A8FF, 0xFF76E3EA, 0xFFE6EDF3,
        0xFF8B949E, 0xFFFF9E9E, 0xFF56D364, 0xFFEAC54F,
        0xFFA5D6FF, 0xFFE8BCFF, 0xFFA5F3FC, 0xFFFFFFFF)
    private val light16 = listOf(
        0xFF000000, 0xFFB91C1C, 0xFF15803D, 0xFF92400E,
        0xFF1D4ED8, 0xFF7E22CE, 0xFF0E7490, 0xFF44403C,
        0xFF57534E, 0xFFDC2626, 0xFF16A34A, 0xFFCA8A04,
        0xFF2563EB, 0xFF9333EA, 0xFF0891B2, 0xFF1C1917)

    private val cube = intArrayOf(0, 95, 135, 175, 215, 255)

    private fun cubeColor(n: Int): Color {
        val v = n - 16
        return Color(cube[(v / 36) % 6], cube[(v / 6) % 6], cube[v % 6])
    }

    private fun grayColor(n: Int): Color {
        val g = 8 + 10 * (n - 232)
        return Color(g, g, g)
    }

    private fun extendedColor(dark: Boolean, kind: Int, args: List<Int>, at: Int): Pair<Color?, Int> {
        if (at >= args.size) return null to at
        return when (args[at]) {
            5 -> if (at + 1 < args.size) indexedColor(dark, args[at + 1]) to at + 2 else null to at + 1
            2 -> if (at + 3 < args.size) rgbColor(dark, args[at + 1], args[at + 2], args[at + 3]) to at + 4
            else null to at + 1
            else -> null to at + 1
        }
    }

    private fun indexedColor(dark: Boolean, n: Int): Color? {
        if (n < 0) return null
        if (n < 16) return Color(if (dark) dark16[n] else light16[n])
        if (n < 232) return adapt(dark, cubeColor(n))
        if (n < 256) return adapt(dark, grayColor(n))
        return null
    }

    private fun rgbColor(dark: Boolean, r: Int, g: Int, b: Int): Color? {
        if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
        return adapt(dark, Color(r, g, b))
    }

    private fun adapt(dark: Boolean, c: Color): Color {
        if (dark) return c
        fun dim(v: Float) = (v * 0.7f).coerceIn(0f, 1f)
        return Color(dim(c.red), dim(c.green), dim(c.blue), c.alpha)
    }

    private class State(var dark: Boolean) {
        var fg: Color? = null
        var bg: Color? = null
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var reverse = false
        fun reset() {
            fg = null; bg = null; bold = false; italic = false
            underline = false; strike = false; reverse = false
        }
        fun apply(params: List<Int>) {
            var i = 0
            if (params.isEmpty()) { reset(); return }
            while (i < params.size) {
                when (val p = params[i]) {
                    0 -> reset()
                    1 -> bold = true
                    2 -> bold = false
                    3 -> italic = true
                    4 -> underline = true
                    7 -> reverse = true
                    9 -> strike = true
                    22 -> bold = false
                    23 -> italic = false
                    24 -> underline = false
                    27 -> reverse = false
                    29 -> strike = false
                    in 30..37 -> fg = indexedColor(dark, p - 30)
                    in 90..97 -> fg = indexedColor(dark, p - 90 + 8)
                    38 -> {
                        val (c, next) = extendedColor(dark, p, params, i + 1)
                        if (c != null) fg = c
                        i = next - 1
                    }
                    39 -> fg = null
                    in 40..47 -> bg = indexedColor(dark, p - 40)
                    in 100..107 -> bg = indexedColor(dark, p - 100 + 8)
                    48 -> {
                        val (c, next) = extendedColor(dark, p, params, i + 1)
                        if (c != null) bg = c
                        i = next - 1
                    }
                    49 -> bg = null
                }
                i++
            }
        }
        fun isPlain() = fg == null && bg == null && !bold && !italic &&
            !underline && !strike && !reverse
        fun style(): SpanStyle {
            val (f, b) = if (reverse) bg to fg else fg to bg
            var deco = TextDecoration.None
            if (underline) deco += TextDecoration.Underline
            if (strike) deco += TextDecoration.LineThrough
            return SpanStyle(
                color = f ?: Color.Unspecified,
                background = b ?: Color.Unspecified,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (deco == TextDecoration.None) null else deco)
        }
    }

    /** Parse SGR colors out of terminal output. [darkBackground] picks the palette. */
    fun parse(input: String, darkBackground: Boolean): ParsedAnsi {
        val text = StringBuilder()
        val spans = mutableListOf<AnsiSpan>()
        val st = State(darkBackground)
        var pos = 0
        var runStart = 0
        var runStyled = false
        fun closeRun() {
            if (runStyled && text.length > runStart) {
                spans.add(AnsiSpan(runStart, text.length, st.style()))
            }
            runStart = text.length
            runStyled = !st.isPlain()
        }
        for (m in seq.findAll(input)) {
            if (m.range.first > pos) {
                text.append(input, pos, m.range.first)
            }
            val token = m.value
            if (token.startsWith("\u001B[") && token.endsWith("m")) {
                closeRun()
                val body = token.substring(2, token.length - 1).trimEnd('?')
                val params = if (body.isEmpty()) listOf(0)
                else body.split(';').map { it.toIntOrNull() ?: 0 }
                st.apply(params)
                closeRun()
            }
            pos = m.range.last + 1
        }
        if (pos < input.length) text.append(input, pos, input.length)
        closeRun()
        return ParsedAnsi(text.toString(), spans)
    }

    /** Plain text with every escape sequence removed. */
    fun strip(input: String): String = parse(input, true).text
}
