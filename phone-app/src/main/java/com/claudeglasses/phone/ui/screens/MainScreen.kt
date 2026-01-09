package com.claudeglasses.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.claudeglasses.phone.R
import com.claudeglasses.phone.glasses.GlassesConnectionManager
import com.claudeglasses.phone.terminal.TerminalClient
import com.claudeglasses.phone.voice.VoiceCommandHandler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Managers
    val glassesManager = remember { GlassesConnectionManager(context) }
    val terminalClient = remember { TerminalClient() }
    val voiceHandler = remember { VoiceCommandHandler(context) }

    // State
    val glassesState by glassesManager.connectionState.collectAsState()
    val terminalState by terminalClient.connectionState.collectAsState()
    val terminalLines by terminalClient.terminalLines.collectAsState()
    val isListening by voiceHandler.isListening.collectAsState()

    // 10.0.2.2 is the Android emulator's alias for host machine localhost
    var serverUrl by remember { mutableStateOf("ws://10.0.2.2:8080") }
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Initialize voice handler
    LaunchedEffect(Unit) {
        voiceHandler.initialize()
    }

    // Auto-scroll to bottom when new lines arrive
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size - 1)
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            glassesManager.disconnect()
            terminalClient.cleanup()
            voiceHandler.cleanup()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude Glasses Terminal") },
                actions = {
                    // Settings button
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                // Input field
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("Type command...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                terminalClient.sendInput(inputText)
                                inputText = ""
                            }
                        }
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )

                // Voice button
                IconButton(
                    onClick = {
                        if (isListening) {
                            voiceHandler.stopListening()
                        } else {
                            voiceHandler.startListening { result ->
                                when (result) {
                                    is VoiceCommandHandler.VoiceResult.Text -> {
                                        terminalClient.sendInput(result.text)
                                    }
                                    is VoiceCommandHandler.VoiceResult.Command -> {
                                        handleVoiceCommand(result.command, terminalClient)
                                    }
                                    is VoiceCommandHandler.VoiceResult.Error -> {
                                        // Handle error
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Voice input",
                        tint = if (isListening) Color.Red else MaterialTheme.colorScheme.onSurface
                    )
                }

                // Send button
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            terminalClient.sendInput(inputText)
                            inputText = ""
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, "Send")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection status bar
            ConnectionStatusBar(
                glassesState = glassesState,
                terminalState = terminalState,
                onConnectGlasses = { glassesManager.startScanning() },
                onConnectTerminal = { terminalClient.connect(serverUrl) }
            )

            // Terminal output - auto-scale font to fit TERMINAL_COLS characters
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 4.dp)  // Minimal horizontal padding
            ) {
                val terminalCols = 50  // Match server's DEFAULT_COLS
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()

                // Use JetBrains Mono - a proper terminal font with correct box-drawing characters
                val monoFontFamily = remember {
                    FontFamily(Font(R.font.jetbrains_mono))
                }

                // Measure actual text width using TextMeasurer
                // Use a reference string of exactly terminalCols characters
                val referenceText = "M".repeat(terminalCols)
                val referenceFontSize = 16.sp  // Reference size to measure at

                val terminalFontSize = remember(maxWidth) {
                    val referenceStyle = TextStyle(
                        fontFamily = monoFontFamily,
                        fontSize = referenceFontSize
                    )
                    val measuredWidth = textMeasurer.measure(referenceText, referenceStyle).size.width
                    val availableWidthPx = with(density) { maxWidth.toPx() }

                    // Scale with 5% safety margin to ensure fit
                    val scaledSize = referenceFontSize.value * (availableWidthPx / measuredWidth) * 0.95f
                    scaledSize.coerceIn(6f, 20f).sp
                }

                // Create text style with tight line height (no extra spacing)
                val terminalTextStyle = TextStyle(
                    fontFamily = monoFontFamily,
                    fontSize = terminalFontSize,
                    lineHeight = 1.0.em,  // Exactly 1x font size, no extra spacing
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(terminalLines) { line ->
                        Text(
                            text = line.content,
                            style = terminalTextStyle,
                            color = when (line.type) {
                                TerminalClient.TerminalLine.Type.INPUT -> Color(0xFF4EC9B0)
                                TerminalClient.TerminalLine.Type.OUTPUT -> Color(0xFFD4D4D4)
                                TerminalClient.TerminalLine.Type.ERROR -> Color(0xFFF14C4C)
                                TerminalClient.TerminalLine.Type.SYSTEM -> Color(0xFF569CD6)
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Quick action buttons
            QuickActionBar(
                onEnter = { terminalClient.sendKey(TerminalClient.SpecialKey.ENTER) },
                onEscape = { terminalClient.sendKey(TerminalClient.SpecialKey.ESCAPE) },
                onTab = { terminalClient.sendKey(TerminalClient.SpecialKey.TAB) },
                onShiftTab = { terminalClient.sendKey(TerminalClient.SpecialKey.SHIFT_TAB) },
                onCtrlC = { terminalClient.sendKey(TerminalClient.SpecialKey.CTRL_C) }
            )
        }
    }

    // Settings dialog
    if (showSettings) {
        SettingsDialog(
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            onDismiss = { showSettings = false },
            onInstallGlassesApp = {
                // TODO: Trigger APK installation on glasses
                glassesManager.installApkOnGlasses("glasses-app.apk")
            }
        )
    }
}

@Composable
fun ConnectionStatusBar(
    glassesState: GlassesConnectionManager.ConnectionState,
    terminalState: TerminalClient.ConnectionState,
    onConnectGlasses: () -> Unit,
    onConnectTerminal: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glasses status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Status icon (colored circle)
            Icon(
                when (glassesState) {
                    is GlassesConnectionManager.ConnectionState.Connected -> Icons.Default.CheckCircle
                    is GlassesConnectionManager.ConnectionState.Connecting,
                    is GlassesConnectionManager.ConnectionState.Scanning -> Icons.Default.Sync
                    is GlassesConnectionManager.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked  // Empty circle for disconnected
                },
                contentDescription = null,
                tint = when (glassesState) {
                    is GlassesConnectionManager.ConnectionState.Connected -> Color.Green
                    is GlassesConnectionManager.ConnectionState.Connecting,
                    is GlassesConnectionManager.ConnectionState.Scanning -> Color.Yellow
                    is GlassesConnectionManager.ConnectionState.Error -> Color.Red
                    else -> Color.Gray
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Visibility,
                contentDescription = "Glasses",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            if (glassesState is GlassesConnectionManager.ConnectionState.Disconnected) {
                TextButton(
                    onClick = onConnectGlasses,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Connect", fontSize = 12.sp)
                }
            } else {
                Text(
                    text = when (glassesState) {
                        is GlassesConnectionManager.ConnectionState.Connected -> "Connected"
                        is GlassesConnectionManager.ConnectionState.Connecting -> "Connecting..."
                        is GlassesConnectionManager.ConnectionState.Scanning -> "Scanning..."
                        is GlassesConnectionManager.ConnectionState.Error -> "Error"
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        // Terminal/Server status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            if (terminalState is TerminalClient.ConnectionState.Disconnected) {
                TextButton(
                    onClick = onConnectTerminal,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Connect", fontSize = 12.sp)
                }
            } else {
                Text(
                    text = when (terminalState) {
                        is TerminalClient.ConnectionState.Connected -> "Connected"
                        is TerminalClient.ConnectionState.Connecting -> "Connecting..."
                        is TerminalClient.ConnectionState.Error -> "Error"
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Terminal,
                contentDescription = "Server",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            // Status icon (colored circle)
            Icon(
                when (terminalState) {
                    is TerminalClient.ConnectionState.Connected -> Icons.Default.CheckCircle
                    is TerminalClient.ConnectionState.Connecting -> Icons.Default.Sync
                    is TerminalClient.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked  // Empty circle for disconnected
                },
                contentDescription = null,
                tint = when (terminalState) {
                    is TerminalClient.ConnectionState.Connected -> Color.Green
                    is TerminalClient.ConnectionState.Connecting -> Color.Yellow
                    is TerminalClient.ConnectionState.Error -> Color.Red
                    else -> Color.Gray
                },
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun QuickActionBar(
    onEnter: () -> Unit,
    onEscape: () -> Unit,
    onTab: () -> Unit,
    onShiftTab: () -> Unit,
    onCtrlC: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        OutlinedButton(onClick = onEnter) { Text("⏎") }
        OutlinedButton(onClick = onEscape) { Text("ESC") }
        OutlinedButton(onClick = onTab) { Text("TAB") }
        OutlinedButton(onClick = onShiftTab) { Text("⇧TAB") }
        OutlinedButton(onClick = onCtrlC) { Text("^C") }
    }
}

@Composable
fun SettingsDialog(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onInstallGlassesApp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("Server URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onInstallGlassesApp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Install App on Glasses")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun handleVoiceCommand(command: String, terminalClient: TerminalClient) {
    when (command) {
        "escape" -> terminalClient.sendKey(TerminalClient.SpecialKey.ESCAPE)
        "scroll up" -> terminalClient.scrollUp()
        "scroll down" -> terminalClient.scrollDown()
        "switch mode", "navigate mode" -> {
            // TODO: Switch interaction mode
        }
        // Screenshot handling would be done via glasses
    }
}
