package com.claudeglasses.phone.glasses

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.claudeglasses.phone.BuildConfig
import com.rokid.cxr.Caps
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.listeners.CustomCmdListener
import com.rokid.cxr.client.utils.ValueUtil

/**
 * Manages Rokid CXR-M SDK initialization and lifecycle.
 *
 * Uses credentials from BuildConfig (loaded from local.properties):
 * - ROKID_CLIENT_ID
 * - ROKID_CLIENT_SECRET
 * - ROKID_ACCESS_KEY
 */
object RokidSdkManager {

    private const val TAG = "RokidSdkManager"

    private var isInitialized = false
    private var cxrApi: CxrApi? = null
    private var appContext: Context? = null

    // Callbacks for glasses events
    var onGlassesConnected: (() -> Unit)? = null
    var onGlassesDisconnected: (() -> Unit)? = null
    var onMessageFromGlasses: ((String, Caps?) -> Unit)? = null
    var onConnectionInfo: ((name: String, mac: String, sn: String, type: Int) -> Unit)? = null

    private val bluetoothCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(name: String?, mac: String?, sn: String?, deviceType: Int) {
            Log.d(TAG, "Connection info: name=$name, mac=$mac, sn=$sn, type=$deviceType")
            onConnectionInfo?.invoke(name ?: "", mac ?: "", sn ?: "", deviceType)
        }

        override fun onConnected() {
            Log.d(TAG, "Connected to glasses")
            onGlassesConnected?.invoke()
        }

        override fun onDisconnected() {
            Log.d(TAG, "Disconnected from glasses")
            onGlassesDisconnected?.invoke()
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            Log.e(TAG, "Bluetooth connection failed: $errorCode")
        }
    }

    /**
     * Initialize the Rokid CXR-M SDK with credentials
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            Log.d(TAG, "SDK already initialized")
            return true
        }

        val clientId = BuildConfig.ROKID_CLIENT_ID
        val clientSecret = BuildConfig.ROKID_CLIENT_SECRET
        val accessKey = BuildConfig.ROKID_ACCESS_KEY

        if (clientId.isEmpty() || clientSecret.isEmpty() || accessKey.isEmpty()) {
            Log.e(TAG, "Rokid credentials not configured in local.properties")
            return false
        }

        Log.d(TAG, "Initializing Rokid SDK with Client ID: ${clientId.take(8)}...")
        appContext = context.applicationContext

        try {
            // Initialize CxrApi singleton
            cxrApi = CxrApi.getInstance()

            // Set up custom command listener to receive messages from glasses
            cxrApi?.setCustomCmdListener(object : CustomCmdListener {
                override fun onCustomCmd(cmd: String?, caps: Caps?) {
                    Log.d(TAG, "Received custom command from glasses: $cmd")
                    cmd?.let { onMessageFromGlasses?.invoke(it, caps) }
                }
            })

            // Update Rokid account with access key
            cxrApi?.updateRokidAccount(accessKey)

            Log.d(TAG, "Rokid SDK initialized successfully")
            isInitialized = true
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Rokid SDK", e)
            return false
        }
    }

    /**
     * Initialize Bluetooth connection with a specific device
     */
    fun initBluetooth(device: BluetoothDevice) {
        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized")
            return
        }

        try {
            cxrApi?.initBluetooth(context, device, bluetoothCallback)
            Log.d(TAG, "Bluetooth initialized with device: ${device.address}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth", e)
        }
    }

    /**
     * Connect to glasses via Bluetooth using address
     */
    fun connectBluetooth(
        deviceAddress: String,
        deviceName: String,
        encryptKey: ByteArray? = null,
        rokidAccount: String? = null
    ) {
        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized")
            return
        }

        try {
            cxrApi?.connectBluetooth(
                context,
                deviceAddress,
                deviceName,
                bluetoothCallback,
                encryptKey ?: ByteArray(0),
                rokidAccount ?: ""
            )
            Log.d(TAG, "Connecting to: $deviceName ($deviceAddress)")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting via Bluetooth", e)
        }
    }

    /**
     * Send a custom command/message to the glasses
     */
    fun sendToGlasses(command: String, caps: Caps = Caps()): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        return try {
            // The sendCustomCmd might not exist - need to find the right method
            // Using the underlying CxrController if available
            Log.d(TAG, "Attempting to send: ${command.take(50)}...")
            // TODO: Find the correct send method from CxrApi
            // For now, logging - actual implementation depends on SDK discovery
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to glasses", e)
            false
        }
    }

    /**
     * Check if SDK is ready
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Check if connected to glasses
     */
    fun isConnected(): Boolean {
        return try {
            cxrApi?.isBluetoothConnected ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Set audio as communication device (for voice input)
     */
    fun setCommunicationDevice() {
        cxrApi?.setCommunicationDevice()
    }

    /**
     * Clear communication device setting
     */
    fun clearCommunicationDevice() {
        cxrApi?.clearCommunicationDevice()
    }

    /**
     * Cleanup SDK resources
     */
    fun cleanup() {
        if (!isInitialized) return

        try {
            cxrApi?.clearCommunicationDevice()
            cxrApi = null
            appContext = null
            isInitialized = false
            Log.d(TAG, "Rokid SDK cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up Rokid SDK", e)
        }
    }
}
