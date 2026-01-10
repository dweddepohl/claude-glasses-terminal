package com.claudeglasses.phone.glasses

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.claudeglasses.phone.debug.DebugGlassesServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages connection to Rokid Glasses using CXR-M SDK
 * Supports debug mode for emulator testing via WebSocket
 */
class GlassesConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "GlassesConnection"

        // Rokid BLE Service UUID
        val ROKID_SERVICE_UUID: UUID = UUID.fromString("00009100-0000-1000-8000-00805f9b34fb")
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Scanning : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _terminalOutput = MutableStateFlow<List<String>>(emptyList())
    val terminalOutput: StateFlow<List<String>> = _terminalOutput.asStateFlow()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null

    // Debug mode WebSocket server for emulator testing
    private var debugServer: DebugGlassesServer? = null
    private var _debugModeEnabled = MutableStateFlow(false)
    val debugModeEnabled: StateFlow<Boolean> = _debugModeEnabled.asStateFlow()

    // Callback for messages from glasses (both BLE and debug modes)
    var onMessageFromGlasses: ((String) -> Unit)? = null

    // TODO: Replace with actual CXR-M SDK client
    // private var cxrClient: CxrClient? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown"

            if (deviceName.contains("Rokid", ignoreCase = true)) {
                Log.d(TAG, "Found Rokid device: $deviceName")
                stopScanning()
                connectToDevice(device.address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
        }
    }

    fun startScanning() {
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = ConnectionState.Error("Bluetooth is not enabled")
            return
        }

        _connectionState.value = ConnectionState.Scanning
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(ROKID_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.d(TAG, "Started BLE scanning for Rokid devices")
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.Error("Missing Bluetooth permissions")
        }
    }

    fun stopScanning() {
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }

    private fun connectToDevice(address: String) {
        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "Connecting to device: $address")

        // TODO: Use CXR-M SDK to establish connection
        // cxrClient = CxrApi.getInstance()
        // cxrClient?.connectDevice(address, object : ConnectionListener {
        //     override fun onConnected() {
        //         _connectionState.value = ConnectionState.Connected(address)
        //     }
        //     override fun onDisconnected() {
        //         _connectionState.value = ConnectionState.Disconnected
        //     }
        // })

        // Placeholder - simulate connection
        _connectionState.value = ConnectionState.Connected(address)
    }

    fun disconnect() {
        if (_debugModeEnabled.value) {
            stopDebugServer()
        }
        // TODO: cxrClient?.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    // ============== Debug Mode Methods ==============

    /**
     * Enable debug mode for emulator testing.
     * Starts a WebSocket server that glasses app can connect to.
     */
    fun enableDebugMode() {
        if (_debugModeEnabled.value) {
            Log.d(TAG, "Debug mode already enabled")
            return
        }

        Log.i(TAG, "Enabling debug mode - starting WebSocket server on port ${DebugGlassesServer.DEFAULT_PORT}")
        _debugModeEnabled.value = true

        debugServer = DebugGlassesServer().apply {
            onGlassesConnected = {
                _connectionState.value = ConnectionState.Connected("Debug Glasses (WebSocket)")
            }
            onGlassesDisconnected = {
                _connectionState.value = ConnectionState.Disconnected
            }
            onMessageFromGlasses = { message ->
                this@GlassesConnectionManager.onMessageFromGlasses?.invoke(message)
            }
            start()
        }

        // Update state to show we're waiting for connection
        _connectionState.value = ConnectionState.Scanning
    }

    /**
     * Disable debug mode and stop the WebSocket server.
     */
    fun disableDebugMode() {
        stopDebugServer()
        _debugModeEnabled.value = false
        _connectionState.value = ConnectionState.Disconnected
        Log.i(TAG, "Debug mode disabled")
    }

    private fun stopDebugServer() {
        debugServer?.stop()
        debugServer = null
    }

    /**
     * Send terminal output to glasses for display
     */
    fun sendToGlasses(lines: List<String>, cursorPosition: Int, mode: String) {
        val message = JSONObject().apply {
            put("type", "terminal_update")
            put("lines", JSONArray(lines))
            put("cursorPosition", cursorPosition)
            put("mode", mode)
            put("timestamp", System.currentTimeMillis())
        }

        if (_debugModeEnabled.value) {
            // Send via debug WebSocket server
            debugServer?.sendToGlasses(message.toString())
        } else {
            // TODO: Use CXR-M SDK to send data
            // cxrClient?.sendData(message.toString().toByteArray())
        }

        Log.d(TAG, "Sending to glasses: ${lines.size} lines, mode: $mode")
    }

    /**
     * Send a raw JSON message to glasses (for forwarding server responses)
     */
    fun sendRawMessage(jsonMessage: String) {
        if (_debugModeEnabled.value) {
            debugServer?.sendToGlasses(jsonMessage)
        } else {
            // TODO: Use CXR-M SDK to send data
        }
        Log.d(TAG, "Sending raw message to glasses")
    }

    /**
     * Send a command to install APK on glasses
     */
    fun installApkOnGlasses(apkPath: String) {
        Log.d(TAG, "Installing APK on glasses: $apkPath")
        // TODO: Use CXR-M SDK APK installation feature
        // cxrClient?.installApk(apkPath)
    }
}
