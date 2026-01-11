# Claude Glasses - Claude Code for Rokid AI Glasses 👾x🕶️

A terminal interface for Claude Code on Rokid Glasses. View and interact with Claude Code through your AR glasses using voice commands and gestures.

NOTE: This is an emulated prototype and has not been tested on the actual glasses yet.

## See Claude Code While You Work

Imagine reviewing code changes while standing at a whiteboard, or dictating a database query while your hands are busy. Claude Glasses puts a terminal in your field of view, controlled entirely by voice and gestures.

### Manage Multiple Sessions

<p align="center">
  <img src="docs/images/Screenshot_20260111_154026.png" width="320" alt="Session selector showing multiple Claude Code sessions">
</p>

Switch between different Claude Code sessions on the fly. Each project gets its own persistent session - navigate between them with swipes, select with a tap.

### Voice-First Input

<p align="center">
  <img src="docs/images/Screenshot_20260111_154323.png" width="320" alt="Voice input showing natural language command">
</p>

No keyboard needed. Hold the touchpad and speak naturally: *"Connect to our database and find out how many transactions we did today"*. Your voice becomes the prompt.

### Review Code Hands-Free

<p align="center">
  <img src="docs/images/Screenshot_20260111_154114.png" width="320" alt="Code diff view showing Claude's changes">
</p>

Scroll through diffs, read Claude's explanations, and navigate the terminal - all with simple gestures on the temple touchpad. The monochrome green display blends into your environment while keeping the code visible.

### Bootstrapped

Wonder if you can really build something with just gestures and voice? This project is the answer. Claude Glasses was used to build Claude Glasses!

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   REMOTE SERVER                         │
│  ┌─────────────────────────────────────────────────┐    │
│  │           server/ (Node.js)                     │    │
│  │  • Runs Claude Code in tmux session             │    │
│  │  • WebSocket endpoint for phone connection      │    │
│  │  • Handles terminal I/O                         │    │
│  └─────────────────────────────────────────────────┘    │
└──────────────────────────┬──────────────────────────────┘
                           │ WebSocket
┌──────────────────────────▼──────────────────────────────┐
│                      PHONE                              │
│  ┌─────────────────────────────────────────────────┐    │
│  │           phone-app/ (Android)                  │    │
│  │  • CXR-M SDK for glasses communication          │    │
│  │  • WebSocket client to server                   │    │
│  │  • Voice recognition (speech → text)            │    │
│  │  • Bridges server ↔ glasses                     │    │
│  └─────────────────────────────────────────────────┘    │
└──────────────────────────┬──────────────────────────────┘
                           │ BLE (CXR protocol)
┌──────────────────────────▼──────────────────────────────┐
│                     GLASSES                             │
│  ┌─────────────────────────────────────────────────┐    │
│  │           glasses-app/ (Android)                │    │
│  │  • CXR-S SDK for phone communication            │    │
│  │  • HUD display (optimized for monochrome)       │    │
│  │  • Gesture input (touchpad)                     │    │
│  │  • Camera capture for screenshots               │    │
│  └─────────────────────────────────────────────────┘    │
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
        └── index.js        # Claude Code tmux wrapper
```

## Setup

### Prerequisites

- Android Studio (latest)
- Node.js 18+
- tmux (`brew install tmux` on macOS)
- Claude Code CLI installed and configured
- Rokid Glasses with YodaOS-Sprite
- Rokid developer account (for CXR SDK access)

### 1. Server Setup

```bash
cd server
npm install
npm start
```

The server will start on port 8080 with a 65×15 terminal (optimized for glasses HUD). Use Tailscale or another VPN to expose it to your phone on the go.

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

The temple touchpad has two swipe directions: **forward** (towards eyes) and **backward** (towards ear).

| Mode | Forward/Backward | Tap | Double-Tap | Long Press |
|------|------------------|-----|------------|------------|
| **SCROLL** | Scroll up/down | Jump to end | Switch mode | Voice |
| **NAVIGATE** | Arrow ↑↓ | Enter | Switch mode | Voice |
| **COMMAND** | Tab / Escape | Shift-Tab | Switch mode | Voice |

- **Forward swipe**: Scroll up / Arrow up / Tab (depending on mode)
- **Backward swipe**: Scroll down / Arrow down / Escape (depending on mode)
- **Hardware Back button**: Escape

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

The glasses display is optimized for the Rokid AR Lite (49g green waveguide) 640×480 pixel display:
- Pure black background (blends with real world on monochrome display)
- Neon green/cyan text (high visibility)
- JetBrains Mono font for proper box-drawing character alignment
- Dynamic font scaling to fit 65 columns without wrapping
- ~65 characters × 15 lines visible

## Emulator Testing (Debug Mode)

For development without physical glasses, you can test using two Android emulators:

### Setup

1. **Create a glasses emulator** with these specs to match Rokid display:
   - Resolution: 640×480
   - Screen size: 5.0 inches (gives 160 dpi, so 1dp = 1px)

2. **Start the phone emulator** first, then the glasses emulator

3. **Debug mode** is enabled automatically in debug builds:
   - Phone app starts a WebSocket server on port 8081
   - Glasses app connects to `10.0.2.2:8081` (host machine from emulator)

### Running

```bash
# Terminal 1: Start the server
cd server && npm start

# Terminal 2: Run phone app on first emulator
./gradlew :phone-app:installDebug

# Terminal 3: Run glasses app on second emulator
./gradlew :glasses-app:installDebug
```

The glasses emulator will connect to the phone emulator via WebSocket, bypassing Bluetooth.

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

## Documentation

- [CLAUDE.md](CLAUDE.md) - Development context and guidelines for Claude Code
- [docs/ROKID.md](docs/ROKID.md) - Rokid hardware specs, SDK reference, and resources

## TODO

- [ ] Integrate actual CXR-M/CXR-S SDK (currently placeholder)
- [ ] Add proper ANSI code parsing for syntax highlighting
- [ ] Implement camera capture on glasses
- [ ] Add haptic feedback for gestures
- [ ] Support for Claude's streaming responses
- [ ] Offline mode with cached context
- [ ] Voice command integration

## License

MIT
