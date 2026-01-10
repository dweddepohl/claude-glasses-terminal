package com.claudeglasses.glasses.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Focus hierarchy levels for the glasses UI
 */
enum class FocusLevel {
    AREA_SELECT,   // Level 0: Swipe between areas
    AREA_FOCUSED,  // Level 1: Within an area
    FINE_CONTROL   // Level 2: Character/selection mode
}

/**
 * The three main areas of the UI
 */
enum class FocusArea {
    CONTENT,  // Terminal output (scrollable)
    INPUT,    // Claude's prompts/questions
    COMMAND   // Quick action bar
}

/**
 * Content area sub-modes for progressive interaction depth
 */
enum class ContentMode {
    PAGE,       // Swipe to scroll by page
    LINE,       // Swipe to move line by line
    CHARACTER,  // Swipe to move character by character
    SELECTION   // Selecting text
}

/**
 * Quick commands available in the command bar
 */
enum class QuickCommand(val icon: String, val label: String, val key: String) {
    ESCAPE("✕", "ESC", "escape"),
    ENTER("↵", "ENTER", "enter"),
    SHIFT_TAB("⇤", "S-TAB", "shift_tab"),
    TAB("⇥", "TAB", "tab"),
    CLEAR("⌫", "CLEAR", "ctrl_u"),
    SESSION("◎", "SESSION", "list_sessions")
}

/**
 * Hierarchical focus state for the glasses UI
 */
data class FocusState(
    val level: FocusLevel = FocusLevel.AREA_SELECT,
    val focusedArea: FocusArea = FocusArea.CONTENT,
    val contentMode: ContentMode = ContentMode.PAGE,
    val selectedLine: Int = 0,
    val cursorPosition: Int = 0,
    val selectionStart: Int? = null,
    val commandIndex: Int = 0,
    val inputOptionIndex: Int = 0,
    val pendingInput: String = ""  // Text copied from selection or voice
)

/**
 * Detected prompt types from Claude's output
 */
sealed class DetectedPrompt {
    data class MultipleChoice(val options: List<String>, val selectedIndex: Int) : DetectedPrompt()
    data class Confirmation(val yesDefault: Boolean) : DetectedPrompt()
    data class TextInput(val placeholder: String) : DetectedPrompt()
    object None : DetectedPrompt()
}

/**
 * Voice input states for HUD display
 */
sealed class VoiceInputState {
    object Idle : VoiceInputState()
    object Listening : VoiceInputState()
    object Recognizing : VoiceInputState()
    data class Error(val message: String) : VoiceInputState()
}

/**
 * Terminal state data class with hierarchical focus model
 */
data class TerminalState(
    val lines: List<String> = emptyList(),
    val scrollPosition: Int = 0,
    val scrollTrigger: Int = 0,
    val cursorLine: Int = 0,
    val focus: FocusState = FocusState(),
    val detectedPrompt: DetectedPrompt = DetectedPrompt.None,
    val displaySize: HudDisplaySize = HudDisplaySize.NORMAL,
    val isConnected: Boolean = false,
    val voiceState: VoiceInputState = VoiceInputState.Idle,
    val voiceText: String = "",
    // Session picker state
    val showSessionPicker: Boolean = false,
    val availableSessions: List<String> = emptyList(),
    val currentSession: String = "",
    val selectedSessionIndex: Int = 0
) {
    val visibleLines: Int get() = displaySize.rows

    // Convenience properties for focus state
    val focusLevel: FocusLevel get() = focus.level
    val focusedArea: FocusArea get() = focus.focusedArea
    val contentMode: ContentMode get() = focus.contentMode

    // Legacy mode compatibility (for gradual migration)
    @Deprecated("Use focus.focusedArea instead")
    val mode: LegacyMode get() = when {
        focus.focusedArea == FocusArea.COMMAND -> LegacyMode.COMMAND
        focus.focusedArea == FocusArea.INPUT -> LegacyMode.NAVIGATE
        else -> LegacyMode.SCROLL
    }

    @Deprecated("Use FocusArea instead")
    enum class LegacyMode { SCROLL, NAVIGATE, COMMAND }
}

// ============================================================================
// BRIGHTNESS ANIMATION UTILITIES
// ============================================================================

/**
 * Calculate brightness alpha based on focus state
 */
@Composable
fun focusBrightness(isFocused: Boolean, isPulsing: Boolean = false): Float {
    val baseAlpha = if (isFocused) 1f else 0.4f

    return if (isPulsing && isFocused) {
        // Gentle pulsing for focused area at Level 0
        val infiniteTransition = rememberInfiniteTransition(label = "focus")
        infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(600)),
            label = "pulse"
        ).value
    } else {
        animateFloatAsState(
            targetValue = baseAlpha,
            animationSpec = tween(200),
            label = "brightness"
        ).value
    }
}

// ============================================================================
// MAIN HUD SCREEN
// ============================================================================

/**
 * HUD-optimized terminal display for Rokid Glasses
 *
 * Design: Hierarchical focus-based interaction with three areas:
 * - Content Area: Terminal output (scrollable, selectable)
 * - Input Area: Claude's prompts/questions
 * - Command Bar: Quick action buttons
 *
 * Focus is indicated via brightness (40% unfocused, 100% focused)
 */
@Composable
fun HudScreen(
    state: TerminalState,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Use JetBrains Mono for proper monospace box-drawing characters
    val monoFontFamily = remember { FontFamily(Font(R.font.jetbrains_mono)) }

    // Double-tap detection state
    var lastTapTime by remember { mutableStateOf(0L) }
    var pendingTapJob by remember { mutableStateOf<Job?>(null) }
    val doubleTapTimeout = 300L

    // Auto-scroll when position or trigger changes
    LaunchedEffect(state.scrollPosition, state.scrollTrigger) {
        if (state.lines.isNotEmpty() && state.scrollPosition < state.lines.size) {
            Log.d("HudScreen", "Auto-scrolling to position ${state.scrollPosition}")
            listState.animateScrollToItem(state.scrollPosition)
        }
    }

    // Calculate focus-based brightness for each area
    val isAreaSelectLevel = state.focusLevel == FocusLevel.AREA_SELECT
    val contentFocused = state.focusedArea == FocusArea.CONTENT
    val inputFocused = state.focusedArea == FocusArea.INPUT
    val commandFocused = state.focusedArea == FocusArea.COMMAND

    val contentAlpha = focusBrightness(contentFocused, isPulsing = isAreaSelectLevel)
    val inputAlpha = focusBrightness(inputFocused, isPulsing = isAreaSelectLevel)
    val commandAlpha = focusBrightness(commandFocused, isPulsing = isAreaSelectLevel)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        Log.d("HudScreen", "Compose onTap detected at $it")
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < doubleTapTimeout) {
                            Log.d("HudScreen", "Double tap detected")
                            pendingTapJob?.cancel()
                            pendingTapJob = null
                            lastTapTime = 0L
                            onDoubleTap()
                        } else {
                            lastTapTime = now
                            pendingTapJob?.cancel()
                            pendingTapJob = scope.launch {
                                delay(doubleTapTimeout)
                                if (lastTapTime == now) {
                                    Log.d("HudScreen", "Single tap confirmed")
                                    onTap()
                                }
                            }
                        }
                    },
                    onLongPress = {
                        Log.d("HudScreen", "Long press detected at $it")
                        pendingTapJob?.cancel()
                        onLongPress()
                    }
                )
            }
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
            val scaledSize = referenceFontSize.value * (availableWidthPx / measuredWidth) * 0.99f
            scaledSize.coerceIn(6f, 24f).sp
        }

        // Three-area vertical layout with brightness-based focus
        Column(modifier = Modifier.fillMaxSize()) {
            // Status bar (always dim, informational)
            StatusBar(
                focusedArea = state.focusedArea,
                focusLevel = state.focusLevel,
                lineInfo = "${state.scrollPosition + 1}/${state.lines.size}",
                isConnected = state.isConnected,
                displaySize = state.displaySize,
                fontFamily = monoFontFamily
            )

            Spacer(modifier = Modifier.height(4.dp))

            // CONTENT AREA - Terminal output (weight: fills remaining space)
            ContentArea(
                lines = state.lines,
                listState = listState,
                cursorLine = state.cursorLine,
                contentMode = state.contentMode,
                selectedLine = state.focus.selectedLine,
                cursorPosition = state.focus.cursorPosition,
                selectionStart = state.focus.selectionStart,
                fontSize = fontSize,
                fontFamily = monoFontFamily,
                alpha = contentAlpha,
                isFocused = contentFocused && state.focusLevel != FocusLevel.AREA_SELECT,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // INPUT AREA - Claude's prompts (fixed height, collapsible when None)
            InputArea(
                detectedPrompt = state.detectedPrompt,
                pendingInput = state.focus.pendingInput,
                selectedOptionIndex = state.focus.inputOptionIndex,
                displaySize = state.displaySize,
                fontFamily = monoFontFamily,
                alpha = inputAlpha,
                isFocused = inputFocused && state.focusLevel != FocusLevel.AREA_SELECT
            )

            Spacer(modifier = Modifier.height(4.dp))

            // COMMAND BAR or HINTS depending on focus
            val isAtBottom = state.scrollPosition >= maxOf(0, state.lines.size - state.visibleLines)
            val showScrollHints = contentFocused && state.focusLevel == FocusLevel.AREA_FOCUSED && !isAtBottom
            val showInputHints = inputFocused && state.focusLevel == FocusLevel.AREA_FOCUSED

            when {
                showScrollHints -> {
                    // Show hints when in SCROLL mode and not at bottom
                    ScrollModeHints(
                        displaySize = state.displaySize,
                        fontFamily = monoFontFamily
                    )
                }
                showInputHints -> {
                    // Show hints when in INPUT mode
                    InputModeHints(
                        displaySize = state.displaySize,
                        fontFamily = monoFontFamily
                    )
                }
                else -> {
                    // Show command bar
                    CommandBar(
                        commands = QuickCommand.values().toList(),
                        selectedIndex = state.focus.commandIndex,
                        displaySize = state.displaySize,
                        fontFamily = monoFontFamily,
                        alpha = commandAlpha,
                        isFocused = commandFocused && state.focusLevel != FocusLevel.AREA_SELECT
                    )
                }
            }
        }

        // Voice input overlay (shown when voice recognition is active)
        AnimatedVisibility(
            visible = state.voiceState !is VoiceInputState.Idle,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            VoiceInputOverlay(
                voiceState = state.voiceState,
                voiceText = state.voiceText,
                fontFamily = monoFontFamily
            )
        }

        // Session picker overlay
        AnimatedVisibility(
            visible = state.showSessionPicker,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SessionPickerOverlay(
                sessions = state.availableSessions,
                currentSession = state.currentSession,
                selectedIndex = state.selectedSessionIndex,
                fontFamily = monoFontFamily
            )
        }
    }
}

// ============================================================================
// CONTENT AREA
// ============================================================================

@Composable
private fun ContentArea(
    lines: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    cursorLine: Int,
    contentMode: ContentMode,
    selectedLine: Int,
    cursorPosition: Int,
    selectionStart: Int?,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily,
    alpha: Float,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
    ) {
        if (lines.isEmpty()) {
            Text(
                text = "Waiting for connection...",
                color = HudColors.dimText,
                fontSize = fontSize,
                fontFamily = fontFamily,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 64.dp) // Match Input + Command bar height
            ) {
                itemsIndexed(lines) { index, line ->
                    val isHighlighted = when (contentMode) {
                        ContentMode.LINE, ContentMode.CHARACTER, ContentMode.SELECTION ->
                            index == selectedLine && isFocused
                        else -> false
                    }
                    val isCurrentLine = index == cursorLine

                    ContentLine(
                        text = line,
                        lineIndex = index,
                        isCurrentLine = isCurrentLine,
                        isHighlighted = isHighlighted,
                        contentMode = contentMode,
                        cursorPosition = if (isHighlighted) cursorPosition else null,
                        selectionStart = if (isHighlighted) selectionStart else null,
                        fontSize = fontSize,
                        fontFamily = fontFamily
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentLine(
    text: String,
    lineIndex: Int,
    isCurrentLine: Boolean,
    isHighlighted: Boolean,
    contentMode: ContentMode,
    cursorPosition: Int?,
    selectionStart: Int?,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    val backgroundColor = when {
        isHighlighted -> HudColors.green.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    val textColor = when {
        isHighlighted -> HudColors.green
        isCurrentLine -> HudColors.cyan
        else -> HudColors.primaryText
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        // For character/selection mode, we could render character-by-character
        // For now, show the line with visual indication of cursor position
        if (isHighlighted && contentMode == ContentMode.CHARACTER && cursorPosition != null) {
            // Show cursor as inverse character
            Row {
                val beforeCursor = text.take(cursorPosition)
                val atCursor = text.getOrNull(cursorPosition)?.toString() ?: " "
                val afterCursor = text.drop(cursorPosition + 1)

                Text(
                    text = beforeCursor,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp
                )
                Text(
                    text = atCursor,
                    color = Color.Black,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp,
                    modifier = Modifier.background(HudColors.green)
                )
                Text(
                    text = afterCursor,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp
                )
            }
        } else if (isHighlighted && contentMode == ContentMode.SELECTION && selectionStart != null && cursorPosition != null) {
            // Show selection range
            val start = minOf(selectionStart, cursorPosition)
            val end = maxOf(selectionStart, cursorPosition)
            Row {
                val beforeSelection = text.take(start)
                val selected = text.substring(start, minOf(end + 1, text.length))
                val afterSelection = text.drop(end + 1)

                Text(
                    text = beforeSelection,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp
                )
                Text(
                    text = selected,
                    color = Color.Black,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp,
                    modifier = Modifier.background(HudColors.cyan)
                )
                Text(
                    text = afterSelection,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp
                )
            }
        } else {
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                fontFamily = fontFamily,
                lineHeight = fontSize,
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ============================================================================
// INPUT AREA
// ============================================================================

@Composable
private fun InputArea(
    detectedPrompt: DetectedPrompt,
    pendingInput: String,
    selectedOptionIndex: Int,
    displaySize: HudDisplaySize,
    fontFamily: FontFamily,
    alpha: Float,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val inputFontSize = (displaySize.fontSizeSp - 1).coerceAtLeast(8).sp

    // Collapse when no prompt detected and no pending input
    if (detectedPrompt is DetectedPrompt.None && pendingInput.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(20.dp)
                .alpha(alpha)
                .border(1.dp, HudColors.dimText.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "No prompt",
                color = HudColors.dimText,
                fontSize = inputFontSize,
                fontFamily = fontFamily
            )
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) HudColors.green else HudColors.dimText.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        when (detectedPrompt) {
            is DetectedPrompt.MultipleChoice -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    detectedPrompt.options.forEachIndexed { index, option ->
                        val isSelected = index == selectedOptionIndex
                        Text(
                            text = if (isSelected) "▶ $option" else "  $option",
                            color = if (isSelected) HudColors.green else HudColors.primaryText,
                            fontSize = inputFontSize,
                            fontFamily = fontFamily,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            is DetectedPrompt.Confirmation -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val yesSelected = selectedOptionIndex == 0
                    Text(
                        text = if (yesSelected) "▶ ✓ Yes" else "  ✓ Yes",
                        color = if (yesSelected) HudColors.green else HudColors.primaryText,
                        fontSize = inputFontSize,
                        fontFamily = fontFamily,
                        fontWeight = if (yesSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = if (!yesSelected) "▶ ✕ No" else "  ✕ No",
                        color = if (!yesSelected) HudColors.green else HudColors.primaryText,
                        fontSize = inputFontSize,
                        fontFamily = fontFamily,
                        fontWeight = if (!yesSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            is DetectedPrompt.TextInput -> {
                Row {
                    Text(
                        text = "❯ ",
                        color = HudColors.cyan,
                        fontSize = inputFontSize,
                        fontFamily = fontFamily
                    )
                    Text(
                        text = pendingInput.ifEmpty { detectedPrompt.placeholder },
                        color = if (pendingInput.isNotEmpty()) HudColors.green else HudColors.dimText,
                        fontSize = inputFontSize,
                        fontFamily = fontFamily
                    )
                }
            }

            DetectedPrompt.None -> {
                // Show pending input with ❯ indicator
                Row {
                    Text(
                        text = "❯ ",
                        color = HudColors.cyan,
                        fontSize = inputFontSize,
                        fontFamily = fontFamily
                    )
                    Text(
                        text = pendingInput.ifEmpty { "..." },
                        color = if (pendingInput.isNotEmpty()) HudColors.green else HudColors.dimText,
                        fontSize = inputFontSize,
                        fontFamily = fontFamily
                    )
                }
            }
        }
    }
}

// ============================================================================
// COMMAND BAR
// ============================================================================

@Composable
private fun CommandBar(
    commands: List<QuickCommand>,
    selectedIndex: Int,
    displaySize: HudDisplaySize,
    fontFamily: FontFamily,
    alpha: Float,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val commandFontSize = (displaySize.fontSizeSp - 2).coerceAtLeast(8).sp
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        commands.forEachIndexed { index, command ->
            val isSelected = index == selectedIndex && isFocused

            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) HudColors.green.copy(alpha = 0.3f) else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = if (isSelected) HudColors.green else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = command.icon,
                        color = if (isSelected) HudColors.green else HudColors.primaryText,
                        fontSize = (commandFontSize.value + 2).sp
                    )
                    Text(
                        text = command.label,
                        color = if (isSelected) HudColors.green else HudColors.dimText,
                        fontSize = commandFontSize,
                        fontFamily = fontFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ============================================================================
// SCROLL MODE HINTS
// ============================================================================

@Composable
private fun ScrollModeHints(
    displaySize: HudDisplaySize,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val hintFontSize = (displaySize.fontSizeSp - 2).coerceAtLeast(8).sp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tap = scroll to bottom hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TAP",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= jump to end",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }

        // Double-tap = exit hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2×TAP",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= exit scroll",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// INPUT MODE HINTS
// ============================================================================

@Composable
private fun InputModeHints(
    displaySize: HudDisplaySize,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val hintFontSize = (displaySize.fontSizeSp - 2).coerceAtLeast(8).sp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tap = Enter hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TAP",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= enter",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }

        // Up/Down hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "↑↓",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= navigate",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }

        // Hold = voice hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HOLD",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= voice",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }

        // Double-tap = exit hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2×TAP",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= exit",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// STATUS BAR
// ============================================================================

@Composable
private fun StatusBar(
    focusedArea: FocusArea,
    focusLevel: FocusLevel,
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
        // Focus area indicator - simple labels
        val areaLabel = when (focusedArea) {
            FocusArea.CONTENT -> "SCROLL"
            FocusArea.INPUT -> "INPUT"
            FocusArea.COMMAND -> "COMMAND"
        }
        Text(
            text = "[$areaLabel]",
            color = when (focusedArea) {
                FocusArea.CONTENT -> HudColors.cyan
                FocusArea.INPUT -> HudColors.yellow
                FocusArea.COMMAND -> HudColors.green
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

// ============================================================================
// VOICE INPUT OVERLAY
// ============================================================================

/**
 * Voice input overlay shown when voice recognition is active
 */
@Composable
private fun VoiceInputOverlay(
    voiceState: VoiceInputState,
    voiceText: String,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    // Pulsing animation for listening indicator
    val infiniteTransition = rememberInfiniteTransition(label = "voice")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800)
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (voiceState) {
                is VoiceInputState.Listening -> {
                    // Pulsing microphone indicator
                    Text(
                        text = "\uD83C\uDF99",  // Microphone emoji
                        fontSize = 48.sp,
                        modifier = Modifier.graphicsLayer { this.alpha = alpha }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Listening...",
                        color = HudColors.cyan,
                        fontSize = 18.sp,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }

                is VoiceInputState.Recognizing -> {
                    // Show partial transcription
                    Text(
                        text = "\uD83C\uDF99",  // Microphone emoji
                        fontSize = 32.sp,
                        color = HudColors.green
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = voiceText.ifEmpty { "..." },
                        color = HudColors.green,
                        fontSize = 20.sp,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                is VoiceInputState.Error -> {
                    // Show error message
                    Text(
                        text = "\u26A0",  // Warning emoji
                        fontSize = 32.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = voiceState.message,
                        color = HudColors.error,
                        fontSize = 16.sp,
                        fontFamily = fontFamily,
                        textAlign = TextAlign.Center
                    )
                }

                is VoiceInputState.Idle -> {
                    // Should not be visible when idle
                }
            }

            // Tap to cancel hint (shown for all active states)
            if (voiceState !is VoiceInputState.Idle) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Tap to cancel",
                    color = HudColors.dimText,
                    fontSize = 12.sp,
                    fontFamily = fontFamily
                )
            }
        }
    }
}

/**
 * Session picker overlay for switching between tmux sessions
 */
@Composable
private fun SessionPickerOverlay(
    sessions: List<String>,
    currentSession: String,
    selectedIndex: Int,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "SELECT SESSION",
                color = HudColors.cyan,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Session list with "New Session" option at the end
            val allOptions = sessions + listOf("+ New Session")

            allOptions.forEachIndexed { index, session ->
                val isSelected = index == selectedIndex
                val isCurrent = session == currentSession

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) HudColors.green.copy(alpha = 0.3f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelected) "▶" else " ",
                            color = HudColors.green,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                        Text(
                            text = session,
                            color = if (isSelected) HudColors.green else HudColors.primaryText,
                            fontSize = 14.sp,
                            fontFamily = fontFamily,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    if (isCurrent) {
                        Text(
                            text = "●",
                            color = HudColors.cyan,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "↑↓ Navigate  TAP Select  2×TAP Cancel",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
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
