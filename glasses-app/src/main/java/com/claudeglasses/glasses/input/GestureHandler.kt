package com.claudeglasses.glasses.input

import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Handles touch gestures on the glasses touchpad
 *
 * Gesture mappings:
 * - Swipe Up/Down: Scroll or navigate depending on mode
 * - Swipe Left/Right: Tab navigation
 * - Tap: Enter/confirm
 * - Double-tap: Switch mode
 * - Long press: Escape
 */
class GestureHandler(
    private val onGesture: (Gesture) -> Unit
) {
    enum class Gesture {
        SWIPE_UP,
        SWIPE_DOWN,
        SWIPE_LEFT,
        SWIPE_RIGHT,
        TAP,
        DOUBLE_TAP,
        LONG_PRESS,
        SCROLL_UP,
        SCROLL_DOWN,
        ESCAPE
    }

    companion object {
        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
    }

    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L

    private var lastTapTime = 0L
    private val doubleTapTimeout = 300L
    private val longPressTimeout = 500L

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startTime = System.currentTimeMillis()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val endX = event.x
                val endY = event.y
                val endTime = System.currentTimeMillis()

                val deltaX = endX - startX
                val deltaY = endY - startY
                val duration = endTime - startTime

                // Check for long press
                if (duration > longPressTimeout && abs(deltaX) < 50 && abs(deltaY) < 50) {
                    onGesture(Gesture.LONG_PRESS)
                    return true
                }

                // Check for swipe
                if (abs(deltaX) > SWIPE_THRESHOLD || abs(deltaY) > SWIPE_THRESHOLD) {
                    if (abs(deltaX) > abs(deltaY)) {
                        // Horizontal swipe
                        if (deltaX > 0) {
                            onGesture(Gesture.SWIPE_RIGHT)
                        } else {
                            onGesture(Gesture.SWIPE_LEFT)
                        }
                    } else {
                        // Vertical swipe
                        if (deltaY > 0) {
                            onGesture(Gesture.SWIPE_DOWN)
                        } else {
                            onGesture(Gesture.SWIPE_UP)
                        }
                    }
                    return true
                }

                // Check for tap/double-tap
                if (abs(deltaX) < 50 && abs(deltaY) < 50) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < doubleTapTimeout) {
                        onGesture(Gesture.DOUBLE_TAP)
                        lastTapTime = 0
                    } else {
                        lastTapTime = now
                        // Delay single tap to check for double tap
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (lastTapTime == now) {
                                onGesture(Gesture.TAP)
                            }
                        }, doubleTapTimeout)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // Could be used for continuous scrolling
                return true
            }
        }
        return false
    }
}
