package com.parth.mobileclaw.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Exact Element X Compound Tokens (Light & Dark) ─────────────────────────

// LIGHT TOKENS
private val LightBgCanvasDefault = Color(0xffffffff)
private val LightBgSubtleSecondary = Color(0xfff0f2f5)
private val LightBgSubtlePrimary = Color(0xffe1e6ec)
private val LightBorderDisabled = Color(0xffcdd3da)

private val LightBgActionPrimaryRest = Color(0xff1b1d22)
private val LightTextOnSolidPrimary = Color(0xffffffff)
private val LightTextPrimary = Color(0xff1b1d22)
private val LightTextSecondary = Color(0xff656d77)
private val LightTextDisabled = Color(0xff818a95)

private val LightIconAccentPrimary = Color(0xff007a61)
private val LightTextCriticalPrimary = Color(0xffd51928)

private val LightBubbleTool = Color(0xffe3f7ed) // Green300 (Subtle)
private val LightBubbleCode = Color(0xffe1e6ec) // bgSubtlePrimary


// DARK TOKENS
private val DarkBgCanvasDefault = Color(0xff101317)
private val DarkBgSubtleSecondary = Color(0xff1d1f24)
private val DarkBgSubtlePrimary = Color(0xff26282d)
private val DarkBorderDisabled = Color(0xff323539)

private val DarkBgActionPrimaryRest = Color(0xffebeef2)
private val DarkTextOnSolidPrimary = Color(0xff101317)
private val DarkTextPrimary = Color(0xffebeef2)
private val DarkTextSecondary = Color(0xff808994)
private val DarkTextDisabled = Color(0xff656c76)

private val DarkIconAccentPrimary = Color(0xff129a78)
private val DarkTextCriticalPrimary = Color(0xfffd3e3c)

private val DarkBubbleTool = Color(0xff1b2b2b)
private val DarkBubbleCode = Color(0xff16181d)


// ── Material 3 Schemes ──────────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary = LightBgActionPrimaryRest,
    onPrimary = LightTextOnSolidPrimary,
    primaryContainer = LightBgSubtlePrimary,
    onPrimaryContainer = LightTextPrimary,
    
    // Accent (Element Green) mapped to secondary
    secondary = LightIconAccentPrimary,
    onSecondary = LightTextOnSolidPrimary,
    secondaryContainer = LightIconAccentPrimary.copy(alpha = 0.15f),
    onSecondaryContainer = LightIconAccentPrimary,
    
    background = LightBgCanvasDefault,
    onBackground = LightTextPrimary,
    surface = LightBgSubtleSecondary,
    onSurface = LightTextPrimary,
    surfaceVariant = LightBgSubtlePrimary,
    onSurfaceVariant = LightTextSecondary,
    
    error = LightTextCriticalPrimary,
    onError = LightTextOnSolidPrimary,
    outline = LightBorderDisabled,
    outlineVariant = LightTextDisabled
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkBgActionPrimaryRest,
    onPrimary = DarkTextOnSolidPrimary,
    primaryContainer = DarkBgSubtlePrimary,
    onPrimaryContainer = DarkTextPrimary,
    
    // Accent (Element Green) mapped to secondary
    secondary = DarkIconAccentPrimary,
    onSecondary = DarkTextOnSolidPrimary,
    secondaryContainer = DarkIconAccentPrimary.copy(alpha = 0.15f),
    onSecondaryContainer = DarkIconAccentPrimary,
    
    background = DarkBgCanvasDefault,
    onBackground = DarkTextPrimary,
    surface = DarkBgSubtleSecondary,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkBgSubtlePrimary,
    onSurfaceVariant = DarkTextSecondary,
    
    error = DarkTextCriticalPrimary,
    onError = DarkTextOnSolidPrimary,
    outline = DarkBorderDisabled,
    outlineVariant = DarkTextDisabled
)

// Legacy bubbles logic helpers (if still referenced)
val BubbleMe: Color @Composable get() = if (isSystemInDarkTheme()) DarkBorderDisabled else LightBorderDisabled
val BubbleOther: Color @Composable get() = if (isSystemInDarkTheme()) DarkBgSubtlePrimary else LightBgSubtlePrimary
val BubbleTool: Color @Composable get() = if (isSystemInDarkTheme()) DarkBubbleTool else LightBubbleTool
val BubbleCode: Color @Composable get() = if (isSystemInDarkTheme()) DarkBubbleCode else LightBubbleCode

private val AppTypography = Typography()

@Composable
fun MobileClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
