package com.claudeglasses.phone.glasses

import android.content.Context
import android.util.Log
import dadb.Dadb
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Handles APK installation on Rokid glasses.
 *
 * Supports two installation methods:
 * 1. SDK Method: Uses CXR-M SDK's WiFi P2P transfer (requires SDK initialization + Bluetooth connection)
 * 2. ADB Method: Uses ADB over WiFi (requires glasses to have ADB enabled and be on same network)
 *
 * The ADB method is more reliable for development and doesn't require the full SDK setup.
 */
class ApkInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ApkInstaller"
        private const val GLASSES_APP_ASSET = "glasses-app-release.apk"
        private const val GLASSES_APP_PACKAGE = "com.claudeglasses.glasses"
        private const val GLASSES_APP_ACTIVITY = "com.claudeglasses.glasses.HudActivity"
        private const val DEFAULT_ADB_PORT = 5555
        private const val OPERATION_TIMEOUT_MS = 60_000L
    }

    /**
     * Installation method
     */
    enum class InstallMethod {
        SDK,    // Use Rokid CXR-M SDK (WiFi P2P)
        ADB     // Use ADB over WiFi
    }

    /**
     * Installation state machine
     */
    sealed class InstallState {
        object Idle : InstallState()
        object CheckingConnection : InstallState()
        object InitializingWifiP2P : InstallState()
        object PreparingApk : InstallState()
        data class Uploading(val message: String = "Uploading APK...", val progress: Int = -1) : InstallState()
        data class Installing(val message: String = "Installing...") : InstallState()
        data class Success(val message: String = "Installation complete!") : InstallState()
        data class Error(val message: String, val canRetry: Boolean = true) : InstallState()
    }

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var installJob: Job? = null

    // ADB connection settings
    private var adbHost: String = ""
    private var adbPort: Int = DEFAULT_ADB_PORT

    /**
     * Configure ADB connection for installation.
     * Call this before using installViaAdb().
     */
    fun configureAdb(host: String, port: Int = DEFAULT_ADB_PORT) {
        this.adbHost = host
        this.adbPort = port
        Log.d(TAG, "ADB configured: $host:$port")
    }

    /**
     * Install the glasses app using ADB over WiFi.
     * This is more reliable for development than the SDK method.
     *
     * Prerequisites on glasses:
     * 1. Enable Developer Options (tap Build Number 7 times)
     * 2. Enable USB debugging
     * 3. Connect glasses to same WiFi network as phone
     * 4. Find glasses IP: Settings > About > IP address
     */
    fun installViaAdb(host: String? = null, port: Int? = null) {
        val targetHost = host ?: adbHost
        val targetPort = port ?: adbPort

        if (targetHost.isEmpty()) {
            _installState.value = InstallState.Error(
                "ADB host not configured. Go to Settings and enter the glasses IP address.",
                canRetry = false
            )
            return
        }

        if (!canStartInstall()) return

        Log.i(TAG, "Starting ADB installation to $targetHost:$targetPort")
        _installState.value = InstallState.CheckingConnection

        installJob = scope.launch {
            try {
                withTimeout(OPERATION_TIMEOUT_MS) {
                    doAdbInstall(targetHost, targetPort)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Installation timed out after ${OPERATION_TIMEOUT_MS}ms")
                _installState.value = InstallState.Error("Installation timed out. Check glasses connection.")
            } catch (e: CancellationException) {
                Log.d(TAG, "Installation cancelled")
                _installState.value = InstallState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed", e)
                _installState.value = InstallState.Error(formatError(e))
            }
        }
    }

    private suspend fun doAdbInstall(host: String, port: Int) = withContext(Dispatchers.IO) {
        // Step 1: Test connection
        Log.d(TAG, "Testing ADB connection to $host:$port...")
        _installState.value = InstallState.CheckingConnection

        val dadb = try {
            Dadb.create(host, port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ADB", e)
            throw Exception("Cannot connect to glasses at $host:$port. " +
                "Ensure ADB debugging is enabled and glasses are on the same network.")
        }

        dadb.use { adb ->
            // Verify connection works
            val testResult = adb.shell("echo connected")
            if (testResult.exitCode != 0) {
                throw Exception("ADB connection test failed. Check if glasses accepted the connection.")
            }
            Log.d(TAG, "ADB connection verified")

            // Step 2: Prepare APK
            _installState.value = InstallState.PreparingApk
            val apkFile = extractApkFromAssets()
                ?: throw Exception("No APK found. Ensure glasses-app-release.apk is bundled.")

            Log.d(TAG, "APK prepared: ${apkFile.absolutePath} (${apkFile.length() / 1024} KB)")

            // Step 3: Install APK
            _installState.value = InstallState.Uploading("Uploading ${apkFile.length() / 1024} KB...")

            try {
                Log.d(TAG, "Installing APK via ADB...")
                _installState.value = InstallState.Installing("Installing on glasses...")
                adb.install(apkFile, "-r") // -r = replace existing

                Log.i(TAG, "APK installation successful!")
                _installState.value = InstallState.Success("Glasses app installed successfully!")

            } catch (e: Exception) {
                Log.e(TAG, "APK installation failed", e)
                throw Exception("Installation failed: ${e.message}")
            } finally {
                // Cleanup temp file
                cleanupTempApk()
            }
        }
    }

    /**
     * Install the bundled glasses app APK using SDK method.
     * Requires: SDK initialized, Bluetooth connected to glasses.
     *
     * Note: This method depends on the Rokid SDK being properly set up.
     * For development, prefer installViaAdb() which is more reliable.
     */
    fun installViaSdk() {
        if (!canStartInstall()) return

        // Check SDK initialization
        if (!RokidSdkManager.isReady()) {
            Log.e(TAG, "Rokid SDK not initialized")
            _installState.value = InstallState.Error(
                "Rokid SDK not initialized. Check if credentials are configured in local.properties.",
                canRetry = false
            )
            return
        }

        // Check Bluetooth connection
        if (!RokidSdkManager.isConnected()) {
            Log.e(TAG, "Not connected to glasses via Bluetooth")
            _installState.value = InstallState.Error(
                "Not connected to glasses. Pair with glasses first via Bluetooth.",
                canRetry = true
            )
            return
        }

        Log.i(TAG, "Starting SDK installation")
        _installState.value = InstallState.Error(
            "SDK installation not yet implemented. Use ADB method instead:\n" +
            "1. Enable ADB on glasses\n" +
            "2. Connect glasses to WiFi\n" +
            "3. Enter glasses IP in settings\n" +
            "4. Tap 'Install via ADB'",
            canRetry = false
        )

        // TODO: Implement SDK-based installation when SDK is fully integrated
        // The SDK uses WiFi P2P which requires additional setup
    }

    /**
     * Legacy method for backwards compatibility.
     * Tries SDK first, suggests ADB on failure.
     */
    fun installGlassesApp() {
        if (!canStartInstall()) return

        // Try to determine best method
        if (adbHost.isNotEmpty()) {
            // ADB is configured, use it
            installViaAdb()
        } else if (RokidSdkManager.isReady() && RokidSdkManager.isConnected()) {
            // SDK available, try it
            installViaSdk()
        } else {
            // Nothing configured - show helpful error
            _installState.value = InstallState.Error(
                "Configure installation method:\n\n" +
                "ADB Method (recommended for development):\n" +
                "1. Enable Developer Options on glasses\n" +
                "2. Enable USB/ADB debugging\n" +
                "3. Connect glasses to WiFi\n" +
                "4. Enter glasses IP address below\n\n" +
                "SDK Method:\n" +
                "Requires Rokid SDK credentials and Bluetooth pairing.",
                canRetry = false
            )
        }
    }

    /**
     * Test ADB connection to glasses without installing.
     */
    fun testAdbConnection(host: String, port: Int = DEFAULT_ADB_PORT, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Testing ADB connection to $host:$port")
                    Dadb.create(host, port).use { adb ->
                        val result = adb.shell("getprop ro.product.model")
                        if (result.exitCode == 0) {
                            val model = result.output.trim()
                            Log.d(TAG, "ADB connection successful: $model")
                            onResult(true, "Connected to: $model")
                        } else {
                            onResult(false, "Connection failed: ${result.output}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ADB connection test failed", e)
                onResult(false, formatError(e))
            }
        }
    }

    /**
     * Launch the glasses app via ADB.
     */
    fun launchViaAdb(host: String? = null, port: Int? = null) {
        val targetHost = host ?: adbHost
        val targetPort = port ?: adbPort

        if (targetHost.isEmpty()) {
            Log.e(TAG, "ADB host not configured")
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Dadb.create(targetHost, targetPort).use { adb ->
                        val cmd = "am start -n $GLASSES_APP_PACKAGE/$GLASSES_APP_ACTIVITY"
                        val result = adb.shell(cmd)
                        if (result.exitCode == 0) {
                            Log.d(TAG, "App launched successfully")
                        } else {
                            Log.e(TAG, "Failed to launch app: ${result.output}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app via ADB", e)
            }
        }
    }

    /**
     * Open the glasses app (tries SDK, then ADB).
     */
    fun openGlassesApp() {
        if (adbHost.isNotEmpty()) {
            launchViaAdb()
        } else {
            Log.w(TAG, "Cannot open app: ADB not configured and SDK not connected")
        }
    }

    /**
     * Cancel the current installation.
     */
    fun cancelInstallation() {
        Log.d(TAG, "Cancelling installation")
        installJob?.cancel()
        installJob = null
        cleanupTempApk()
        _installState.value = InstallState.Idle
    }

    /**
     * Reset state to idle.
     */
    fun resetState() {
        _installState.value = InstallState.Idle
        _lastError.value = null
    }

    private fun canStartInstall(): Boolean {
        val currentState = _installState.value
        if (currentState !is InstallState.Idle &&
            currentState !is InstallState.Error &&
            currentState !is InstallState.Success) {
            Log.w(TAG, "Installation already in progress: $currentState")
            return false
        }
        return true
    }

    private fun extractApkFromAssets(): File? {
        return try {
            val cacheDir = context.cacheDir
            val apkFile = File(cacheDir, "glasses-app.apk")

            // Check if we have a bundled APK in assets
            val assetManager = context.assets
            val assetList = assetManager.list("") ?: emptyArray()

            if (GLASSES_APP_ASSET in assetList) {
                Log.d(TAG, "Extracting bundled APK from assets")
                assetManager.open(GLASSES_APP_ASSET).use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }
                apkFile
            } else {
                // Check debug APK location
                val debugApk = File(cacheDir, "glasses-app-debug.apk")
                if (debugApk.exists()) {
                    Log.d(TAG, "Using debug APK from cache: ${debugApk.absolutePath}")
                    return debugApk
                }

                // Check external files directory
                val externalApk = File(context.getExternalFilesDir(null), "glasses-app.apk")
                if (externalApk.exists()) {
                    Log.d(TAG, "Using APK from external files: ${externalApk.absolutePath}")
                    externalApk
                } else {
                    Log.e(TAG, "No glasses-app APK found. Checked: assets/$GLASSES_APP_ASSET, $debugApk, $externalApk")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting APK from assets", e)
            null
        }
    }

    private fun cleanupTempApk() {
        try {
            File(context.cacheDir, "glasses-app.apk").delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up cached APK", e)
        }
    }

    private fun formatError(e: Exception): String {
        val message = e.message ?: "Unknown error"
        return when {
            message.contains("Connection refused") ->
                "Connection refused. Ensure:\n" +
                "1. ADB debugging is enabled on glasses\n" +
                "2. Glasses are on the same WiFi network\n" +
                "3. IP address is correct"
            message.contains("timeout", ignoreCase = true) ->
                "Connection timed out. Check:\n" +
                "1. Glasses IP address\n" +
                "2. WiFi connectivity\n" +
                "3. Firewall settings"
            message.contains("INSTALL_FAILED") ->
                "Installation failed: $message\n" +
                "Try uninstalling the existing app first."
            message.contains("No route to host") ->
                "Cannot reach glasses. Ensure they're on the same network."
            else -> message
        }
    }

    fun cleanup() {
        installJob?.cancel()
        scope.cancel()
        cleanupTempApk()
    }
}
