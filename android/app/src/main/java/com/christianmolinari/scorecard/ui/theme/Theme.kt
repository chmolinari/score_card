package com.christianmolinari.scorecard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

// Shared "warm & playful" visual language: a coral/teal/amber palette, a warm
// gradient background, floating rounded-card tiles with soft shadows, and
// colorful name avatars. Screens opt in via the helpers here so the look stays
// consistent and is defined in exactly one place.

// Core accents. Each adapts to light/dark so the warmth survives both.
private val CoralLight = Color(0xFFFF6B6B)
private val CoralDark = Color(0xFFFF8A8A)
private val TealLight = Color(0xFF12B3A6)
private val TealDark = Color(0xFF32D6C6)
private val AmberLight = Color(0xFFF5A314)
private val AmberDark = Color(0xFFFBBF24)
private val PlumLight = Color(0xFF7C5CFF)
private val PlumDark = Color(0xFF9F8BFF)
private val SkyLight = Color(0xFF3B82F6)
private val SkyDark = Color(0xFF60A5FA)

// Surface for the floating card tiles. A warm white that lifts off the
// gradient, and an elevated warm charcoal in the dark.
private val CardSurfaceLight = Color(0xFFFFFFFF)
private val CardSurfaceDark = Color(0xFF2A2632)

// Gradient backdrop endpoints — a soft, cool near-white that keeps the
// focus on the card tiles. The accents (coral/amber) stay warm against it.
private val BackgroundTopLight = Color(0xFFF7FAFC)
private val BackgroundTopDark = Color(0xFF14171C)
private val BackgroundBottomLight = Color(0xFFE9F0F5)
private val BackgroundBottomDark = Color(0xFF171D24)

object ThemeColors {
    val coral: Color @Composable get() = pick(CoralLight, CoralDark)
    val teal: Color @Composable get() = pick(TealLight, TealDark)
    val amber: Color @Composable get() = pick(AmberLight, AmberDark)
    val plum: Color @Composable get() = pick(PlumLight, PlumDark)
    val sky: Color @Composable get() = pick(SkyLight, SkyDark)

    // The primary brand/accent color (navigation bar, prominent buttons).
    val accent: Color @Composable get() = coral

    val cardSurface: Color @Composable get() = pick(CardSurfaceLight, CardSurfaceDark)
    val backgroundTop: Color @Composable get() = pick(BackgroundTopLight, BackgroundTopDark)
    val backgroundBottom: Color @Composable get() = pick(BackgroundBottomLight, BackgroundBottomDark)

    // A stable, pleasant accent color for a given name — used for avatars and
    // per-competitor highlights so the same person always gets the same hue.
    // Must hash exactly like iOS Theme.accent(for:) so the same name maps to
    // the same color on both platforms; Swift's Int is 64-bit, hence the fold
    // runs on Long with two's-complement wrapping.
    @Composable
    fun accentFor(name: String): Color {
        val palette = listOf(coral, teal, amber, plum, sky)
        var hash = 5381L
        var index = 0
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            hash = hash * 33 + codePoint
            index += Character.charCount(codePoint)
        }
        return palette[(abs(hash) % palette.size).toInt()]
    }

    @Composable
    private fun pick(light: Color, dark: Color): Color =
        if (isSystemInDarkTheme()) dark else light
}

@Composable
fun ScoreCardTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = CoralDark,
            secondary = TealDark,
            tertiary = AmberDark,
            background = BackgroundTopDark,
            surface = CardSurfaceDark,
        )
    } else {
        lightColorScheme(
            primary = CoralLight,
            secondary = TealLight,
            tertiary = AmberLight,
            background = BackgroundTopLight,
            surface = CardSurfaceLight,
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
