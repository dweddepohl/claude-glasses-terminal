package com.claudeglasses.phone.glasses

import android.util.Log
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles APK installation on Rokid glasses via ADB over WiFi.
 *
 * Prerequisites on glasses:
 * 1. Enable Developer Options (tap Build Number 7 times)
 * 2. Enable USB debugging
 * 3. Enable Wireless debugging
 * 4. Connect to same WiFi network as phone
 */
class ApkInstaller {

    companion object {
        private const val TAG = "ApkInstaller"
        private const val DEFAULT_ADB_PORT = 5555
    }

    sealed class InstallState {
        object Idle : InstallState()
        data class Connecting(val host: String) : InstallState()
        data class Transferring(val progress: Float) : InstallState()
        object Installing : InstallState()
        data class Success(val packageName: String) : InstallState()
        data class Error(val message: String) : InstallState()
    }

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    /**
     * Test connection to glasses via ADB.
     *
     * @param host IP address of the glasses (e.g., "192.168.1.100")
     * @param port ADB port (default 5555)
     * @return true if connection successful
     */
    suspend fun testConnection(host: String, port: Int = DEFAULT_ADB_PORT): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Testing connection to $host:$port")
                Dadb.create(host, port).use { dadb ->
                    val response = dadb.shell("echo connected")
                    val success = response.exitCode == 0
                    Log.d(TAG, "Connection test result: $success")
                    success
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection test failed", e)
                false
            }
        }
    }

    /**
     * Install APK on glasses via ADB over WiFi.
     *
     * @param host IP address of the glasses
     * @param port ADB port (default 5555)
     * @param apkFile The APK file to install
     */
    suspend fun installApk(
        host: String,
        port: Int = DEFAULT_ADB_PORT,
        apkFile: File
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (!apkFile.exists()) {
                    val error = "APK file not found: ${apkFile.absolutePath}"
                    _installState.value = InstallState.Error(error)
                    return@withContext Result.failure(Exception(error))
                }

                Log.d(TAG, "Starting APK installation: ${apkFile.name} (${apkFile.length() / 1024}KB)")
                _installState.value = InstallState.Connecting(host)

                Dadb.create(host, port).use { dadb ->
                    // Verify connection
                    Log.d(TAG, "Connected to $host:$port")

                    // Start transfer
                    _installState.value = InstallState.Transferring(0f)

                    // Install the APK
                    // Note: dadb.install() handles the full process
                    Log.d(TAG, "Installing APK...")
                    _installState.value = InstallState.Installing

                    dadb.install(apkFile)

                    // Get package name from APK for confirmation
                    val packageName = getPackageNameFromApk(apkFile)
                    Log.d(TAG, "Installation complete: $packageName")

                    _installState.value = InstallState.Success(packageName)
                    Result.success(packageName)
                }
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("Connection refused") == true ->
                        "Connection refused. Ensure wireless debugging is enabled on glasses."
                    e.message?.contains("timeout") == true ->
                        "Connection timed out. Check glasses IP and WiFi connection."
                    e.message?.contains("INSTALL_FAILED") == true ->
                        "Installation failed: ${e.message}"
                    else ->
                        "Installation error: ${e.message}"
                }
                Log.e(TAG, "APK installation failed", e)
                _installState.value = InstallState.Error(errorMessage)
                Result.failure(Exception(errorMessage))
            }
        }
    }

    /**
     * Install APK from a path string.
     */
    suspend fun installApk(
        host: String,
        port: Int = DEFAULT_ADB_PORT,
        apkPath: String
    ): Result<String> {
        return installApk(host, port, File(apkPath))
    }

    /**
     * Launch an app on the glasses.
     */
    suspend fun launchApp(
        host: String,
        port: Int = DEFAULT_ADB_PORT,
        packageName: String,
        activityName: String? = null
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Dadb.create(host, port).use { dadb ->
                    val command = if (activityName != null) {
                        "am start -n $packageName/$activityName"
                    } else {
                        "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                    }
                    val response = dadb.shell(command)
                    if (response.exitCode == 0) {
                        Log.d(TAG, "Launched app: $packageName")
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("Failed to launch: ${response.output}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get device info from glasses.
     */
    suspend fun getDeviceInfo(host: String, port: Int = DEFAULT_ADB_PORT): DeviceInfo? {
        return withContext(Dispatchers.IO) {
            try {
                Dadb.create(host, port).use { dadb ->
                    val model = dadb.shell("getprop ro.product.model").output.trim()
                    val androidVersion = dadb.shell("getprop ro.build.version.release").output.trim()
                    val serialNumber = dadb.shell("getprop ro.serialno").output.trim()
                    DeviceInfo(
                        model = model,
                        androidVersion = androidVersion,
                        serialNumber = serialNumber
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get device info", e)
                null
            }
        }
    }

    /**
     * Reset state to idle.
     */
    fun reset() {
        _installState.value = InstallState.Idle
    }

    private fun getPackageNameFromApk(apkFile: File): String {
        // Simple extraction - in a real app you'd use PackageParser
        // For now, infer from filename if it follows convention
        return apkFile.nameWithoutExtension
            .replace("-debug", "")
            .replace("-release", "")
    }

    data class DeviceInfo(
        val model: String,
        val androidVersion: String,
        val serialNumber: String
    )
}
