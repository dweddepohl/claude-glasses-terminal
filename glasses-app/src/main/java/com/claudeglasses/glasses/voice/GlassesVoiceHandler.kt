package com.claudeglasses.glasses.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.claudeglasses.glasses.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles voice recognition on the glasses with live transcription support.
 *
 * Uses Android's SpeechRecognizer which works with:
 * - Google Play Services (standard Android devices)
 * - Rokid's built-in voice services (YodaOS-Master)
 *
 * In debug mode on emulator, use simulateVoiceInput() for testing.
 */
class GlassesVoiceHandler(private val context: Context) {

    companion object {
        private const val TAG = "GlassesVoice"

        // Word mappings for common terminal symbols
        private val WORD_MAPPINGS = mapOf(
            "slash" to "/",
            "forward slash" to "/",
            "backslash" to "\\",
            "back slash" to "\\",
            "dot" to ".",
            "period" to ".",
            "comma" to ",",
            "colon" to ":",
            "semicolon" to ";",
            "dash" to "-",
            "hyphen" to "-",
            "underscore" to "_",
            "at" to "@",
            "at sign" to "@",
            "hash" to "#",
            "hashtag" to "#",
            "pound" to "#",
            "dollar" to "$",
            "dollar sign" to "$",
            "percent" to "%",
            "caret" to "^",
            "ampersand" to "&",
            "and sign" to "&",
            "asterisk" to "*",
            "star" to "*",
            "open paren" to "(",
            "close paren" to ")",
            "open bracket" to "[",
            "close bracket" to "]",
            "open brace" to "{",
            "close brace" to "}",
            "pipe" to "|",
            "tilde" to "~",
            "backtick" to "`",
            "quote" to "\"",
            "single quote" to "'",
            "apostrophe" to "'",
            "equals" to "=",
            "plus" to "+",
            "minus" to "-",
            "less than" to "<",
            "greater than" to ">",
            "space" to " ",
            "newline" to "\n",
            "enter" to "\n",
            "tab" to "\t"
        )

        // Special commands that trigger actions instead of text input
        private val SPECIAL_COMMANDS = setOf(
            "escape",
            "scroll up",
            "scroll down",
            "take screenshot",
            "take photo",
            "switch mode",
            "navigate mode",
            "scroll mode",
            "command mode"
        )
    }

    /**
     * Voice recognition states for UI display
     */
    sealed class VoiceState {
        object Idle : VoiceState()
        object Listening : VoiceState()
        data class Recognizing(val partialText: String) : VoiceState()
        data class Error(val message: String) : VoiceState()
    }

    /**
     * Final voice recognition result
     */
    sealed class VoiceResult {
        data class Text(val text: String) : VoiceResult()
        data class Command(val command: String) : VoiceResult()
        data class Error(val message: String) : VoiceResult()
    }

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var onResult: ((VoiceResult) -> Unit)? = null
    private var isInitialized = false

    /**
     * Initialize the speech recognizer. Call once during activity creation.
     * Returns true if speech recognition is available.
     */
    fun initialize(): Boolean {
        if (isInitialized) return true

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(createRecognitionListener())
                isInitialized = true
                Log.d(TAG, "Speech recognizer initialized")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize speech recognizer", e)
            }
        } else {
            Log.w(TAG, "Speech recognition not available on this device")
        }

        // In debug mode, we allow initialization even without speech recognition
        // so that simulateVoiceInput() can be used for testing
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Debug mode: voice handler ready for simulated input")
            isInitialized = true
            return true
        }

        return false
    }

    /**
     * Start listening for voice input.
     * On emulator without speech recognition, this will show an error.
     * Use simulateVoiceInput() for testing on emulator.
     */
    fun startListening(onResult: (VoiceResult) -> Unit) {
        this.onResult = onResult

        if (speechRecognizer == null) {
            Log.w(TAG, "Speech recognizer not available")
            if (BuildConfig.DEBUG) {
                // In debug mode, show helpful message
                _voiceState.value = VoiceState.Error("Press T to type input\n(mic unavailable on emulator)")
            } else {
                onResult(VoiceResult.Error("Speech recognition unavailable"))
            }
            return
        }

        _voiceState.value = VoiceState.Listening

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Extend timeouts for longer dictation
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            _voiceState.value = VoiceState.Error("Failed to start")
            onResult(VoiceResult.Error("Failed to start voice recognition"))
        }
    }

    /**
     * Simulate voice input for debug/emulator testing (keyboard input).
     * Unlike real voice input, this does NOT apply word mappings (e.g., "at" -> "@")
     * since keyboard input should be sent exactly as typed.
     */
    fun simulateVoiceInput(text: String, onResult: (VoiceResult) -> Unit) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "simulateVoiceInput only available in debug builds")
            return
        }

        Log.d(TAG, "Simulating voice input: $text")
        _voiceState.value = VoiceState.Recognizing(text)

        // For keyboard input, send text as-is without word mappings
        // Word mappings are only useful for actual voice recognition
        _voiceState.value = VoiceState.Idle
        onResult(VoiceResult.Text(text))
    }

    /**
     * Update the displayed partial text during simulated input.
     */
    fun updateSimulatedText(text: String) {
        if (BuildConfig.DEBUG) {
            _voiceState.value = if (text.isEmpty()) {
                VoiceState.Listening
            } else {
                VoiceState.Recognizing(text)
            }
        }
    }

    /**
     * Stop listening (waits for final result)
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        Log.d(TAG, "Stopped listening")
    }

    /**
     * Cancel voice recognition without getting result
     */
    fun cancel() {
        speechRecognizer?.cancel()
        _voiceState.value = VoiceState.Idle
        onResult = null
        Log.d(TAG, "Cancelled voice recognition")
    }

    /**
     * Check if currently listening or showing voice UI
     */
    fun isListening(): Boolean {
        return _voiceState.value is VoiceState.Listening ||
               _voiceState.value is VoiceState.Recognizing
    }

    /**
     * Check if voice state is showing (not idle)
     */
    fun isActive(): Boolean {
        return _voiceState.value !is VoiceState.Idle
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            _voiceState.value = VoiceState.Listening
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could use this for audio level visualization
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission needed"
                SpeechRecognizer.ERROR_NETWORK -> "Network required"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Busy, try again"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Recognition error"
            }
            Log.e(TAG, "Recognition error ($error): $errorMessage")

            _voiceState.value = VoiceState.Error(errorMessage)
            onResult?.invoke(VoiceResult.Error(errorMessage))
            onResult = null
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spokenText = matches?.firstOrNull() ?: ""

            Log.d(TAG, "Final result: $spokenText")

            if (spokenText.isNotEmpty()) {
                val processedResult = processSpokenText(spokenText)
                onResult?.invoke(processedResult)
            } else {
                onResult?.invoke(VoiceResult.Error("No speech detected"))
            }

            _voiceState.value = VoiceState.Idle
            onResult = null
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partialText = matches?.firstOrNull() ?: ""

            if (partialText.isNotEmpty()) {
                Log.d(TAG, "Partial result: $partialText")
                _voiceState.value = VoiceState.Recognizing(partialText)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Process spoken text, converting word mappings and detecting special commands
     */
    fun processSpokenText(spokenText: String): VoiceResult {
        val lowerText = spokenText.lowercase().trim()

        // Check for special commands first
        for (command in SPECIAL_COMMANDS) {
            if (lowerText == command || lowerText.startsWith("$command ")) {
                return VoiceResult.Command(command)
            }
        }

        // Apply word mappings
        var processedText = spokenText
        for ((word, symbol) in WORD_MAPPINGS) {
            processedText = processedText.replace(
                Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE),
                symbol
            )
        }

        return VoiceResult.Text(processedText)
    }

    /**
     * Clean up resources. Call in activity onDestroy.
     */
    fun cleanup() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isInitialized = false
        _voiceState.value = VoiceState.Idle
        onResult = null
        Log.d(TAG, "Voice handler cleaned up")
    }
}
