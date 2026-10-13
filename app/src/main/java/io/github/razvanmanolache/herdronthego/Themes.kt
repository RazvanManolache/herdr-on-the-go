package io.github.razvanmanolache.herdronthego

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Color schemes. Solid tones per scheme (no gradient wash); the terminal
 * keeps its own ink so chat stays readable in every theme.
 */
data class AppTheme(
    val id: String,
    val name: String,
    val swatch: Color,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val dim: Color,
    val good: Color,
    val warn: Color,
    val idle: Color,
    val matchBg: Color,
    val trackBg: Color,
    // NOTE: match-hit glyph color (white on dark themes, ink on sand).
    val matchFg: Color = Color.White,
    val isLight: Boolean = false,
)

val THEMES = listOf(
    AppTheme(
        id = "graphite", name = "Graphite", swatch = Color(0xFF7CC4FF),
        bg = Color(0xFF111318), surface = Color(0xFF1B1E24),
        surface2 = Color(0xFF23272F), accent = Color(0xFF7CC4FF),
        onAccent = Color(0xFF0B0E12), text = Color(0xFFF2F4F7),
        dim = Color(0xFF9AA0AA), good = Color(0xFF4ADE80),
        warn = Color(0xFFFBBF24), idle = Color(0xFF6B7280),
        matchBg = Color(0xFF8A6700), trackBg = Color(0xFF2E3A4A),
    ),
    AppTheme(
        id = "abyss", name = "Abyss", swatch = Color(0xFF5EEAD4),
        bg = Color(0xFF000000), surface = Color(0xFF101214),
        surface2 = Color(0xFF1A1D20), accent = Color(0xFF5EEAD4),
        onAccent = Color(0xFF04211C), text = Color(0xFFE8ECEF),
        dim = Color(0xFF8A949C), good = Color(0xFF34D399),
        warn = Color(0xFFFBBF24), idle = Color(0xFF5B636B),
        matchBg = Color(0xFF0F766E), trackBg = Color(0xFF14302A),
    ),
    AppTheme(
        id = "forest", name = "Forest", swatch = Color(0xFFA3E635),
        bg = Color(0xFF0D1411), surface = Color(0xFF16211B),
        surface2 = Color(0xFF1E2C24), accent = Color(0xFFA3E635),
        onAccent = Color(0xFF1A2405), text = Color(0xFFEDF2E9),
        dim = Color(0xFF93A08D), good = Color(0xFF4ADE80),
        warn = Color(0xFFFBBF24), idle = Color(0xFF5F6B58),
        matchBg = Color(0xFF65A30D), trackBg = Color(0xFF243823),
    ),
    AppTheme(
        id = "ember", name = "Ember", swatch = Color(0xFFFFAB91),
        bg = Color(0xFF150E0D), surface = Color(0xFF231413),
        surface2 = Color(0xFF2E1A18), accent = Color(0xFFFFAB91),
        onAccent = Color(0xFF2A0F08), text = Color(0xFFF7EDE8),
        dim = Color(0xFFA89890), good = Color(0xFF6EE7A0),
        warn = Color(0xFFFBBF24), idle = Color(0xFF6E5D55),
        matchBg = Color(0xFF9A3412), trackBg = Color(0xFF3A2620),
    ),
    AppTheme(
        id = "sand", name = "Sand", swatch = Color(0xFF0F766E),
        bg = Color(0xFFF1EDE2), surface = Color(0xFFFFFFFF),
        surface2 = Color(0xFFE7E1D2), accent = Color(0xFF0F766E),
        onAccent = Color(0xFFFFFFFF), text = Color(0xFF1C1917),
        dim = Color(0xFF6B6257), good = Color(0xFF15803D),
        warn = Color(0xFFB45309), idle = Color(0xFFA8A091),
        matchBg = Color(0xFFFCD34D), trackBg = Color(0xFFD3C9B2),
        matchFg = Color(0xFF1C1917),
        isLight = true,
    ),
)

fun AppTheme.colorScheme() = if (isLight) {
    lightColorScheme(
        background = bg, surface = surface, primary = accent,
        onBackground = text, onSurface = text)
} else {
    darkColorScheme(
        background = bg, surface = surface, primary = accent,
        onBackground = text, onSurface = text)
}

fun statusColor(t: AppTheme, status: String): Color = when (status.lowercase()) {
    "working" -> t.good
    "idle" -> t.warn
    // NOTE: finished work reads calm grey, never amber.
    "done" -> t.idle
    else -> t.idle
}

/** Initials avatar for tab/agent labels ("PC ANdroid VR" -> "PV"). */
fun initials(label: String): String {
    val words = label.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    if (words.size == 1) return words[0].take(2).uppercase()
    return (words[0].first() + "" + words[1].first()).uppercase()
}
