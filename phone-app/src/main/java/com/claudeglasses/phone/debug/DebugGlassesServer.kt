package com.claudeglasses.phone.debug

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * Debug WebSocket server that runs on the phone app to allow
 * glasses app emulator to connect via WebSocket instead of Bluetooth.
 *
 * This enables testing phone↔glasses communication on emulators.
 */
class DebugGlassesServer(private val port: Int = 8081) {

    companion object {
        private const val TAG = "DebugGlassesServer"
        const val DEFAULT_PORT = 8081
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var scope: CoroutineScope? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _isClientConnected = MutableStateFlow(false)
    val isClientConnected: StateFlow<Boolean> = _isClientConnected

    // Callback for messages from glasses
    var onMessageFromGlasses: ((String) -> Unit)? = null

    // Callback for connection state changes
    var onGlassesConnected: (() -> Unit)? = null
    var onGlassesDisconnected: (() -> Unit)? = null

    fun start() {
        if (_isRunning.value) {
            Log.d(TAG, "Server already running")
            return
        }

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope?.launch {
            try {
                serverSocket = ServerSocket(port)
                _isRunning.value = true
                Log.i(TAG, "Debug glasses server started on port $port")

                while (isActive && _isRunning.value) {
                    try {
                        Log.d(TAG, "Waiting for glasses connection...")
                        val socket = serverSocket?.accept() ?: break
                        handleClient(socket)
                    } catch (e: Exception) {
                        if (_isRunning.value) {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            } finally {
                _isRunning.value = false
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        clientSocket = socket
        Log.i(TAG, "Glasses client connected from ${socket.inetAddress}")

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer = PrintWriter(socket.getOutputStream(), true)

            // Perform WebSocket handshake
            if (!performHandshake(reader, writer!!)) {
                Log.e(TAG, "WebSocket handshake failed")
                socket.close()
                return
            }

            _isClientConnected.value = true
            withContext(Dispatchers.Main) {
                onGlassesConnected?.invoke()
            }

            // Read messages
            while (socket.isConnected && _isRunning.value) {
                val message = readWebSocketFrame(socket.getInputStream())
                if (message != null) {
                    Log.d(TAG, "Received from glasses: ${message.take(100)}")
                    withContext(Dispatchers.Main) {
                        onMessageFromGlasses?.invoke(message)
                    }
                } else {
                    // Connection closed
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        } finally {
            _isClientConnected.value = false
            withContext(Dispatchers.Main) {
                onGlassesDisconnected?.invoke()
            }
            clientSocket = null
            writer = null
            Log.i(TAG, "Glasses client disconnected")
        }
    }

    private fun performHandshake(reader: BufferedReader, writer: PrintWriter): Boolean {
        // Read HTTP request
        val requestLines = mutableListOf<String>()
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            requestLines.add(line)
            line = reader.readLine()
        }

        // Find WebSocket key
        val keyLine = requestLines.find { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
        val key = keyLine?.substringAfter(":")?.trim() ?: return false

        // Generate accept key
        val acceptKey = generateAcceptKey(key)

        // Send handshake response
        writer.print("HTTP/1.1 101 Switching Protocols\r\n")
        writer.print("Upgrade: websocket\r\n")
        writer.print("Connection: Upgrade\r\n")
        writer.print("Sec-WebSocket-Accept: $acceptKey\r\n")
        writer.print("\r\n")
        writer.flush()

        Log.d(TAG, "WebSocket handshake completed")
        return true
    }

    private fun generateAcceptKey(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest((key + magic).toByteArray())
        return Base64.getEncoder().encodeToString(hash)
    }

    private fun readWebSocketFrame(input: java.io.InputStream): String? {
        return try {
            val firstByte = input.read()
            if (firstByte == -1) return null

            val secondByte = input.read()
            if (secondByte == -1) return null

            val isMasked = (secondByte and 0x80) != 0
            var payloadLength = (secondByte and 0x7F).toLong()

            if (payloadLength == 126L) {
                payloadLength = ((input.read() shl 8) or input.read()).toLong()
            } else if (payloadLength == 127L) {
                payloadLength = 0
                for (i in 0..7) {
                    payloadLength = (payloadLength shl 8) or input.read().toLong()
                }
            }

            val mask = if (isMasked) {
                ByteArray(4).also { input.read(it) }
            } else null

            val payload = ByteArray(payloadLength.toInt())
            var bytesRead = 0
            while (bytesRead < payloadLength) {
                val read = input.read(payload, bytesRead, (payloadLength - bytesRead).toInt())
                if (read == -1) return null
                bytesRead += read
            }

            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            // Check for close frame
            val opcode = firstByte and 0x0F
            if (opcode == 0x08) return null  // Close frame

            String(payload, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading WebSocket frame", e)
            null
        }
    }

    fun sendToGlasses(message: String): Boolean {
        val currentWriter = writer ?: return false
        val currentSocket = clientSocket ?: return false

        // Run on IO thread to avoid NetworkOnMainThreadException
        scope?.launch {
            try {
                val payload = message.toByteArray(Charsets.UTF_8)
                val frame = createWebSocketFrame(payload)

                currentSocket.getOutputStream()?.let { output ->
                    output.write(frame)
                    output.flush()
                }

                Log.d(TAG, "Sent to glasses: ${message.take(100)}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to glasses", e)
            }
        }
        return true
    }

    private fun createWebSocketFrame(payload: ByteArray): ByteArray {
        val frameLength = when {
            payload.size < 126 -> 2 + payload.size
            payload.size < 65536 -> 4 + payload.size
            else -> 10 + payload.size
        }

        val frame = ByteArray(frameLength)
        frame[0] = 0x81.toByte()  // FIN + Text frame

        var offset = 2
        when {
            payload.size < 126 -> {
                frame[1] = payload.size.toByte()
            }
            payload.size < 65536 -> {
                frame[1] = 126
                frame[2] = (payload.size shr 8).toByte()
                frame[3] = payload.size.toByte()
                offset = 4
            }
            else -> {
                frame[1] = 127
                for (i in 0..7) {
                    frame[2 + i] = (payload.size shr (56 - i * 8)).toByte()
                }
                offset = 10
            }
        }

        System.arraycopy(payload, 0, frame, offset, payload.size)
        return frame
    }

    fun stop() {
        _isRunning.value = false
        scope?.cancel()
        scope = null

        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }

        clientSocket = null
        serverSocket = null
        writer = null
        _isClientConnected.value = false

        Log.i(TAG, "Debug glasses server stopped")
    }
}
