package com.kozin.bluetoothlaserpointer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

enum class TcpConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

data class DiscoveredServer(
    val hostName: String,
    val osName: String,
    val ip: String,
    val port: Int
)

data class TcpUiState(
    val phase: TcpConnectionPhase = TcpConnectionPhase.DISCONNECTED,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val connectedServerName: String? = null,
    val isScanningLan: Boolean = false,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val statusMessage: String = "Disconnected"
) {
    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8080
    }
}

class TcpMouseController(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        TcpUiState(
            host = prefs.getString(KEY_TCP_HOST, TcpUiState.DEFAULT_HOST) ?: TcpUiState.DEFAULT_HOST,
            port = prefs.getInt(KEY_TCP_PORT, TcpUiState.DEFAULT_PORT)
        )
    )
    val uiState: StateFlow<TcpUiState> = _uiState.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val commandQueue = ConcurrentLinkedQueue<String>()
    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)
    private val pendingScroll = AtomicInteger(0)
    private val flushScheduled = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var isConnected = false

    fun updateHost(newHost: String) {
        val trimmed = newHost.trim().ifEmpty { TcpUiState.DEFAULT_HOST }
        prefs.edit().putString(KEY_TCP_HOST, trimmed).apply()
        _uiState.value = _uiState.value.copy(host = trimmed)
    }

    fun connect(overrideHost: String? = null, overridePort: Int? = null) {
        val targetHost = (overrideHost ?: _uiState.value.host).trim().ifEmpty { TcpUiState.DEFAULT_HOST }
        val targetPort = overridePort ?: _uiState.value.port
        prefs.edit().putString(KEY_TCP_HOST, targetHost).putInt(KEY_TCP_PORT, targetPort).apply()

        if (_uiState.value.phase == TcpConnectionPhase.CONNECTING) return
        closeSocketQuietly()

        _uiState.value = _uiState.value.copy(
            phase = TcpConnectionPhase.CONNECTING,
            host = targetHost,
            port = targetPort,
            connectedServerName = null,
            statusMessage = "Connecting to $targetHost:$targetPort…"
        )

        thread(name = "TcpConnectThread", isDaemon = true) {
            try {
                val sock = Socket().apply {
                    tcpNoDelay = true // Disable Nagle buffering for sub-ms cursor smoothness
                    keepAlive = true
                    connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
                }
                val out = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), 1024)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

                socket = sock
                writer = out
                isConnected = true
                clearBuffers()

                _uiState.value = _uiState.value.copy(
                    phase = TcpConnectionPhase.CONNECTED,
                    connectedServerName = targetHost,
                    statusMessage = "Connected to $targetHost"
                )
                Log.d(TAG, "Connected to $targetHost:$targetPort")

                // Reader loop: parses server HELLO greeting and immediately detects server EOF/disconnect!
                while (isConnected && !sock.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("HELLO:")) {
                        val parts = line.split(":")
                        val serverName = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: targetHost
                        val osLabel = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                        val display = if (osLabel != null) "$serverName ($osLabel)" else serverName
                        _uiState.value = _uiState.value.copy(
                            connectedServerName = display,
                            statusMessage = "Connected to $display"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP connection ended: ${e.message}")
                if (!isConnected) {
                    val hint = if (targetHost == "127.0.0.1" || targetHost.equals("localhost", ignoreCase = true)) {
                        "Run mouse_server.py & 'adb reverse tcp:8080 tcp:8080' (or use Scan Wi-Fi)"
                    } else {
                        "Could not reach $targetHost:$targetPort — check mouse_server.py & firewall"
                    }
                    _uiState.value = _uiState.value.copy(
                        phase = TcpConnectionPhase.DISCONNECTED,
                        connectedServerName = null,
                        statusMessage = hint
                    )
                    return@thread
                }
            } finally {
                handleDisconnected("Disconnected")
            }
        }
    }

    fun disconnect() {
        handleDisconnected("Disconnected")
    }

    private fun handleDisconnected(message: String) {
        val wasActive = isConnected || socket != null
        isConnected = false
        clearBuffers()
        closeSocketQuietly()
        if (wasActive || _uiState.value.phase == TcpConnectionPhase.CONNECTED) {
            _uiState.value = _uiState.value.copy(
                phase = TcpConnectionPhase.DISCONNECTED,
                connectedServerName = null,
                statusMessage = message
            )
        }
    }

    private fun closeSocketQuietly() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
    }

    private fun clearBuffers() {
        pendingDx.set(0)
        pendingDy.set(0)
        pendingScroll.set(0)
        commandQueue.clear()
        flushScheduled.set(false)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Coalesced Non-Blocking Input Transmission
    // ────────────────────────────────────────────────────────────────────────

    fun sendMovement(dx: Int, dy: Int) {
        if (!isConnected || (dx == 0 && dy == 0)) return
        pendingDx.addAndGet(dx)
        pendingDy.addAndGet(dy)
        scheduleFlush()
    }

    fun sendScroll(wheelDelta: Int) {
        if (!isConnected || wheelDelta == 0) return
        pendingScroll.addAndGet(wheelDelta)
        scheduleFlush()
    }

    fun setMouseButton(left: Boolean, pressed: Boolean) {
        if (!isConnected) return
        val cmd = when {
            left && pressed -> "LDOWN"
            left && !pressed -> "LUP"
            !left && pressed -> "RDOWN"
            else -> "RUP"
        }
        enqueueDiscreteCommand(cmd)
    }

    fun sendSlide(next: Boolean, usePageKeys: Boolean = false) {
        if (!isConnected) return
        val cmd = if (usePageKeys) {
            if (next) "PGDN" else "PGUP"
        } else {
            if (next) "NEXT" else "PREV"
        }
        enqueueDiscreteCommand(cmd)
    }

    fun sendPresentationAction(action: String) {
        if (!isConnected) return
        enqueueDiscreteCommand(action)
    }

    fun sendCenterCommand() {
        if (!isConnected) return
        enqueueDiscreteCommand("CENTER")
    }

    private fun enqueueDiscreteCommand(cmd: String) {
        commandQueue.offer(cmd)
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            ioExecutor.execute { drainAndFlush() }
        }
    }

    private fun drainAndFlush() {
        flushScheduled.set(false)
        val out = writer ?: return
        if (!isConnected) return

        try {
            var wroteAnything = false

            // 1. Always flush coalesced cursor movement BEFORE discrete clicks/keys
            val dx = pendingDx.getAndSet(0)
            val dy = pendingDy.getAndSet(0)
            if (dx != 0 || dy != 0) {
                out.write("$dx,$dy\n")
                wroteAnything = true
            }

            // 2. Flush coalesced scroll delta
            val scroll = pendingScroll.getAndSet(0)
            if (scroll != 0) {
                out.write("SCROLL $scroll\n")
                wroteAnything = true
            }

            // 3. Flush discrete button/key commands in FIFO order
            while (true) {
                val cmd = commandQueue.poll() ?: break
                out.write(cmd)
                out.write("\n")
                wroteAnything = true
            }

            if (wroteAnything) {
                out.flush()
            }

            // Re-schedule if new events arrived while flushing
            if (pendingDx.get() != 0 || pendingDy.get() != 0 || pendingScroll.get() != 0 || commandQueue.isNotEmpty()) {
                scheduleFlush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket write failed: ${e.message}")
            handleDisconnected("Connection lost")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1-Tap Wi-Fi LAN Auto-Discovery (UDP Broadcast to mouse_server.py)
    // ────────────────────────────────────────────────────────────────────────

    fun discoverLanServers(autoConnectIfSingle: Boolean = true) {
        if (_uiState.value.isScanningLan) return
        _uiState.value = _uiState.value.copy(
            isScanningLan = true,
            statusMessage = "Scanning Wi-Fi for mouse_server.py…"
        )

        thread(name = "LanDiscoveryThread", isDaemon = true) {
            val found = linkedMapOf<String, DiscoveredServer>()
            try {
                DatagramSocket().use { udp ->
                    udp.broadcast = true
                    udp.soTimeout = 350

                    val payload = DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
                    val targets = collectBroadcastAddresses()
                    repeat(2) {
                        for (addr in targets) {
                            try {
                                udp.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
                            } catch (_: Exception) {}
                        }
                    }

                    val buf = ByteArray(512)
                    val deadline = System.currentTimeMillis() + 950L
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            udp.receive(packet)
                            val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                            // Format: LASER_HERE:<hostname>:<os>:<tcpPort>
                            if (msg.startsWith("LASER_HERE:")) {
                                val parts = msg.split(":")
                                val hostName = parts.getOrNull(1)?.ifBlank { "Computer" } ?: "Computer"
                                val osName = parts.getOrNull(2)?.ifBlank { "PC" } ?: "PC"
                                val port = parts.getOrNull(3)?.toIntOrNull() ?: TcpUiState.DEFAULT_PORT
                                val ip = packet.address.hostAddress ?: continue
                                found[ip] = DiscoveredServer(hostName, osName, ip, port)
                            }
                        } catch (_: Exception) {
                            // Timeout slice elapsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LAN discovery error: ${e.message}")
            }

            val list = found.values.toList()
            _uiState.value = _uiState.value.copy(
                isScanningLan = false,
                discoveredServers = list,
                statusMessage = when {
                    list.isEmpty() -> "No Wi-Fi server found — make sure mouse_server.py is running"
                    list.size == 1 -> "Found ${list.first().hostName} (${list.first().ip})"
                    else -> "Found ${list.size} computers on Wi-Fi"
                }
            )

            if (autoConnectIfSingle && list.size == 1 && !isConnected) {
                val s = list.first()
                connect(overrideHost = s.ip, overridePort = s.port)
            }
        }
    }

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) {
                        result.add(bcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return result.toList()
    }

    fun cleanUp() {
        handleDisconnected("Disconnected")
        ioExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "TcpMouseController"
        private const val PREFS_NAME = "laser_pointer_prefs"
        private const val KEY_TCP_HOST = "tcp_host"
        private const val KEY_TCP_PORT = "tcp_port"

        private const val CONNECT_TIMEOUT_MS = 3000
        private const val DISCOVERY_PORT = 8081
        private const val DISCOVER_MAGIC = "LASER_DISCOVER"
    }
}
