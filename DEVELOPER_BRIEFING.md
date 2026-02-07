# Developer Briefing: Getting claude-glasses-terminal to Work

## Goal

Get the `claude-glasses-terminal` project to a working state where:
1. The **phone app** runs on a Samsung Galaxy Z Fold5 (Android 16)
2. It connects to Rokid Glasses (RV101) via Bluetooth
3. It bridges voice/gesture input from the glasses to a WebSocket server running Claude Code (or OpenClaw)
4. Results are displayed on the glasses' monochrome HUD

## Current State

The repo has the full architecture scaffolded but is an **emulated prototype** - it has never been tested on actual hardware. The CXR SDK integration uses placeholder/incorrect patterns that need to be updated based on the official documentation.

### What exists and works:
- ✅ Server (`server/`) - Node.js WebSocket server wrapping Claude Code in tmux
- ✅ Shared protocol (`shared/`) - Message types and serialization
- ✅ Debug mode - Phone and glasses apps can communicate via WebSocket for emulator testing
- ✅ Glasses app UI (`glasses-app/`) - HUD display, gesture handler, theme
- ✅ Phone app UI (`phone-app/`) - Main screen, voice handler, terminal client

### What needs fixing:
- ❌ `RokidSdkManager.kt` - Uses incorrect credential-based init; needs rewrite per official docs
- ❌ `GlassesConnectionManager.kt` - Needs to use proper BLE scanning + CxrApi connection flow
- ❌ `PhoneConnectionService.kt` (glasses-app) - Uses `CXRServiceBridge` which is correct but the API usage needs verification
- ❌ Bluetooth permissions in AndroidManifest may be incomplete
- ❌ Dependency version mismatch (code uses `client-m:1.0.4`, docs reference `1.0.1-SNAPSHOT`)
- ❌ No glasses-app deployment mechanism (no dev cable available)

---

## SDK Reference

### Official Documentation

**CXR-M SDK docs (English):**
https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/us/index.html?documentId=e6548b3db09c4324aa621202066c7531

Sections:
1. Brief - Overview
2. SDK Import - Maven setup, dependencies, permissions
3. Function Development:
   - Device Connection (Bluetooth + WiFi P2P)
   - Equipment Status and Controls
   - Picture/Video/Audio
   - Data Operation (send data to glasses, sync files)
   - Scenes Operation (Custom AI Assistant)

### Maven Repository

```
Repository: https://maven.rokid.com/repository/maven-public/
```

**Available artifacts (verified, all resolve successfully):**

| Artifact | Latest Stable | Notes |
|----------|--------------|-------|
| `com.rokid.cxr:client-m` | 1.0.8 | Phone SDK (CXR-M) |
| `com.rokid.cxr:cxr-service-bridge` | 1.0 | Glasses SDK (CXR-S) |

The `client-m` AAR contains:
- `classes.jar` with full API
- Native libraries for `arm64-v8a` and `armeabi-v7a` (`libcaps.so`, `libcxr-bridge-jni.so`, `libcxr-sock-rfcomm-jni.so`, `libflora-cli.so`, `libmutils.so`)

### Key API Classes (Phone SDK - CXR-M)

```
com.rokid.cxr.client.extend.CxrApi              - Main SDK singleton
com.rokid.cxr.client.extend.callbacks.*:
  - BluetoothStatusCallback                       - BT connection events
  - WifiP2PStatusCallback                         - WiFi P2P events  
  - GlassInfoResultCallback                       - Device info
  - SendStatusCallback                            - Data send status
  - SyncStatusCallback                            - File sync status
  - ApkStatusCallback                             - APK installation status
  - PhotoResultCallback / PhotoPathCallback        - Photo handling
com.rokid.cxr.client.extend.listeners.*:
  - CustomCmdListener                              - Custom messages from glasses
  - AiEventListener                                - AI-related events
  - AudioStreamListener                            - Audio streaming
  - BatteryLevelUpdateListener                     - Battery monitoring
  - BrightnessUpdateListener                       - Brightness changes
  - SceneStatusUpdateListener                      - Scene status
com.rokid.cxr.client.extend.controllers.*:
  - FileController                                 - File transfer operations
  - WifiController                                 - WiFi management
com.rokid.cxr.client.extend.infos.*:
  - GlassInfo                                      - Device information
  - RKAppInfo                                      - App information
  - SceneStatusInfo                                - Scene status
com.rokid.cxr.Caps                                - Data capsule for messaging
com.rokid.cxr.CXRServiceBridge                    - Glasses-side bridge (CXR-S)
com.rokid.cxr.client.utils.ValueUtil              - Status codes and enums
```

---

## Required Changes

### 1. Update `build.gradle.kts` (phone-app)

**Change dependency version** and remove credential-based BuildConfig fields:

```kotlin
// REMOVE these BuildConfig fields from defaultConfig:
// buildConfigField("String", "ROKID_CLIENT_ID", ...)
// buildConfigField("String", "ROKID_CLIENT_SECRET", ...)  
// buildConfigField("String", "ROKID_ACCESS_KEY", ...)

// UPDATE dependency:
dependencies {
    // Change from:
    implementation("com.rokid.cxr:client-m:1.0.4")
    // To:
    implementation("com.rokid.cxr:client-m:1.0.8")
    
    // ADD missing dependency:
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.1")
}
```

### 2. Update AndroidManifest.xml (phone-app)

Ensure these permissions are declared (some may be missing):

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

### 3. Rewrite `RokidSdkManager.kt`

The current implementation uses a credential-based initialization (`ROKID_CLIENT_ID`, `ROKID_CLIENT_SECRET`, `ROKID_ACCESS_KEY`). The official SDK docs show **no credentials are needed**. The correct flow is:

#### Correct Bluetooth Connection Flow:

```
1. BLE Scan (filter UUID: 00009100-0000-1000-8000-00805f9b34fb)
   → Find Rokid device
2. CxrApi.getInstance().initBluetooth(context, device, callback)
   → callback.onConnectionInfo(socketUuid, macAddress, rokidAccount, glassesType)
3. CxrApi.getInstance().connectBluetooth(context, socketUuid, macAddress, callback)
   → callback.onConnected()
4. Now connected! Can send/receive data.
```

#### Reference Implementation (from official docs):

**Bluetooth scanning:**
```kotlin
// Use UUID to filter Rokid devices during BLE scan
val rokidServiceUuid = ParcelUuid.fromString("00009100-0000-1000-8000-00805f9b34fb")

scanner.startScan(
    listOf(ScanFilter.Builder()
        .setServiceUuid(rokidServiceUuid)
        .build()),
    ScanSettings.Builder().build(),
    scanCallback
)
```

**Initialization and connection:**
```kotlin
// Step 1: Initialize with found device
CxrApi.getInstance().initBluetooth(context, device, object : BluetoothStatusCallback {
    override fun onConnectionInfo(socketUuid: String?, macAddress: String?, rokidAccount: String?, glassesType: Int) {
        // Step 2: Connect using returned socketUuid and macAddress
        socketUuid?.let { uuid ->
            macAddress?.let { address ->
                CxrApi.getInstance().connectBluetooth(context, uuid, address, connectCallback)
            }
        }
    }
    override fun onConnected() { /* Connected! */ }
    override fun onDisconnected() { /* Disconnected */ }
    override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) { /* Error */ }
})
```

**Key differences from current code:**
- Remove all `BuildConfig.ROKID_CLIENT_ID/SECRET/KEY` references
- Remove `updateRokidAccount()` call
- Use `initBluetooth(context, device, callback)` instead of the current approach
- The `onConnectionInfo` callback gives you `socketUuid` and `macAddress` - use those for `connectBluetooth()`
- `connectBluetooth()` signature is `(context, socketUuid, macAddress, callback)` - not `(context, address, name, callback, encryptKey, account)`

### 4. Rewrite `GlassesConnectionManager.kt`

This class should:
1. Handle BLE scanning for Rokid devices
2. Present found devices to user
3. Call `RokidSdkManager.initBluetooth()` with the selected device
4. Manage connection lifecycle

Use the `BluetoothHelper` pattern from the official docs (see SDK Import > Device Connection section).

### 5. Sending Data to Glasses

Once connected, you can send data to the glasses using:

```kotlin
// Send stream data (e.g., teleprompter text)
CxrApi.getInstance().sendStream(
    ValueUtil.CxrStreamType.WORD_TIPS,  // or other types
    byteArrayData,
    "filename",
    sendStatusCallback
)
```

For custom messaging between phone and glasses apps, use `CustomCmdListener`:

```kotlin
// Listen for messages FROM glasses
CxrApi.getInstance().setCustomCmdListener { cmd, caps ->
    // Handle command from glasses app
}
```

### 6. WiFi P2P (Optional, for higher bandwidth)

WiFi P2P requires Bluetooth to be connected first. Only use when needed (high energy consumption).

```kotlin
// Initialize WiFi P2P (after Bluetooth is connected)
CxrApi.getInstance().initWifiP2P(object : WifiP2PStatusCallback {
    override fun onConnected() { /* WiFi P2P connected */ }
    override fun onDisconnected() { /* Disconnected */ }
    override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) { /* Error */ }
})

// Check status
val isWifiConnected = CxrApi.getInstance().isWifiP2PConnected
```

### 7. Glasses App Deployment

**Problem:** No 5-pin development cable available for ADB sideloading.

**Options (in order of preference):**

1. **Via CXR-M SDK APK push** - The SDK has `ApkStatusCallback` suggesting APK installation is supported. Investigate `FileController` for pushing APK files to glasses. This is likely how the Hi Rokid companion app installs updates.

2. **Via ADB over WiFi** - If the glasses' WiFi and ADB debugging are enabled (via Hi Rokid app), connect wirelessly:
   ```bash
   adb connect <glasses-ip>:5555
   adb install glasses-app.apk
   ```

3. **Purchase dev cable** - Contact global-support@rokid.com for the 5-pin development cable (~$40).

### 8. Glasses-Side SDK (CXR-S) Verification

The glasses app uses `CXRServiceBridge` for communication. The current `PhoneConnectionService.kt` implementation looks reasonable but verify:

- `CXRServiceBridge()` initialization
- `subscribe(msgType, callback)` for receiving messages
- `sendMessage(msgType, caps)` for sending messages
- Status listener setup

The `CXRServiceBridge` is available in both the `client-m` and `cxr-service-bridge` packages (the classes appear in both JARs).

---

## Development Setup

### Prerequisites

```bash
# macOS
brew install android-platform-tools
brew install openjdk@17

# Environment
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
```

### Building

```bash
# Create local.properties (SDK path only, no credentials needed)
echo "sdk.dir=/path/to/android/sdk" > local.properties

# Build phone app
./gradlew :phone-app:assembleDebug

# Build glasses app  
./gradlew :glasses-app:assembleDebug

# Install phone app on Fold5
adb install phone-app/build/outputs/apk/debug/phone-app-debug.apk
```

### Testing Without Glasses

The debug mode (WebSocket bridge) already works for emulator testing:
1. Start server: `cd server && npm install && npm start`
2. Run phone app on emulator/device
3. Run glasses app on second emulator (480x640 resolution)
4. Apps connect via WebSocket on port 8081, bypassing Bluetooth

---

## Architecture Reminder

```
┌──────────────────────────────────────────────────┐
│              REMOTE SERVER                        │
│  Node.js WebSocket server                        │
│  Runs Claude Code / OpenClaw in tmux             │
│  Sends terminal output as JSON frames            │
└───────────────────┬──────────────────────────────┘
                    │ WebSocket (port 8080)
┌───────────────────▼──────────────────────────────┐
│          PHONE APP (Samsung Fold5)                │
│  • BLE scan → find Rokid glasses                 │
│  • CxrApi.initBluetooth() → connect              │
│  • WebSocket client → server                     │
│  • Voice recognition (Android SpeechRecognizer)  │
│  • Bridges: server ↔ glasses                     │
└───────────────────┬──────────────────────────────┘
                    │ Bluetooth (CXR protocol)
┌───────────────────▼──────────────────────────────┐
│          GLASSES APP (Rokid RV101)                │
│  • CXRServiceBridge → phone communication        │
│  • HUD display (green monochrome, 480x640)       │
│  • Gesture input (temple touchpad)               │
│  • Camera capture (12MP, for future use)          │
└──────────────────────────────────────────────────┘
```

## Suggested Development Order

1. **Fix phone app Bluetooth connection** (RokidSdkManager + GlassesConnectionManager)
2. **Build and install phone app** on Fold5
3. **Test BLE scanning** - verify it finds the Rokid glasses
4. **Test Bluetooth connection** - verify `onConnected()` fires
5. **Figure out glasses app deployment** (ADB over WiFi or APK push via SDK)
6. **Install glasses app** on Rokid
7. **Test end-to-end** communication
8. **Connect to server** and verify terminal output appears on glasses

## Key Resources

- **Official CXR-M SDK Docs:** https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/us/index.html?documentId=e6548b3db09c4324aa621202066c7531
- **Maven Repo:** https://maven.rokid.com/repository/maven-public/
- **Rokid Developer Portal:** https://ar.rokid.com
- **Community examples:** https://github.com/RiverRuby/rokid-apps (native glasses apps)
- **Hackathon starter:** https://github.com/bochristopher/ROKID-hack
- **Medium writeup (BT SPP approach):** https://medium.com/@20x05zero/building-an-ai-powered-ar-assistant-for-rokid-glasses-camera-photo-analysis-and-voice-commands-b5788c79d51a
