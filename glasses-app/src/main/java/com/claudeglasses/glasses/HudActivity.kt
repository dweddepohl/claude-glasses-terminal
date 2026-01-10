package com.claudeglasses.glasses

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import org.json.JSONObject

import com.claudeglasses.glasses.BuildConfig

class HudActivity : ComponentActivity() {

    companion object {
        // Enable debug mode for emulator testing (set to true when building for emulator)
        // When true, connects via WebSocket to phone app instead of Bluetooth
        val DEBUG_MODE = BuildConfig.DEBUG

        // Debug connection settings (10.0.2.2 is host from Android emulator)
        const val DEBUG_HOST = "10.0.2.2"
        const val DEBUG_PORT = 8081
    }

    private val terminalState = MutableStateFlow(TerminalState())
    private lateinit var gestureHandler: GestureHandler
    private lateinit var phoneConnection: PhoneConnectionService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while app is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable immersive fullscreen mode (hide system bars)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        gestureHandler = GestureHandler { gesture ->
            handleGesture(gesture)
        }

        // Create phone connection service - uses WebSocket in debug mode, Bluetooth otherwise
        phoneConnection = PhoneConnectionService(
            context = this,
            onMessageReceived = { message -> handlePhoneMessage(message) },
            debugMode = DEBUG_MODE,
            debugHost = DEBUG_HOST,
            debugPort = DEBUG_PORT
        )

        Log.i(GlassesApp.TAG, "HudActivity created, debugMode=$DEBUG_MODE")

        setContent {
            GlassesHudTheme {
                val state by terminalState.collectAsState()
                HudScreen(
                    state = state,
                    onTap = { handleGesture(Gesture.TAP) },
                    onDoubleTap = { handleGesture(Gesture.DOUBLE_TAP) },
                    onLongPress = { handleGesture(Gesture.LONG_PRESS) }
                )
            }
        }

        // Start listening for phone connection
        lifecycleScope.launch {
            phoneConnection.startListening()
        }

        // Observe connection state and update UI
        lifecycleScope.launch {
            phoneConnection.connectionState.collect { state ->
                val isConnected = state is PhoneConnectionService.ConnectionState.Connected
                val current = terminalState.value
                if (current.isConnected != isConnected) {
                    terminalState.value = current.copy(isConnected = isConnected)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { gestureHandler.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    // Handle generic motion events from external touchpad (Rokid glasses temple touchpad)
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event?.let {
            Log.d(GlassesApp.TAG, "GenericMotionEvent: action=${it.action}, source=${it.source}")
            if (gestureHandler.onTouchEvent(it)) {
                return true
            }
        }
        return super.onGenericMotionEvent(event)
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
        Log.d(GlassesApp.TAG, "Gesture detected: $gesture, mode: ${terminalState.value.mode}")
        val currentMode = terminalState.value.mode
        when (gesture) {
            Gesture.SWIPE_UP, Gesture.SCROLL_UP -> {
                when (currentMode) {
                    TerminalState.Mode.SCROLL -> scrollUp()
                    TerminalState.Mode.NAVIGATE -> sendCommand("up")
                    TerminalState.Mode.COMMAND -> sendCommand("tab")
                }
            }
            Gesture.SWIPE_DOWN, Gesture.SCROLL_DOWN -> {
                when (currentMode) {
                    TerminalState.Mode.SCROLL -> scrollDown()
                    TerminalState.Mode.NAVIGATE -> sendCommand("down")
                    TerminalState.Mode.COMMAND -> sendCommand("escape")
                }
            }
            Gesture.TAP -> {
                when (currentMode) {
                    TerminalState.Mode.SCROLL -> scrollToBottom()
                    TerminalState.Mode.NAVIGATE -> sendCommand("enter")
                    TerminalState.Mode.COMMAND -> sendCommand("shift_tab")  // Switch Claude Code modes
                }
            }
            Gesture.DOUBLE_TAP -> toggleMode()
            Gesture.LONG_PRESS -> sendCommand("escape")
            Gesture.SWIPE_LEFT -> {
                when (currentMode) {
                    TerminalState.Mode.COMMAND -> sendCommand("escape")
                    else -> sendCommand("shift_tab")
                }
            }
            Gesture.SWIPE_RIGHT -> {
                when (currentMode) {
                    TerminalState.Mode.COMMAND -> sendCommand("tab")
                    else -> sendCommand("tab")
                }
            }
            Gesture.ESCAPE -> sendCommand("escape")
        }
    }

    private fun scrollToBottom() {
        val current = terminalState.value
        // Scroll to last item index so it appears at top, which effectively shows the bottom
        val lastIndex = maxOf(0, current.lines.size - 1)
        Log.d(GlassesApp.TAG, "scrollToBottom: currentPos=${current.scrollPosition}, lines=${current.lines.size}, scrollingTo=$lastIndex")
        // Increment trigger to force scroll even if position unchanged
        terminalState.value = current.copy(
            scrollPosition = lastIndex,
            scrollTrigger = current.scrollTrigger + 1
        )
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
            val msg = JSONObject(json)
            val type = msg.optString("type", "")

            when (type) {
                "terminal_update" -> {
                    // Parse terminal update from phone app
                    val linesArray = msg.optJSONArray("lines")
                    val cursorPos = msg.optInt("cursorPosition", 0)
                    val mode = msg.optString("mode", "SCROLL")

                    val lines = mutableListOf<String>()
                    if (linesArray != null) {
                        for (i in 0 until linesArray.length()) {
                            lines.add(linesArray.optString(i, ""))
                        }
                    }

                    val current = terminalState.value
                    terminalState.value = current.copy(
                        lines = lines,
                        cursorLine = cursorPos,
                        scrollPosition = maxOf(0, lines.size - current.visibleLines),
                        isConnected = true
                    )

                    Log.d(GlassesApp.TAG, "Terminal update: ${lines.size} lines")
                }
                else -> {
                    Log.d(GlassesApp.TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(GlassesApp.TAG, "Error parsing message: ${json.take(100)}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        phoneConnection.stop()
    }
}
