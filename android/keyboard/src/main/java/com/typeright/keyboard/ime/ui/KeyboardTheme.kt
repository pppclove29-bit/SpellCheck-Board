package com.typeright.keyboard.ime.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class KeyboardColors(
    val background: Color,
    val key: Color,
    val keyPressed: Color,
    val functionKey: Color,
    val accentKey: Color,
    val keyText: Color,
    val accentText: Color,
    val hintText: Color,
    val bar: Color,
    val chip: Color,
    val chipText: Color,
    val chipBorder: Color,
    val accent: Color,
    val danger: Color,
    val bubble: Color,
    val bubbleText: Color,
    val tip: Color,
)

private val Light = KeyboardColors(
    background = Color(0xFFE9ECF1),
    key = Color(0xFFFFFFFF),
    keyPressed = Color(0xFFC9D3E3),
    functionKey = Color(0xFFD3D9E2),
    accentKey = Color(0xFF2E6BE6),
    keyText = Color(0xFF1B1F24),
    accentText = Color(0xFFFFFFFF),
    hintText = Color(0xFF6B7380),
    bar = Color(0xFFF4F6F9),
    chip = Color(0xFFFFFFFF),
    chipText = Color(0xFF1B1F24),
    chipBorder = Color(0xFFD0D6DF),
    accent = Color(0xFF2E6BE6),
    danger = Color(0xFFD93025),
    bubble = Color(0xFFFFF1DC),
    bubbleText = Color(0xFF4A2E00),
    tip = Color(0xFFE8F3EA),
)

private val Dark = KeyboardColors(
    background = Color(0xFF1C1F24),
    key = Color(0xFF3A3F47),
    keyPressed = Color(0xFF555C66),
    functionKey = Color(0xFF2B2F36),
    accentKey = Color(0xFF5B8DEF),
    keyText = Color(0xFFF1F3F5),
    accentText = Color(0xFFFFFFFF),
    hintText = Color(0xFF9AA3AF),
    bar = Color(0xFF24282E),
    chip = Color(0xFF30353C),
    chipText = Color(0xFFF1F3F5),
    chipBorder = Color(0xFF454B54),
    accent = Color(0xFF7FA6F3),
    danger = Color(0xFFFF6B5E),
    bubble = Color(0xFF4A3A1E),
    bubbleText = Color(0xFFFFE8C2),
    tip = Color(0xFF203A28),
)

val LocalKeyboardColors = staticCompositionLocalOf { Light }

@Composable
fun keyboardColors(): KeyboardColors = if (isSystemInDarkTheme()) Dark else Light
