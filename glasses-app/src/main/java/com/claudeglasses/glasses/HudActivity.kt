package com.claudeglasses.glasses

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.claudeglasses.glasses.input.GestureHandler
import com.claudeglasses.glasses.input.GestureHandler.Gesture
import com.claudeglasses.glasses.service.PhoneConnectionService
import com.claudeglasses.glasses.ui.HudScreen
import com.claudeglasses.glasses.ui.TerminalState
import com.claudeglasses.glasses.ui.theme.GlassesHudTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HudActivity : ComponentActivity() {

    private val terminalState = MutableStateFlow(TerminalState())
    private lateinit var gestureHandler: GestureHandler
    private lateinit var phoneConnection: PhoneConnectionService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while app is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gestureHandler = GestureHandler { gesture ->
            handleGesture(gesture)
        }

        phoneConnection = PhoneConnectionService(this) { message ->
            handlePhoneMessage(message)
        }

        setContent {
            GlassesHudTheme {
                val state by terminalState.collectAsState()
                HudScreen(state = state)
            }
        }

        // Start listening for phone connection
        lifecycleScope.launch {
            phoneConnection.startListening()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { gestureHandler.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle hardware buttons on glasses
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleGesture(Gesture.SCROLL_UP)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleGesture(Gesture.SCROLL_DOWN)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                handleGesture(Gesture.ESCAPE)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleGesture(gesture: Gesture) {
        when (gesture) {
            Gesture.SWIPE_UP -> scrollUp()
            Gesture.SWIPE_DOWN -> scrollDown()
            Gesture.TAP -> sendCommand("enter")
            Gesture.DOUBLE_TAP -> toggleMode()
            Gesture.LONG_PRESS -> sendCommand("escape")
            Gesture.SWIPE_LEFT -> sendCommand("shift_tab")
            Gesture.SWIPE_RIGHT -> sendCommand("tab")
            Gesture.SCROLL_UP -> scrollUp()
            Gesture.SCROLL_DOWN -> scrollDown()
            Gesture.ESCAPE -> sendCommand("escape")
        }
    }

    private fun scrollUp() {
        val current = terminalState.value
        val newPosition = maxOf(0, current.scrollPosition - current.visibleLines)
        terminalState.value = current.copy(scrollPosition = newPosition)
    }

    private fun scrollDown() {
        val current = terminalState.value
        val maxScroll = maxOf(0, current.lines.size - current.visibleLines)
        val newPosition = minOf(maxScroll, current.scrollPosition + current.visibleLines)
        terminalState.value = current.copy(scrollPosition = newPosition)
    }

    private fun toggleMode() {
        val current = terminalState.value
        val newMode = when (current.mode) {
            TerminalState.Mode.SCROLL -> TerminalState.Mode.NAVIGATE
            TerminalState.Mode.NAVIGATE -> TerminalState.Mode.COMMAND
            TerminalState.Mode.COMMAND -> TerminalState.Mode.SCROLL
        }
        terminalState.value = current.copy(mode = newMode)
    }

    private fun sendCommand(command: String) {
        phoneConnection.sendToPhone("""{"type":"command","command":"$command"}""")
    }

    private fun handlePhoneMessage(json: String) {
        try {
            // Parse message from phone and update terminal state
            // Expected: {"type":"terminal_update","lines":[...],"mode":"...","scroll":0}

            // TODO: Proper JSON parsing
            // For now, just add as a line
            val current = terminalState.value
            val newLines = current.lines + json
            terminalState.value = current.copy(
                lines = newLines,
                scrollPosition = maxOf(0, newLines.size - current.visibleLines)
            )
        } catch (e: Exception) {
            android.util.Log.e(GlassesApp.TAG, "Error parsing message", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        phoneConnection.stop()
    }
}
