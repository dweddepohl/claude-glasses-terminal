package com.claudeglasses.glasses.service

import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge
import kotlinx.coroutines.*

/**
 * Service to handle communication with the phone app via CXR-S SDK
 *
 * Receives terminal updates from phone and sends gesture/voice commands back
 */
class PhoneConnectionService(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit
) {
    companion object {
        private const val TAG = "PhoneConnection"
        // Message types for subscribing
        private const val MSG_TYPE_TERMINAL = "terminal"
        private const val MSG_TYPE_COMMAND = "command"
    }

    private var cxrBridge: CXRServiceBridge? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var isConnected = false
    private var connectedDeviceName: String? = null
    private var connectedDeviceMac: String? = null

    /**
     * Start listening for phone connections via CXR-S SDK
     */
    fun startListening() {
        if (isRunning) return
        isRunning = true

        Log.d(TAG, "Starting CXR Service Bridge for phone connection")

        try {
            initializeBridge()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CXR bridge", e)
        }
    }

    private fun initializeBridge() {
        cxrBridge = CXRServiceBridge()

        // Set up status listener for connection events
        cxrBridge?.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(name: String?, mac: String?, deviceType: Int) {
                Log.d(TAG, "Phone connected via CXR bridge: $name ($mac), type=$deviceType")
                connectedDeviceName = name
                connectedDeviceMac = mac
                isConnected = true
            }

            override fun onDisconnected() {
                Log.d(TAG, "Phone disconnected from CXR bridge")
                connectedDeviceName = null
                connectedDeviceMac = null
                isConnected = false
            }

            override fun onConnecting(name: String?, mac: String?, deviceType: Int) {
                Log.d(TAG, "Phone connecting: $name ($mac)")
            }

            override fun onARTCStatus(latency: Float, connected: Boolean) {
                Log.d(TAG, "ARTC status: latency=$latency, connected=$connected")
            }

            override fun onRokidAccountChanged(account: String?) {
                Log.d(TAG, "Rokid account changed: $account")
            }
        })

        // Subscribe to terminal messages from phone
        val result = cxrBridge?.subscribe(MSG_TYPE_TERMINAL, object : CXRServiceBridge.MsgCallback {
            override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
                Log.d(TAG, "Received message type: $msgType")
                // Convert data to string if available
                val message = data?.toString(Charsets.UTF_8) ?: caps?.toString() ?: ""
                if (message.isNotEmpty()) {
                    Log.d(TAG, "Message content: ${message.take(100)}...")
                    onMessageReceived(message)
                }
            }
        })

        Log.d(TAG, "Subscribed to $MSG_TYPE_TERMINAL messages, result: $result")
    }

    /**
     * Send a command/message back to the phone
     */
    fun sendToPhone(message: String) {
        if (!isConnected) {
            Log.w(TAG, "Not connected to phone, cannot send message")
            return
        }

        scope.launch {
            try {
                val caps = Caps()
                caps.write(message)
                val result = cxrBridge?.sendMessage(MSG_TYPE_COMMAND, caps)
                Log.d(TAG, "Sent to phone: ${message.take(50)}..., result: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to phone", e)
            }
        }
    }

    /**
     * Send a command with binary data
     */
    fun sendToPhone(messageType: String, caps: Caps, data: ByteArray? = null) {
        if (!isConnected) {
            Log.w(TAG, "Not connected to phone, cannot send message")
            return
        }

        scope.launch {
            try {
                val result = if (data != null) {
                    cxrBridge?.sendMessage(messageType, caps, data)
                } else {
                    cxrBridge?.sendMessage(messageType, caps)
                }
                Log.d(TAG, "Sent message type $messageType, result: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to phone", e)
            }
        }
    }

    /**
     * Send a captured image to phone (for Claude screenshot feature)
     */
    fun sendImage(base64Image: String) {
        val caps = Caps()
        caps.write("image")
        caps.write(base64Image)
        sendToPhone("image", caps)
    }

    /**
     * Check if connected to phone
     */
    fun isPhoneConnected(): Boolean = isConnected

    /**
     * Get connected device info
     */
    fun getConnectedDevice(): Pair<String?, String?> = Pair(connectedDeviceName, connectedDeviceMac)

    fun stop() {
        isRunning = false
        isConnected = false
        scope.cancel()

        try {
            cxrBridge?.disconnectCXRDevice()
            cxrBridge = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping CXR bridge", e)
        }

        Log.d(TAG, "Phone connection service stopped")
    }
}
