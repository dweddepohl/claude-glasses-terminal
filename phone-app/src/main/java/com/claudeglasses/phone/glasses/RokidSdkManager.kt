package com.claudeglasses.phone.glasses

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.claudeglasses.phone.BuildConfig
import com.rokid.cxr.Caps
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.listeners.CustomCmdListener
import com.rokid.cxr.client.utils.ValueUtil

/**
 * Manages Rokid CXR-M SDK initialization and lifecycle.
 *
 * Uses credentials from BuildConfig (loaded from local.properties):
 * - ROKID_CLIENT_ID
 * - ROKID_CLIENT_SECRET
 * - ROKID_ACCESS_KEY
 *
 * Connection flow:
 * 1. initialize() - Set up SDK with credentials
 * 2. initBluetooth(device) - Establish Bluetooth control channel
 * 3. initWifiP2P() - Establish WiFi P2P data channel (for APK uploads)
 * 4. startUploadApk() - Upload and install APK via WiFi P2P
 */
object RokidSdkManager {

    private const val TAG = "RokidSdkManager"

    private var isInitialized = false
    private var cxrApi: CxrApi? = null
    private var appContext: Context? = null

    // Connection state
    private var isBluetoothConnectedState = false
    private var isWifiP2PConnectedState = false

    // Saved connection info for reconnection
    private var savedSocketUuid: String? = null
    private var savedMacAddress: String? = null
    private var savedRokidAccount: String? = null
    private var savedDeviceName: String? = null

    // Callbacks for glasses events
    var onGlassesConnected: (() -> Unit)? = null
    var onGlassesDisconnected: (() -> Unit)? = null
    var onMessageFromGlasses: ((String, Caps?) -> Unit)? = null
    var onConnectionInfo: ((name: String, mac: String, sn: String, type: Int) -> Unit)? = null
    var onBluetoothFailed: ((String) -> Unit)? = null

    // WiFi P2P callbacks
    var onWifiP2PConnected: (() -> Unit)? = null
    var onWifiP2PDisconnected: (() -> Unit)? = null
    var onWifiP2PFailed: (() -> Unit)? = null

    // APK installation callbacks
    var onApkUploadSucceed: (() -> Unit)? = null
    var onApkUploadFailed: (() -> Unit)? = null
    var onApkInstallSucceed: (() -> Unit)? = null
    var onApkInstallFailed: (() -> Unit)? = null

    // Track if we're in init phase (need to call connectBluetooth after getting info)
    private var pendingConnect = false

    private val bluetoothCallback = object : BluetoothStatusCallback {
        // Parameters are: socketUuid, macAddress, rokidAccount, deviceType
        override fun onConnectionInfo(socketUuid: String?, macAddress: String?, rokidAccount: String?, deviceType: Int) {
            Log.i(TAG, "=== onConnectionInfo ===")
            Log.i(TAG, "  socketUuid=$socketUuid")
            Log.i(TAG, "  macAddress=$macAddress")
            Log.i(TAG, "  rokidAccount=$rokidAccount")
            Log.i(TAG, "  deviceType=$deviceType")

            // Save for reconnection
            savedSocketUuid = socketUuid
            savedMacAddress = macAddress
            savedRokidAccount = rokidAccount
            onConnectionInfo?.invoke(socketUuid ?: "", macAddress ?: "", rokidAccount ?: "", deviceType)

            // After initBluetooth, we need to call connectBluetooth to complete connection
            if (pendingConnect && !socketUuid.isNullOrEmpty() && !macAddress.isNullOrEmpty()) {
                Log.i(TAG, "Got connection info, now calling connectBluetooth...")
                pendingConnect = false
                // Use rokidAccount from callback if provided, otherwise empty string
                // (accessKey is for API auth, not Bluetooth pairing)
                val accountToUse = rokidAccount ?: ""
                Log.i(TAG, "  Using rokidAccount: '${accountToUse.take(20)}' (empty=${accountToUse.isEmpty()})")
                connectBluetoothInternal(socketUuid, macAddress, rokidAccount = accountToUse)
            }
        }

        override fun onConnected() {
            Log.i(TAG, "=== onConnected === Bluetooth connected to glasses!")
            isBluetoothConnectedState = true
            pendingConnect = false
            onGlassesConnected?.invoke()
        }

        override fun onDisconnected() {
            Log.i(TAG, "=== onDisconnected === Bluetooth disconnected from glasses")
            isBluetoothConnectedState = false
            onGlassesDisconnected?.invoke()
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            Log.e(TAG, "=== onFailed === Bluetooth connection failed: $errorCode")
            isBluetoothConnectedState = false
            pendingConnect = false
            onBluetoothFailed?.invoke(errorCode?.name ?: "Unknown error")
        }
    }

    private val wifiP2PCallback = object : WifiP2PStatusCallback {
        override fun onConnected() {
            Log.d(TAG, "WiFi P2P connected")
            isWifiP2PConnectedState = true
            onWifiP2PConnected?.invoke()
        }

        override fun onDisconnected() {
            Log.d(TAG, "WiFi P2P disconnected")
            isWifiP2PConnectedState = false
            onWifiP2PDisconnected?.invoke()
        }

        override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
            Log.e(TAG, "WiFi P2P connection failed: $errorCode")
            isWifiP2PConnectedState = false
            onWifiP2PFailed?.invoke()
        }
    }

    private val apkCallback = object : ApkStatusCallback {
        override fun onUploadApkSucceed() {
            Log.d(TAG, "APK upload succeeded")
            onApkUploadSucceed?.invoke()
        }

        override fun onUploadApkFailed() {
            Log.e(TAG, "APK upload failed")
            onApkUploadFailed?.invoke()
        }

        override fun onInstallApkSucceed() {
            Log.d(TAG, "APK installation succeeded")
            onApkInstallSucceed?.invoke()
        }

        override fun onInstallApkFailed() {
            Log.e(TAG, "APK installation failed")
            onApkInstallFailed?.invoke()
        }

        override fun onUninstallApkSucceed() {
            Log.d(TAG, "APK uninstall succeeded")
        }

        override fun onUninstallApkFailed() {
            Log.e(TAG, "APK uninstall failed")
        }

        override fun onOpenAppSucceed() {
            Log.d(TAG, "App opened successfully")
        }

        override fun onOpenAppFailed() {
            Log.e(TAG, "Failed to open app")
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
     * Initialize Bluetooth connection with a specific device.
     * This is the first step - it will trigger onConnectionInfo callback,
     * then we automatically call connectBluetooth to complete the connection.
     */
    fun initBluetooth(device: BluetoothDevice) {
        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized")
            return
        }

        try {
            Log.i(TAG, "=== initBluetooth === Starting with device: ${device.address}")
            pendingConnect = true  // Flag to call connectBluetooth after getting info
            cxrApi?.initBluetooth(context, device, bluetoothCallback)
            Log.i(TAG, "initBluetooth called, waiting for onConnectionInfo callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth", e)
            pendingConnect = false
        }
    }

    /**
     * Internal method to connect using socketUuid and macAddress from onConnectionInfo.
     */
    private fun connectBluetoothInternal(
        socketUuid: String,
        macAddress: String,
        encryptKey: ByteArray? = null,
        rokidAccount: String? = null
    ) {
        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized")
            return
        }

        try {
            // Determine which account to use:
            // - First try rokidAccount from onConnectionInfo (if glasses already paired)
            // - Fall back to empty string (for new pairing)
            val accountToUse = rokidAccount ?: ""

            Log.i(TAG, "=== connectBluetoothInternal ===")
            Log.i(TAG, "  socketUuid=$socketUuid")
            Log.i(TAG, "  macAddress=$macAddress")
            Log.i(TAG, "  rokidAccount='$accountToUse' (length=${accountToUse.length})")
            Log.i(TAG, "  encryptKey=${encryptKey?.size ?: 0} bytes")

            cxrApi?.connectBluetooth(
                context,
                socketUuid,
                macAddress,
                bluetoothCallback,
                encryptKey ?: ByteArray(0),
                accountToUse
            )
            Log.i(TAG, "connectBluetooth called, waiting for onConnected callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting via Bluetooth", e)
        }
    }

    /**
     * Connect to glasses via Bluetooth using saved connection info.
     * For reconnection after initial pairing.
     */
    fun connectBluetooth(
        socketUuid: String,
        macAddress: String,
        encryptKey: ByteArray? = null,
        rokidAccount: String? = null
    ) {
        connectBluetoothInternal(socketUuid, macAddress, encryptKey, rokidAccount)
    }

    /**
     * Send a custom command/message to the glasses via Bluetooth
     */
    fun sendToGlasses(command: String, caps: Caps = Caps()): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Not connected to glasses via Bluetooth")
            return false
        }

        return try {
            // Send custom command via the SDK
            caps.write(command)
            cxrApi?.sendCustomCmd("terminal", caps)
            Log.d(TAG, "Sent to glasses: ${command.take(50)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to glasses", e)
            false
        }
    }

    // ============== WiFi P2P Methods ==============

    /**
     * Initialize WiFi P2P connection for data transfer (APK uploads, etc.)
     * Call this after Bluetooth is connected.
     */
    fun initWifiP2P(): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Bluetooth not connected - connect via Bluetooth first")
            return false
        }

        return try {
            val status = cxrApi?.initWifiP2P(wifiP2PCallback)
            Log.d(TAG, "WiFi P2P initialization status: $status")
            status == ValueUtil.CxrStatus.REQUEST_SUCCEED
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WiFi P2P", e)
            false
        }
    }

    /**
     * Deinitialize WiFi P2P connection
     */
    fun deinitWifiP2P() {
        try {
            cxrApi?.deinitWifiP2P()
            isWifiP2PConnectedState = false
            Log.d(TAG, "WiFi P2P deinitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error deinitializing WiFi P2P", e)
        }
    }

    /**
     * Check if WiFi P2P is connected
     */
    fun isWifiP2PConnected(): Boolean {
        return try {
            cxrApi?.isWifiP2PConnected ?: false
        } catch (e: Exception) {
            isWifiP2PConnectedState
        }
    }

    // ============== APK Installation Methods ==============

    /**
     * Upload and install an APK on the glasses via WiFi P2P.
     * Requires: Bluetooth connected AND WiFi P2P connected
     *
     * @param apkPath Absolute path to the APK file
     * @return true if upload started successfully
     */
    fun startUploadApk(apkPath: String): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Bluetooth not connected")
            return false
        }

        // WiFi P2P may be initialized on-demand by the SDK
        return try {
            val result = cxrApi?.startUploadApk(apkPath, apkCallback) ?: false
            Log.d(TAG, "startUploadApk result: $result for path: $apkPath")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error starting APK upload", e)
            false
        }
    }

    /**
     * Cancel an ongoing APK upload
     */
    fun stopUploadApk() {
        try {
            cxrApi?.stopUploadApk()
            Log.d(TAG, "APK upload stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping APK upload", e)
        }
    }

    // ============== Status Methods ==============

    /**
     * Check if SDK is ready
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Check if connected to glasses via Bluetooth
     */
    fun isConnected(): Boolean {
        return try {
            cxrApi?.isBluetoothConnected ?: false
        } catch (e: Exception) {
            isBluetoothConnectedState
        }
    }

    /**
     * Get saved MAC address for reconnection
     */
    fun getSavedMacAddress(): String? = savedMacAddress

    /**
     * Get saved device name
     */
    fun getSavedDeviceName(): String? = savedDeviceName

    /**
     * Attempt to reconnect to previously connected glasses
     */
    fun reconnect(): Boolean {
        val socketUuid = savedSocketUuid
        val mac = savedMacAddress
        if (socketUuid.isNullOrEmpty() || mac.isNullOrEmpty()) {
            Log.w(TAG, "No saved connection info for reconnection (socketUuid=$socketUuid, mac=$mac)")
            return false
        }
        Log.i(TAG, "Reconnecting with saved socketUuid and macAddress...")
        connectBluetooth(socketUuid, mac)
        return true
    }

    /**
     * Get saved socket UUID for reconnection
     */
    fun getSavedSocketUuid(): String? = savedSocketUuid

    /**
     * Disconnect from glasses
     */
    fun disconnect() {
        try {
            deinitWifiP2P()
            cxrApi?.deinitBluetooth()
            isBluetoothConnectedState = false
            Log.d(TAG, "Disconnected from glasses")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
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
            deinitWifiP2P()
            cxrApi?.clearCommunicationDevice()
            cxrApi = null
            appContext = null
            isInitialized = false
            isBluetoothConnectedState = false
            isWifiP2PConnectedState = false
            Log.d(TAG, "Rokid SDK cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up Rokid SDK", e)
        }
    }
}
