# Claude Glasses Terminal

A terminal interface for Claude Code on Rokid Glasses. View and interact with Claude Code through your AR glasses using voice commands and gestures.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   REMOTE SERVER                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │           server/ (Node.js)                      │   │
│  │  • Spawns Claude Code in PTY                    │   │
│  │  • WebSocket endpoint for phone connection      │   │
│  │  • Handles terminal I/O                         │   │
│  └─────────────────────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────┘
                           │ WebSocket
┌──────────────────────────▼──────────────────────────────┐
│                      PHONE                              │
│  ┌─────────────────────────────────────────────────┐   │
│  │           phone-app/ (Android)                   │   │
│  │  • CXR-M SDK for glasses communication          │   │
│  │  • WebSocket client to server                   │   │
│  │  • Voice recognition (speech → text)            │   │
│  │  • Bridges server ↔ glasses                     │   │
│  └─────────────────────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────┘
                           │ BLE (CXR protocol)
┌──────────────────────────▼──────────────────────────────┐
│                     GLASSES                             │
│  ┌─────────────────────────────────────────────────┐   │
│  │           glasses-app/ (Android)                 │   │
│  │  • CXR-S SDK for phone communication            │   │
│  │  • HUD display (optimized for monochrome)       │   │
│  │  • Gesture input (touchpad)                     │   │
│  │  • Camera capture for screenshots               │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

## Project Structure

```
claude-glasses-terminal/
├── phone-app/              # Android app for phone (CXR-M SDK)
│   └── src/main/java/com/claudeglasses/phone/
│       ├── glasses/        # Glasses connection management
│       ├── terminal/       # WebSocket terminal client
│       ├── voice/          # Voice command handling
│       └── ui/             # Jetpack Compose UI
│
├── glasses-app/            # Android app for glasses (CXR-S SDK)
│   └── src/main/java/com/claudeglasses/glasses/
│       ├── ui/             # HUD display components
│       ├── input/          # Gesture handling
│       └── service/        # Phone connection service
│
├── shared/                 # Shared protocol definitions
│   └── src/main/java/com/claudeglasses/shared/
│       └── Protocol.kt     # Message types and serialization
│
└── server/                 # Node.js WebSocket server
    └── src/
        └── index.js        # Claude Code PTY wrapper
```

## Setup

### Prerequisites

- Android Studio (latest)
- Node.js 18+
- Rokid Glasses with YodaOS-Sprite
- Rokid developer account (for CXR SDK access)

### 1. Server Setup

```bash
cd server
npm install
npm start
```

The server will start on port 8080. Use Tailscale or another VPN to expose it to your phone on the go.

### 2. Phone App

1. Open the project in Android Studio
2. Add your Rokid CXR-M SDK credentials
3. Build and install `phone-app` on your Android phone
4. Configure the server URL in settings

### 3. Glasses App

Using CXR-M SDK from your phone app:
1. Build the `glasses-app` APK
2. Use the phone app to push the APK to your glasses over WiFi

Or with ADB debug cable (requires developer program):
```bash
adb install glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

## Usage

### Gesture Controls

| Mode | Swipe Up/Down | Tap | Double-Tap | Long Press |
|------|---------------|-----|------------|------------|
| **SCROLL** | Scroll terminal | Enter | Switch mode | ESC |
| **NAVIGATE** | Arrow ↑↓ | Enter | Switch mode | ESC |
| **COMMAND** | — | Enter | Switch mode | ESC |

Swipe Left/Right: Tab / Shift-Tab

### Voice Commands

| Say | Action |
|-----|--------|
| "slash help" | Types `/help` |
| "slash compact" | Types `/compact` |
| "escape" | Sends ESC key |
| "scroll up/down" | Scrolls terminal |
| "take screenshot" | Captures and sends image |

### Hardware Buttons

- Volume Up: Scroll up
- Volume Down: Scroll down
- Back: ESC

## HUD Display

The glasses display is optimized for the monochrome Micro-LED:
- Pure black background (blends with real world)
- Neon green/cyan text (high visibility)
- Centered layout (avoids edge clipping)
- ~50 characters × 12 lines visible

## Development

### Building

```bash
# Build all Android modules
./gradlew assembleDebug

# Build phone app only
./gradlew :phone-app:assembleDebug

# Build glasses app only
./gradlew :glasses-app:assembleDebug
```

### SDK Setup

Add to your Rokid developer credentials:

```kotlin
// In phone-app or glasses-app build.gradle.kts
repositories {
    maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
}
```

## TODO

- [ ] Integrate actual CXR-M/CXR-S SDK (currently placeholder)
- [ ] Add proper ANSI code parsing for syntax highlighting
- [ ] Implement camera capture on glasses
- [ ] Add haptic feedback for gestures
- [ ] Support for Claude's streaming responses
- [ ] Offline mode with cached context

## License

MIT
