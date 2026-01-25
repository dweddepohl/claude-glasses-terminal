package com.claudeglasses.phone.glasses

import android.content.Context
import android.util.Log
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.infos.RKAppInfo
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Handles APK installation on Rokid glasses using CXR-M SDK.
 *
 * The SDK uses WiFi P2P for file transfers and Bluetooth for control.
 */
class ApkInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ApkInstaller"
        private const val GLASSES_APP_ASSET = "glasses-app-release.apk"
        private const val GLASSES_APP_PACKAGE = "com.claudeglasses.glasses"
        private const val GLASSES_APP_ACTIVITY = "com.claudeglasses.glasses.HudActivity"
    }

    /**
     * Installation state machine
     */
    sealed class InstallState {
        object Idle : InstallState()
        object CheckingConnection : InstallState()
        object InitializingWifiP2P : InstallState()
        object PreparingApk : InstallState()
        data class Uploading(val message: String = "Uploading APK...") : InstallState()
        data class Installing(val message: String = "Installing...") : InstallState()
        data class Success(val message: String = "Installation complete!") : InstallState()
        data class Error(val message: String) : InstallState()
    }

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val cxrApi: CxrApi? = try {
        CxrApi.getInstance()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get CxrApi instance", e)
        null
    }

    private var isWifiP2PConnected = false
    private var currentApkPath: String? = null

    private val wifiP2PCallback = object : WifiP2PStatusCallback {
        override fun onConnected() {
            Log.d(TAG, "WiFi P2P connected")
            isWifiP2PConnected = true
            // Proceed with upload now that WiFi P2P is ready
            currentApkPath?.let { path ->
                startApkUpload(path)
            }
        }

        override fun onDisconnected() {
            Log.d(TAG, "WiFi P2P disconnected")
            isWifiP2PConnected = false
        }

        override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
            Log.e(TAG, "WiFi P2P connection failed: $errorCode")
            isWifiP2PConnected = false
            _installState.value = InstallState.Error("WiFi P2P connection failed: ${errorCode ?: "unknown"}")
        }
    }

    private val apkStatusCallback = object : ApkStatusCallback {
        override fun onUploadApkSucceed() {
            Log.d(TAG, "APK upload succeeded")
            _installState.value = InstallState.Installing("Installing on glasses...")
        }

        override fun onUploadApkFailed() {
            Log.e(TAG, "APK upload failed")
            _installState.value = InstallState.Error("Failed to upload APK to glasses")
            cleanup()
        }

        override fun onInstallApkSucceed() {
            Log.d(TAG, "APK installation succeeded")
            _installState.value = InstallState.Success("Glasses app installed successfully!")
            cleanup()
        }

        override fun onInstallApkFailed() {
            Log.e(TAG, "APK installation failed")
            _installState.value = InstallState.Error("Failed to install APK on glasses")
            cleanup()
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
     * Install the bundled glasses app APK on the connected glasses.
     *
     * Flow:
     * 1. Check Bluetooth connection
     * 2. Initialize WiFi P2P (if needed)
     * 3. Extract APK from assets
     * 4. Upload APK via SDK
     * 5. Wait for installation callbacks
     */
    fun installGlassesApp() {
        if (_installState.value !is InstallState.Idle &&
            _installState.value !is InstallState.Error &&
            _installState.value !is InstallState.Success) {
            Log.w(TAG, "Installation already in progress")
            return
        }

        _installState.value = InstallState.CheckingConnection

        // Check Bluetooth connection
        val api = cxrApi
        if (api == null) {
            _installState.value = InstallState.Error("Rokid SDK not initialized")
            return
        }

        if (!api.isBluetoothConnected) {
            _installState.value = InstallState.Error("Not connected to glasses via Bluetooth")
            return
        }

        Log.d(TAG, "Bluetooth connected, preparing APK")
        _installState.value = InstallState.PreparingApk

        // Extract APK from assets to cache directory
        val apkFile = extractApkFromAssets()
        if (apkFile == null) {
            _installState.value = InstallState.Error("Failed to extract APK from app bundle")
            return
        }

        currentApkPath = apkFile.absolutePath

        // Check if WiFi P2P is already connected
        if (api.isWifiP2PConnected) {
            Log.d(TAG, "WiFi P2P already connected, starting upload")
            startApkUpload(apkFile.absolutePath)
        } else {
            // Initialize WiFi P2P first
            Log.d(TAG, "Initializing WiFi P2P connection")
            _installState.value = InstallState.InitializingWifiP2P
            api.initWifiP2P(wifiP2PCallback)
        }
    }

    /**
     * Install APK from a specific file path.
     */
    fun installApkFromPath(apkPath: String) {
        if (_installState.value !is InstallState.Idle &&
            _installState.value !is InstallState.Error &&
            _installState.value !is InstallState.Success) {
            Log.w(TAG, "Installation already in progress")
            return
        }

        val file = File(apkPath)
        if (!file.exists()) {
            _installState.value = InstallState.Error("APK file not found: $apkPath")
            return
        }

        _installState.value = InstallState.CheckingConnection

        val api = cxrApi
        if (api == null) {
            _installState.value = InstallState.Error("Rokid SDK not initialized")
            return
        }

        if (!api.isBluetoothConnected) {
            _installState.value = InstallState.Error("Not connected to glasses via Bluetooth")
            return
        }

        currentApkPath = apkPath

        if (api.isWifiP2PConnected) {
            startApkUpload(apkPath)
        } else {
            _installState.value = InstallState.InitializingWifiP2P
            api.initWifiP2P(wifiP2PCallback)
        }
    }

    private fun startApkUpload(apkPath: String) {
        _installState.value = InstallState.Uploading("Uploading APK to glasses...")

        val started = cxrApi?.startUploadApk(apkPath, apkStatusCallback) ?: false
        if (!started) {
            Log.e(TAG, "Failed to start APK upload")
            _installState.value = InstallState.Error("Failed to start APK upload")
            cleanup()
        } else {
            Log.d(TAG, "APK upload started: $apkPath")
        }
    }

    /**
     * Cancel the current installation.
     */
    fun cancelInstallation() {
        Log.d(TAG, "Cancelling installation")
        cxrApi?.stopUploadApk()
        cleanup()
        _installState.value = InstallState.Idle
    }

    /**
     * Open the glasses app on the connected glasses.
     */
    fun openGlassesApp() {
        val api = cxrApi ?: return

        if (!api.isBluetoothConnected) {
            Log.e(TAG, "Cannot open app: not connected to glasses")
            return
        }

        val appInfo = RKAppInfo(GLASSES_APP_PACKAGE, GLASSES_APP_ACTIVITY)
        api.openApp(appInfo, apkStatusCallback)
    }

    /**
     * Uninstall the glasses app from the connected glasses.
     */
    fun uninstallGlassesApp() {
        val api = cxrApi ?: return

        if (!api.isBluetoothConnected) {
            Log.e(TAG, "Cannot uninstall: not connected to glasses")
            return
        }

        api.uninstallApk(GLASSES_APP_PACKAGE, apkStatusCallback)
    }

    /**
     * Reset state to idle.
     */
    fun resetState() {
        _installState.value = InstallState.Idle
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
                // No bundled APK - check if there's one in app's files directory
                val externalApk = File(context.getExternalFilesDir(null), "glasses-app.apk")
                if (externalApk.exists()) {
                    Log.d(TAG, "Using APK from external files: ${externalApk.absolutePath}")
                    externalApk
                } else {
                    Log.e(TAG, "No glasses-app APK found in assets or external files")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting APK from assets", e)
            null
        }
    }

    private fun cleanup() {
        currentApkPath = null
        // Clean up extracted APK
        try {
            File(context.cacheDir, "glasses-app.apk").delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up cached APK", e)
        }
    }
}
