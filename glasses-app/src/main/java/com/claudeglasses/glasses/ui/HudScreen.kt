package com.claudeglasses.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.claudeglasses.glasses.R
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Display size presets for the 480x398 pixel HUD
 * Each preset optimizes for different character counts vs readability
 */
enum class HudDisplaySize(val fontSizeSp: Int, val cols: Int, val rows: Int, val label: String) {
    COMPACT(10, 60, 25, "Compact"),     // Max characters, smaller text
    NORMAL(12, 50, 20, "Normal"),       // Balanced
    COMFORTABLE(14, 40, 16, "Comfortable"), // Larger, easier to read
    LARGE(16, 35, 14, "Large")          // Maximum readability
}

/**
 * Terminal state data class
 */
data class TerminalState(
    val lines: List<String> = emptyList(),
    val scrollPosition: Int = 0,
    val cursorLine: Int = 0,
    val mode: Mode = Mode.SCROLL,
    val displaySize: HudDisplaySize = HudDisplaySize.NORMAL,
    val isConnected: Boolean = false
) {
    val visibleLines: Int get() = displaySize.rows

    enum class Mode {
        SCROLL,    // Swipes scroll the terminal
        NAVIGATE,  // Swipes send arrow keys
        COMMAND    // Swipes send tab/shift-tab
    }
}

/**
 * HUD-optimized terminal display for Rokid Glasses
 *
 * Design considerations:
 * - Pure black background (blends with real world on monochrome display)
 * - High contrast neon green/cyan text
 * - Centered content to avoid edge visibility issues
 * - Large, readable monospace font
 * - Minimal UI chrome
 */
@Composable
fun HudScreen(state: TerminalState) {
    val listState = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Use JetBrains Mono for proper monospace box-drawing characters
    val monoFontFamily = remember { FontFamily(Font(R.font.jetbrains_mono)) }

    // Auto-scroll when position changes
    LaunchedEffect(state.scrollPosition) {
        if (state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.scrollPosition)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)  // Pure black for HUD
            .padding(horizontal = 16.dp, vertical = 12.dp)  // Reduced padding for more space
    ) {
        // Calculate font size to fit 65 columns in available width
        val targetColumns = 65
        val referenceText = "M".repeat(targetColumns)
        val referenceFontSize = 12.sp

        val fontSize = remember(maxWidth, monoFontFamily) {
            val referenceStyle = TextStyle(
                fontFamily = monoFontFamily,
                fontSize = referenceFontSize,
                letterSpacing = 0.sp
            )
            val measuredWidth = textMeasurer.measure(referenceText, referenceStyle).size.width
            val availableWidthPx = with(density) { maxWidth.toPx() }
            // Scale to fit with tiny margin for rounding errors (1%)
            val scaledSize = referenceFontSize.value * (availableWidthPx / measuredWidth) * 0.99f
            scaledSize.coerceIn(6f, 24f).sp
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status bar at top
            StatusBar(
                mode = state.mode,
                lineInfo = "${state.scrollPosition + 1}/${state.lines.size}",
                isConnected = state.isConnected,
                displaySize = state.displaySize,
                fontFamily = monoFontFamily
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Terminal content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.lines.isEmpty()) {
                    // Empty state
                    Text(
                        text = "Waiting for connection...",
                        color = HudColors.dimText,
                        fontSize = fontSize,
                        fontFamily = monoFontFamily,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(state.lines) { index, line ->
                            TerminalLine(
                                text = line,
                                isCurrentLine = index == state.cursorLine,
                                lineNumber = index + 1,
                                fontSize = fontSize,
                                fontFamily = monoFontFamily
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Gesture hints at bottom
            GestureHints(mode = state.mode, displaySize = state.displaySize, fontFamily = monoFontFamily)
        }
    }
}

@Composable
private fun StatusBar(
    mode: TerminalState.Mode,
    lineInfo: String,
    isConnected: Boolean,
    displaySize: HudDisplaySize,
    fontFamily: FontFamily
) {
    val statusFontSize = (displaySize.fontSizeSp - 2).coerceAtLeast(8).sp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mode indicator
        Text(
            text = "[${mode.name}]",
            color = when (mode) {
                TerminalState.Mode.SCROLL -> HudColors.cyan
                TerminalState.Mode.NAVIGATE -> HudColors.yellow
                TerminalState.Mode.COMMAND -> HudColors.green
            },
            fontSize = statusFontSize,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold
        )

        // Connection status
        Text(
            text = if (isConnected) "●" else "○",
            color = if (isConnected) HudColors.green else HudColors.dimText,
            fontSize = (statusFontSize.value + 2).sp
        )

        // Line info
        Text(
            text = lineInfo,
            color = HudColors.dimText,
            fontSize = statusFontSize,
            fontFamily = fontFamily
        )
    }
}

@Composable
private fun TerminalLine(
    text: String,
    isCurrentLine: Boolean,
    lineNumber: Int,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    // Simple text display - no line numbers for maximum content width
    Text(
        text = text,
        color = if (isCurrentLine) HudColors.cyan else HudColors.primaryText,
        fontSize = fontSize,
        fontFamily = fontFamily,
        lineHeight = fontSize,  // Match line height to font size for tight spacing
        letterSpacing = 0.sp,   // No extra spacing between characters
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun GestureHints(mode: TerminalState.Mode, displaySize: HudDisplaySize, fontFamily: FontFamily) {
    val hints = when (mode) {
        TerminalState.Mode.SCROLL -> "↑↓ Scroll  ●● Mode  ⟳ ESC"
        TerminalState.Mode.NAVIGATE -> "↑↓ Arrow  ●● Mode  ⟳ ESC"
        TerminalState.Mode.COMMAND -> "← ⇧Tab  → Tab  ●● Mode"
    }
    val hintFontSize = (displaySize.fontSizeSp - 3).coerceAtLeast(8).sp

    Text(
        text = hints,
        color = HudColors.dimText,
        fontSize = hintFontSize,
        fontFamily = fontFamily,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Color palette optimized for monochrome HUD display
 */
object HudColors {
    val primaryText = Color(0xFF00FF00)    // Bright green - most visible
    val cyan = Color(0xFF00FFFF)           // Cyan - stands out well
    val green = Color(0xFF39FF14)          // Neon green
    val yellow = Color(0xFFFFFF00)         // Yellow for warnings
    val dimText = Color(0xFF666666)        // Dimmed text for secondary info
    val error = Color(0xFFFF4444)          // Red for errors
}
