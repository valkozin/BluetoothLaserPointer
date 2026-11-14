package com.kozin.bluetoothlaserpointer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class BtConnectionPhase {
    BT_UNSUPPORTED,
    PERMISSION_REQUIRED,
    BT_DISABLED,
    INITIALIZING,
    READY_DISCONNECTED,
    CONNECTING,
    CONNECTED
}

data class PairedHost(
    val name: String,
    val address: String,
    val isComputer: Boolean
)

data class BtUiState(
    val phase: BtConnectionPhase = BtConnectionPhase.INITIALIZING,
    val connectedHostName: String? = null,
    val connectedHostAddress: String? = null,
    val connectingHostName: String? = null,
    val connectingHostAddress: String? = null,
    val pairedHosts: List<PairedHost> = emptyList(),
    val lastHostAddress: String? = null,
    val statusMessage: String = "Initializing Bluetooth…"
)

@SuppressLint("MissingPermission")
class BluetoothHidController(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bluetoothAdapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val hidIoExecutor = Executors.newSingleThreadScheduledExecutor()

    private val _uiState = MutableStateFlow(BtUiState())
    val uiState: StateFlow<BtUiState> = _uiState.asStateFlow()

    @Volatile private var bluetoothHidDevice: BluetoothHidDevice? = null
    @Volatile private var activeHost: BluetoothDevice? = null
    @Volatile private var isReceiverRegistered = false
    @Volatile private var isProxyRequested = false
    @Volatile private var isAppRegistered = false
    @Volatile private var isAppRegistering = false

    // Multi-host connection & switch coordination (accessed on mainHandler)
    private var targetConnectAddress: String? = null
    private var pendingSwitchAddress: String? = null
    private var recentlyDisconnectedAddress: String? = null
    private var recentlyDisconnectedUntilMs: Long = 0L
    private var retryAttempt = 0
    private val retryRunnable = Runnable { performRetryStep() }
    private val switchTimeoutRunnable = Runnable { completePendingSwitch() }

    // HID report state (accessed on hidIoExecutor)
    @Volatile private var pressedButtons: Int = 0
    @Volatile private var keyboardLeds: Byte = 0
    private var lastButtonDownMs: Long = 0L
    private val accumDx = AtomicInteger(0)
    private val accumDy = AtomicInteger(0)
    private val accumWheel = AtomicInteger(0)
    private var flushFuture: ScheduledFuture<*>? = null

    fun hasRequiredPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        return perms.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getLastHostAddress(): String? = prefs.getString(KEY_LAST_HOST_ADDRESS, null)

    private fun saveLastHostAddress(address: String) {
        prefs.edit().putString(KEY_LAST_HOST_ADDRESS, address).apply()
    }

    // ────────────────────────────────────────────────────────────────────────
    // System Broadcast Receiver (Bonding, Adapter Power, ACL Drops)
    // ────────────────────────────────────────────────────────────────────────
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    mainHandler.post { handleAdapterStateChanged(state) }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val device = extractDevice(intent)
                    mainHandler.post { handleBondStateChanged(device, bondState) }
                }
            }
        }
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun handleAdapterStateChanged(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                cancelConnectionTimers()
                activeHost = null
                isAppRegistered = false
                isAppRegistering = false
                isProxyRequested = false
                bluetoothHidDevice = null
                publishState(
                    phase = BtConnectionPhase.BT_DISABLED,
                    statusMessage = "Bluetooth is turned off"
                )
            }
            BluetoothAdapter.STATE_ON -> {
                ensureReady(autoConnectLast = true)
            }
        }
    }

    private fun handleBondStateChanged(device: BluetoothDevice?, bondState: Int) {
        if (!hasRequiredPermissions()) return
        refreshPairedHosts()
        if (bondState == BluetoothDevice.BOND_BONDED && device != null) {
            Log.d(TAG, "Bond completed with ${safeName(device)} (${device.address}) — connecting HID")
            connectToHost(device.address)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Bluetooth HID Callbacks
    // ────────────────────────────────────────────────────────────────────────
    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered plugged=${pluggedDevice?.address}")
            mainHandler.post {
                isAppRegistering = false
                isAppRegistered = registered
                if (!registered) {
                    activeHost = null
                    publishState(
                        phase = if (bluetoothAdapter?.isEnabled == true) {
                            BtConnectionPhase.READY_DISCONNECTED
                        } else {
                            BtConnectionPhase.BT_DISABLED
                        },
                        statusMessage = "HID profile unregistered"
                    )
                    return@post
                }

                refreshPairedHosts()

                // 1. Check if a host is already connected at the profile level
                val alreadyConnected = bluetoothHidDevice?.connectedDevices?.firstOrNull()
                if (alreadyConnected != null) {
                    adoptConnectedHost(alreadyConnected)
                    return@post
                }

                // 2. If user explicitly requested a host while registering, connect to it now
                val explicitTarget = targetConnectAddress
                if (explicitTarget != null) {
                    startConnectSequence(explicitTarget)
                    return@post
                }

                // 3. Gentle startup auto-connect: ONLY connect to the single last-used computer
                //    (or the sole paired computer if only one exists). Never spam all bonded devices!
                val hosts = queryPairedHosts()
                val autoTarget = getLastHostAddress()?.takeIf { saved -> hosts.any { it.address == saved } }
                    ?: hosts.singleOrNull { it.isComputer }?.address

                if (autoTarget != null) {
                    startConnectSequence(autoTarget)
                } else {
                    publishState(
                        phase = BtConnectionPhase.READY_DISCONNECTED,
                        statusMessage = if (hosts.isEmpty()) {
                            "Ready — tap Pair New Computer"
                        } else {
                            "Select a computer below to connect"
                        }
                    )
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: device=${device?.address} state=$state")
            mainHandler.post {
                if (device == null) return@post

                when (state) {
                    BluetoothProfile.STATE_CONNECTING -> {
                        if (activeHost == null) {
                            publishState(
                                phase = BtConnectionPhase.CONNECTING,
                                connectingHostName = safeName(device),
                                connectingHostAddress = device.address,
                                statusMessage = "Connecting to ${safeName(device)}…"
                            )
                        }
                    }

                    BluetoothProfile.STATE_CONNECTED -> {
                        // Guard against the old host immediately grabbing back the link while
                        // the user is explicitly switching to a different computer!
                        val now = SystemClock.elapsedRealtime()
                        val switchingTo = pendingSwitchAddress ?: targetConnectAddress
                        if (switchingTo != null &&
                            switchingTo != device.address &&
                            recentlyDisconnectedAddress == device.address &&
                            now < recentlyDisconnectedUntilMs
                        ) {
                            Log.d(TAG, "Rejecting auto-reconnect from ${safeName(device)} during switch to $switchingTo")
                            bluetoothHidDevice?.disconnect(device)
                            return@post
                        }

                        adoptConnectedHost(device)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (activeHost?.address == device.address) {
                            activeHost = null
                            resetInputState()
                        }

                        // If we were waiting for this old host to disconnect in order to switch:
                        if (pendingSwitchAddress != null) {
                            mainHandler.removeCallbacks(switchTimeoutRunnable)
                            completePendingSwitch()
                            return@post
                        }

                        // If we are still in an active retry loop for targetConnectAddress, let retryRunnable continue
                        if (targetConnectAddress != null && retryAttempt < MAX_CONNECT_RETRIES) {
                            return@post
                        }

                        if (activeHost == null) {
                            targetConnectAddress = null
                            publishState(
                                phase = BtConnectionPhase.READY_DISCONNECTED,
                                statusMessage = "Disconnected"
                            )
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val hid = bluetoothHidDevice ?: return
            val reportId = id.toInt() and 0xFF
            val data = when {
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && reportId == MouseReport.ID_MOUSE ->
                    MouseReport.buildMouseReport(pressedButtons, 0, 0, 0)
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && reportId == MouseReport.ID_KEYBOARD ->
                    ByteArray(MouseReport.KEYBOARD_INPUT_SIZE)
                type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && reportId == MouseReport.ID_KEYBOARD ->
                    byteArrayOf(keyboardLeds)
                else ->
                    ByteArray(bufferSize.coerceAtLeast(1))
            }
            hid.replyReport(device, type, id, data)
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                (id.toInt() and 0xFF) == MouseReport.ID_KEYBOARD &&
                data != null && data.isNotEmpty()
            ) {
                keyboardLeds = data[0]
            }
            bluetoothHidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            Log.d(TAG, "onSetProtocol: device=${device?.address} protocol=$protocol")
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            if ((reportId.toInt() and 0xFF) == MouseReport.ID_KEYBOARD && data != null && data.isNotEmpty()) {
                keyboardLeds = data[0]
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            Log.d(TAG, "onVirtualCableUnplug: ${device?.address}")
            mainHandler.post {
                if (activeHost?.address == device?.address) {
                    activeHost = null
                    resetInputState()
                }
                publishState(
                    phase = BtConnectionPhase.READY_DISCONNECTED,
                    statusMessage = "Unplugged from host"
                )
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = proxy as BluetoothHidDevice
                isProxyRequested = false
                Log.d(TAG, "BluetoothHidDevice profile proxy connected")
                mainHandler.post { registerHidAppIfNeeded() }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "BluetoothHidDevice profile proxy disconnected")
                bluetoothHidDevice = null
                isProxyRequested = false
                isAppRegistered = false
                isAppRegistering = false
                activeHost = null
                mainHandler.post {
                    publishState(
                        phase = if (bluetoothAdapter?.isEnabled == true) {
                            BtConnectionPhase.INITIALIZING
                        } else {
                            BtConnectionPhase.BT_DISABLED
                        },
                        statusMessage = "Bluetooth HID service disconnected"
                    )
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Public Lifecycle & Connection Control
    // ────────────────────────────────────────────────────────────────────────

    fun init() {
        if (bluetoothAdapter == null) {
            publishState(
                phase = BtConnectionPhase.BT_UNSUPPORTED,
                statusMessage = "Bluetooth is not supported on this device"
            )
            return
        }

        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(
                appContext,
                btStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
        }

        ensureReady(autoConnectLast = true)
    }

    fun ensureReady(autoConnectLast: Boolean = true) {
        if (bluetoothAdapter == null) {
            publishState(BtConnectionPhase.BT_UNSUPPORTED, statusMessage = "Bluetooth unsupported")
            return
        }
        if (!hasRequiredPermissions()) {
            publishState(
                phase = BtConnectionPhase.PERMISSION_REQUIRED,
                statusMessage = "Bluetooth permission required"
            )
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            publishState(
                phase = BtConnectionPhase.BT_DISABLED,
                statusMessage = "Turn on Bluetooth to connect"
            )
            return
        }

        refreshPairedHosts()

        val hid = bluetoothHidDevice
        if (hid == null) {
            if (!isProxyRequested) {
                isProxyRequested = true
                publishState(
                    phase = BtConnectionPhase.INITIALIZING,
                    statusMessage = "Starting Bluetooth HID service…"
                )
                bluetoothAdapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
            }
            return
        }

        if (!isAppRegistered) {
            registerHidAppIfNeeded()
        } else if (autoConnectLast && activeHost == null && targetConnectAddress == null) {
            val existing = hid.connectedDevices.firstOrNull()
            if (existing != null) {
                adoptConnectedHost(existing)
            } else {
                val hosts = queryPairedHosts()
                val autoTarget = getLastHostAddress()?.takeIf { saved -> hosts.any { it.address == saved } }
                    ?: hosts.singleOrNull { it.isComputer }?.address
                if (autoTarget != null) {
                    startConnectSequence(autoTarget)
                } else {
                    publishState(
                        phase = BtConnectionPhase.READY_DISCONNECTED,
                        statusMessage = "Select a computer to connect"
                    )
                }
            }
        }
    }

    private fun registerHidAppIfNeeded() {
        if (!hasRequiredPermissions()) {
            publishState(BtConnectionPhase.PERMISSION_REQUIRED, statusMessage = "Bluetooth permission required")
            return
        }
        val hid = bluetoothHidDevice ?: return
        if (isAppRegistered || isAppRegistering) return

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Laser Pointer Pro",
            "Wireless Laser Pointer, Mouse & Presentation Remote",
            "Kozin",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            MouseReport.COMPOSITE_REPORT_DESCRIPTOR
        )

        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        isAppRegistering = true
        publishState(
            phase = BtConnectionPhase.INITIALIZING,
            statusMessage = "Registering HID Mouse + Keyboard…"
        )
        val submitted = hid.registerApp(sdp, null, qos, callbackExecutor, hidCallback)
        Log.d(TAG, "registerApp submitted=$submitted")
        if (!submitted) {
            isAppRegistering = false
            // If a stale registration existed in system server, unregister once cleanly and retry
            hid.unregisterApp()
            mainHandler.postDelayed({
                val retryHid = bluetoothHidDevice ?: return@postDelayed
                isAppRegistering = true
                val retryOk = retryHid.registerApp(sdp, null, qos, callbackExecutor, hidCallback)
                if (!retryOk) {
                    isAppRegistering = false
                    publishState(
                        phase = BtConnectionPhase.READY_DISCONNECTED,
                        statusMessage = "Could not register HID profile — toggle Bluetooth off/on"
                    )
                }
            }, 350L)
        }
    }

    /**
     * Connects or switches cleanly to a specific paired host (Windows or Mac).
     */
    fun connectToHost(address: String) {
        mainHandler.post {
            if (!hasRequiredPermissions()) {
                publishState(BtConnectionPhase.PERMISSION_REQUIRED, statusMessage = "Bluetooth permission required")
                return@post
            }
            saveLastHostAddress(address)
            cancelConnectionTimers()

            val current = activeHost
            if (current != null && current.address == address) {
                adoptConnectedHost(current)
                return@post
            }

            if (!isAppRegistered || bluetoothHidDevice == null) {
                targetConnectAddress = address
                ensureReady(autoConnectLast = false)
                return@post
            }

            // Disconnect any currently connected host BEFORE connecting the new target!
            val connectedList = bluetoothHidDevice?.connectedDevices.orEmpty()
            val toDisconnect = (listOfNotNull(current) + connectedList)
                .distinctBy { it.address }
                .filter { it.address != address }

            if (toDisconnect.isNotEmpty()) {
                val oldHost = toDisconnect.first()
                recentlyDisconnectedAddress = oldHost.address
                recentlyDisconnectedUntilMs = SystemClock.elapsedRealtime() + 4000L
                pendingSwitchAddress = address
                activeHost = null
                resetInputState()

                val targetName = findDevice(address)?.let { safeName(it) } ?: address
                publishState(
                    phase = BtConnectionPhase.CONNECTING,
                    connectingHostName = targetName,
                    connectingHostAddress = address,
                    statusMessage = "Switching to $targetName…"
                )
                toDisconnect.forEach { bluetoothHidDevice?.disconnect(it) }
                mainHandler.postDelayed(switchTimeoutRunnable, 650L)
            } else {
                startConnectSequence(address)
            }
        }
    }

    /**
     * Prepares the phone to pair with a brand-new Mac or Windows PC by freeing any existing
     * HID connection and ensuring the HID SDP record is active.
     */
    fun prepareForNewPairing() {
        mainHandler.post {
            cancelConnectionTimers()
            val hid = bluetoothHidDevice
            if (hid != null && hasRequiredPermissions()) {
                val toDisconnect = (listOfNotNull(activeHost) + hid.connectedDevices).distinctBy { it.address }
                if (toDisconnect.isNotEmpty()) {
                    recentlyDisconnectedAddress = toDisconnect.first().address
                    recentlyDisconnectedUntilMs = SystemClock.elapsedRealtime() + 6000L
                    toDisconnect.forEach { hid.disconnect(it) }
                }
            }
            activeHost = null
            resetInputState()
            ensureReady(autoConnectLast = false)
            publishState(
                phase = if (isAppRegistered) BtConnectionPhase.READY_DISCONNECTED else _uiState.value.phase,
                statusMessage = "Discoverable — select your phone in Mac/Windows Bluetooth settings"
            )
        }
    }

    fun disconnectCurrentHost() {
        mainHandler.post {
            cancelConnectionTimers()
            val hid = bluetoothHidDevice
            if (hid != null && hasRequiredPermissions()) {
                val toDisconnect = (listOfNotNull(activeHost) + hid.connectedDevices).distinctBy { it.address }
                if (toDisconnect.isNotEmpty()) {
                    recentlyDisconnectedAddress = toDisconnect.first().address
                    recentlyDisconnectedUntilMs = SystemClock.elapsedRealtime() + 4000L
                    toDisconnect.forEach { hid.disconnect(it) }
                }
            }
            activeHost = null
            resetInputState()
            publishState(
                phase = BtConnectionPhase.READY_DISCONNECTED,
                statusMessage = "Disconnected"
            )
        }
    }

    private fun completePendingSwitch() {
        val nextAddress = pendingSwitchAddress ?: return
        pendingSwitchAddress = null
        startConnectSequence(nextAddress)
    }

    private fun startConnectSequence(address: String) {
        val hid = bluetoothHidDevice ?: return
        val device = findDevice(address)
        if (device == null) {
            publishState(
                phase = BtConnectionPhase.READY_DISCONNECTED,
                statusMessage = "Device not paired"
            )
            return
        }

        // If already connected at OS HID profile level, adopt immediately
        if (hid.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            adoptConnectedHost(device)
            return
        }

        targetConnectAddress = address
        retryAttempt = 0
        val name = safeName(device)
        publishState(
            phase = BtConnectionPhase.CONNECTING,
            connectingHostName = name,
            connectingHostAddress = address,
            statusMessage = "Connecting to $name…"
        )
        performRetryStep()
    }

    private fun performRetryStep() {
        val address = targetConnectAddress ?: return
        if (activeHost != null) return
        val hid = bluetoothHidDevice ?: return
        val device = findDevice(address) ?: return

        if (hid.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            adoptConnectedHost(device)
            return
        }

        if (retryAttempt >= MAX_CONNECT_RETRIES) {
            targetConnectAddress = null
            publishState(
                phase = BtConnectionPhase.READY_DISCONNECTED,
                statusMessage = "Could not reach ${safeName(device)} — make sure its Bluetooth is on"
            )
            return
        }

        retryAttempt++
        Log.d(TAG, "HID connect attempt $retryAttempt/$MAX_CONNECT_RETRIES -> ${safeName(device)}")
        hid.connect(device)
        mainHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
    }

    private fun adoptConnectedHost(device: BluetoothDevice) {
        cancelConnectionTimers()
        activeHost = device
        saveLastHostAddress(device.address)
        val name = safeName(device)
        publishState(
            phase = BtConnectionPhase.CONNECTED,
            connectedHostName = name,
            connectedHostAddress = device.address,
            statusMessage = "Connected to $name"
        )
    }

    private fun cancelConnectionTimers() {
        targetConnectAddress = null
        pendingSwitchAddress = null
        retryAttempt = 0
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(switchTimeoutRunnable)
    }

    // ────────────────────────────────────────────────────────────────────────
    // High-Frequency Paced HID Input Reports (Mouse, Scroll, Drag, Keyboard)
    // ────────────────────────────────────────────────────────────────────────

    fun sendMouseDelta(dx: Int, dy: Int) {
        if (activeHost == null || (dx == 0 && dy == 0)) return
        accumDx.updateAndGet { (it + dx).coerceIn(-300, 300) }
        accumDy.updateAndGet { (it + dy).coerceIn(-300, 300) }
        scheduleMouseFlush()
    }

    fun sendScroll(wheelDelta: Int) {
        if (activeHost == null || wheelDelta == 0) return
        accumWheel.updateAndGet { (it + wheelDelta).coerceIn(-60, 60) }
        scheduleMouseFlush()
    }

    private fun scheduleMouseFlush() {
        hidIoExecutor.execute {
            if (flushFuture == null || flushFuture?.isDone == true) {
                flushMouseStep()
            }
        }
    }

    private fun flushMouseStep() {
        val device = activeHost
        val hid = bluetoothHidDevice
        if (device == null || hid == null) {
            accumDx.set(0)
            accumDy.set(0)
            accumWheel.set(0)
            flushFuture = null
            return
        }

        val curX = accumDx.get()
        val curY = accumDy.get()
        val curW = accumWheel.get()
        if (curX == 0 && curY == 0 && curW == 0) {
            flushFuture = null
            return
        }

        val stepX = curX.coerceIn(-127, 127)
        val stepY = curY.coerceIn(-127, 127)
        val stepW = curW.coerceIn(-127, 127)

        val report = MouseReport.buildMouseReport(pressedButtons, stepX, stepY, stepW)
        val sent = hid.sendReport(device, MouseReport.ID_MOUSE, report)
        if (sent) {
            val remX = accumDx.addAndGet(-stepX)
            val remY = accumDy.addAndGet(-stepY)
            val remW = accumWheel.addAndGet(-stepW)
            flushFuture = if (remX != 0 || remY != 0 || remW != 0) {
                hidIoExecutor.schedule({ flushMouseStep() }, REPORT_INTERVAL_MS, TimeUnit.MILLISECONDS)
            } else {
                null
            }
        } else {
            // Bluetooth buffer momentarily full; retry next HID frame without dropping delta
            flushFuture = hidIoExecutor.schedule({ flushMouseStep() }, REPORT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Supports rapid double-clicks, quick taps, AND press-and-hold (click & drag)
     * in strict FIFO order with guaranteed button release.
     */
    fun setMouseButton(buttonMask: Int, pressed: Boolean) {
        hidIoExecutor.execute {
            val device = activeHost ?: return@execute
            val hid = bluetoothHidDevice ?: return@execute

            if (pressed) {
                if ((pressedButtons and buttonMask) != 0) return@execute
                pressedButtons = pressedButtons or buttonMask
                lastButtonDownMs = SystemClock.elapsedRealtime()
                val report = MouseReport.buildMouseReport(pressedButtons, 0, 0, 0)
                hid.sendReport(device, MouseReport.ID_MOUSE, report)
            } else {
                if ((pressedButtons and buttonMask) == 0) return@execute
                val elapsed = SystemClock.elapsedRealtime() - lastButtonDownMs
                if (elapsed in 0 until MIN_CLICK_HOLD_MS) {
                    try {
                        Thread.sleep(MIN_CLICK_HOLD_MS - elapsed)
                    } catch (_: InterruptedException) {}
                }
                pressedButtons = pressedButtons and buttonMask.inv()
                val curDev = activeHost ?: return@execute
                val curHid = bluetoothHidDevice ?: return@execute
                val report = MouseReport.buildMouseReport(pressedButtons, 0, 0, 0)
                curHid.sendReport(curDev, MouseReport.ID_MOUSE, report)
            }
        }
    }

    fun sendKeyTap(keyCode: Byte, modifiers: Byte = MouseReport.MOD_NONE) {
        hidIoExecutor.execute {
            val device = activeHost ?: return@execute
            val hid = bluetoothHidDevice ?: return@execute

            val press = MouseReport.buildKeyboardReport(modifiers = modifiers, keyCode = keyCode)
            hid.sendReport(device, MouseReport.ID_KEYBOARD, press)

            try {
                Thread.sleep(KEY_HOLD_MS)
            } catch (_: InterruptedException) {}

            val curDev = activeHost ?: return@execute
            val curHid = bluetoothHidDevice ?: return@execute
            val release = MouseReport.buildKeyboardReport()
            curHid.sendReport(curDev, MouseReport.ID_KEYBOARD, release)
        }
    }

    private fun resetInputState() {
        pressedButtons = 0
        accumDx.set(0)
        accumDy.set(0)
        accumWheel.set(0)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Device Discovery & Helpers
    // ────────────────────────────────────────────────────────────────────────

    fun refreshPairedHosts() {
        val current = _uiState.value
        _uiState.value = current.copy(
            pairedHosts = queryPairedHosts(),
            lastHostAddress = getLastHostAddress()
        )
    }

    private fun queryPairedHosts(): List<PairedHost> {
        if (!hasRequiredPermissions() || bluetoothAdapter == null) return emptyList()
        val bonded = try {
            bluetoothAdapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }

        val lastAddr = getLastHostAddress()
        val activeAddr = activeHost?.address

        return bonded
            .filter { device ->
                val major = device.bluetoothClass?.majorDeviceClass
                val isAlwaysExcluded = major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    major == BluetoothClass.Device.Major.WEARABLE ||
                    major == BluetoothClass.Device.Major.HEALTH ||
                    major == BluetoothClass.Device.Major.TOY ||
                    major == BluetoothClass.Device.Major.IMAGING
                !isAlwaysExcluded || device.address == activeAddr || device.address == lastAddr
            }
            .map { device ->
                val major = device.bluetoothClass?.majorDeviceClass
                PairedHost(
                    name = safeName(device),
                    address = device.address,
                    isComputer = major == BluetoothClass.Device.Major.COMPUTER
                )
            }
            .sortedWith(
                compareByDescending<PairedHost> { it.address == activeAddr }
                    .thenByDescending { it.address == lastAddr }
                    .thenByDescending { it.isComputer }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun findDevice(address: String): BluetoothDevice? {
        if (!hasRequiredPermissions() || bluetoothAdapter == null) return null
        return try {
            bluetoothAdapter.bondedDevices?.firstOrNull { it.address == address }
                ?: bluetoothAdapter.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }
    }

    private fun safeName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
    }

    private fun publishState(
        phase: BtConnectionPhase,
        connectedHostName: String? = if (phase == BtConnectionPhase.CONNECTED) activeHost?.let { safeName(it) } else null,
        connectedHostAddress: String? = if (phase == BtConnectionPhase.CONNECTED) activeHost?.address else null,
        connectingHostName: String? = null,
        connectingHostAddress: String? = null,
        statusMessage: String = _uiState.value.statusMessage
    ) {
        _uiState.value = BtUiState(
            phase = phase,
            connectedHostName = connectedHostName,
            connectedHostAddress = connectedHostAddress,
            connectingHostName = connectingHostName,
            connectingHostAddress = connectingHostAddress,
            pairedHosts = queryPairedHosts(),
            lastHostAddress = getLastHostAddress(),
            statusMessage = statusMessage
        )
    }

    fun cleanUp() {
        cancelConnectionTimers()
        resetInputState()
        if (isReceiverRegistered) {
            try {
                appContext.unregisterReceiver(btStateReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        try {
            if (hasRequiredPermissions()) {
                bluetoothHidDevice?.unregisterApp()
            }
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHidDevice)
        } catch (_: Exception) {}
        bluetoothHidDevice = null
        hidIoExecutor.shutdownNow()
        callbackExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "BluetoothHidController"
        private const val PREFS_NAME = "laser_pointer_prefs"
        private const val KEY_LAST_HOST_ADDRESS = "last_bt_host_address"

        private const val MAX_CONNECT_RETRIES = 6
        private const val RETRY_INTERVAL_MS = 1600L
        private const val REPORT_INTERVAL_MS = 10L   // 100 Hz smooth HID report pacing
        private const val MIN_CLICK_HOLD_MS = 25L
        private const val KEY_HOLD_MS = 30L
    }
}
