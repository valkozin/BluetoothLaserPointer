package com.kozin.bluetoothlaserpointer

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kozin.bluetoothlaserpointer.ui.theme.BluetoothLaserPointerTheme
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    private val viewModel: LaserPointerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake so sensors and Bluetooth L2CAP stay responsive during talks
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        setContent {
            BluetoothLaserPointerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.settings.value.connectionMode == ConnectionMode.BLUETOOTH) {
            viewModel.btController.refreshPairedHosts()
        }
    }

    /**
     * Presenter superpower: Hardware Volume Up / Down advances Previous / Next slide eyes-free!
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val settings = viewModel.settings.value
        if (settings.volumeKeysControlSlides && viewModel.isCurrentlyConnected()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event?.repeatCount == 0) {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        viewModel.sendSlide(next = false)
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event?.repeatCount == 0) {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        viewModel.sendSlide(next = true)
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val settings = viewModel.settings.value
        if (settings.volumeKeysControlSlides && viewModel.isCurrentlyConnected()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: LaserPointerViewModel
) {
    val context = LocalContext.current
    val view = LocalView.current

    val settings by viewModel.settings.collectAsState()
    val btState by viewModel.btController.uiState.collectAsState()
    val tcpState by viewModel.tcpController.uiState.collectAsState()

    val isConnected = when (settings.connectionMode) {
        ConnectionMode.BLUETOOTH -> btState.phase == BtConnectionPhase.CONNECTED
        ConnectionMode.SERVER -> tcpState.phase == TcpConnectionPhase.CONNECTED
    }

    val isConnecting = when (settings.connectionMode) {
        ConnectionMode.BLUETOOTH -> btState.phase == BtConnectionPhase.CONNECTING ||
            btState.phase == BtConnectionPhase.INITIALIZING
        ConnectionMode.SERVER -> tcpState.phase == TcpConnectionPhase.CONNECTING
    }

    // Track whether the user initiated "Pair New Computer" before permission prompt
    var pendingDiscoverableAfterPermission by remember { mutableStateOf(false) }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            viewModel.btController.ensureReady(autoConnectLast = !pendingDiscoverableAfterPermission)
            if (pendingDiscoverableAfterPermission) {
                pendingDiscoverableAfterPermission = false
                viewModel.btController.prepareForNewPairing()
                requestBluetoothDiscoverability(context)
            }
        } else {
            pendingDiscoverableAfterPermission = false
            Toast.makeText(context, "Bluetooth permissions are required for HID mode.", Toast.LENGTH_LONG).show()
        }
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.btController.ensureReady(autoConnectLast = true)
    }

    // ── Zero-Allocation Sensor Motion Pipeline (No UI Recomposition Overhead!) ──
    val currentSettings by rememberUpdatedState(settings)
    val shouldRunSensors = isConnected && settings.shouldStreamMotion

    DisposableEffect(shouldRunSensors) {
        if (!shouldRunSensors) return@DisposableEffect onDispose {}

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val fallbackRotationSensor = if (gyroSensor == null) {
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        } else {
            null
        }

        viewModel.motionConverter.reset()

        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val cfg = currentSettings
                if (!cfg.shouldStreamMotion) return

                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        viewModel.motionConverter.updateGravity(
                            event.values[0],
                            event.values[1],
                            event.values[2],
                            isRawAccelerometer = false
                        )
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        viewModel.motionConverter.updateGravity(
                            event.values[0],
                            event.values[1],
                            event.values[2],
                            isRawAccelerometer = true
                        )
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val (dx, dy) = viewModel.motionConverter.processGyroscope(
                            wx = event.values[0],
                            wy = event.values[1],
                            wz = event.values[2],
                            timestampNs = event.timestamp,
                            sensitivity = cfg.sensitivity,
                            invertY = cfg.invertY
                        )
                        if (dx != 0 || dy != 0) {
                            viewModel.sendMotionDelta(dx, dy)
                        }
                    }
                    Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientationAngles)
                        val azDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                        val piDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                        val (dx, dy) = viewModel.motionConverter.convertOrientationFallback(
                            azimuthDeg = azDeg,
                            pitchDeg = piDeg,
                            sensitivity = cfg.sensitivity,
                            invertY = cfg.invertY
                        )
                        if (dx != 0 || dy != 0) {
                            viewModel.sendMotionDelta(dx, dy)
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // 10,000 us = 100 Hz smooth sampling matching our 100 Hz HID / TCP pacing
        val samplingPeriodUs = 10_000
        if (gyroSensor != null) {
            sensorManager.registerListener(listener, gyroSensor, samplingPeriodUs)
            if (gravitySensor != null) {
                sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
            }
        } else if (fallbackRotationSensor != null) {
            sensorManager.registerListener(listener, fallbackRotationSensor, samplingPeriodUs)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header & Live Connection Pill ────────────────────────────────────
        HeaderSection(
            isConnected = isConnected,
            isConnecting = isConnecting,
            statusLabel = when (settings.connectionMode) {
                ConnectionMode.BLUETOOTH -> when (btState.phase) {
                    BtConnectionPhase.CONNECTED -> "CONNECTED • ${btState.connectedHostName ?: "Host"}"
                    BtConnectionPhase.CONNECTING -> "CONNECTING • ${btState.connectingHostName ?: "Host"}…"
                    BtConnectionPhase.BT_DISABLED -> "BLUETOOTH OFF"
                    BtConnectionPhase.PERMISSION_REQUIRED -> "PERMISSION NEEDED"
                    else -> "DISCONNECTED"
                }
                ConnectionMode.SERVER -> when (tcpState.phase) {
                    TcpConnectionPhase.CONNECTED -> "CONNECTED • ${tcpState.connectedServerName ?: tcpState.host}"
                    TcpConnectionPhase.CONNECTING -> "CONNECTING…"
                    TcpConnectionPhase.DISCONNECTED -> "DISCONNECTED"
                }
            }
        )

        // ── Connection Card (Bluetooth Multi-Host + USB/Wi-Fi Server) ────────
        ConnectionCard(
            connectionMode = settings.connectionMode,
            onModeChange = { viewModel.setConnectionMode(it) },
            btState = btState,
            tcpState = tcpState,
            onGrantBtPermissions = {
                pendingDiscoverableAfterPermission = false
                btPermissionLauncher.launch(requiredBtPermissions())
            },
            onEnableBluetooth = {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
            onSelectBtHost = { address ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                viewModel.btController.connectToHost(address)
            },
            onDisconnectBt = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                viewModel.btController.disconnectCurrentHost()
            },
            onPairNewComputer = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (viewModel.btController.hasRequiredPermissions()) {
                    viewModel.btController.prepareForNewPairing()
                    requestBluetoothDiscoverability(context)
                } else {
                    pendingDiscoverableAfterPermission = true
                    btPermissionLauncher.launch(requiredBtPermissions())
                }
            },
            onTcpHostEdited = { viewModel.tcpController.updateHost(it) },
            onScanWifi = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                viewModel.tcpController.discoverLanServers(autoConnectIfSingle = true)
            },
            onConnectTcp = { hostOverride ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (hostOverride != null) {
                    viewModel.tcpController.connect(overrideHost = hostOverride)
                } else if (tcpState.phase == TcpConnectionPhase.CONNECTED) {
                    viewModel.tcpController.disconnect()
                } else {
                    viewModel.tcpController.connect()
                }
            }
        )

        // ── Presentation & Pointer Controls Card ─────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Slide Navigation Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.sendSlide(next = false)
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("◀  PREV", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Button(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.sendSlide(next = true)
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("NEXT  ▶", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }

                // Dual-Mode Air Trigger / Touchpad + Vertical Scroll Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(156.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TouchpadAndTriggerSurface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        enabled = isConnected,
                        activationMode = settings.activationMode,
                        isGyroEnabled = settings.isMouseEnabled,
                        isFrozen = settings.isFrozen,
                        isTriggerHeld = settings.isTriggerHeld,
                        sensitivity = settings.sensitivity,
                        onTriggerStateChanged = { held ->
                            if (held) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            viewModel.setTriggerHeld(held)
                        },
                        onTouchDelta = { dx, dy ->
                            viewModel.sendMotionDelta(dx, dy)
                        },
                        onSingleTapClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.setMouseButton(left = true, pressed = true)
                            viewModel.setMouseButton(left = true, pressed = false)
                        }
                    )

                    VerticalScrollStrip(
                        modifier = Modifier.width(58.dp).fillMaxHeight(),
                        enabled = isConnected,
                        onScrollTick = { wheelStep ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.sendScroll(wheelStep)
                        }
                    )
                }

                // True Press-and-Hold Left & Right Mouse Click Buttons (Supports Drag!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PressHoldMouseButton(
                        label = "LEFT",
                        subLabel = "Tap or Hold to Drag",
                        enabled = isConnected,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.weight(1f).height(84.dp),
                        onPressChanged = { pressed ->
                            if (pressed) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            viewModel.setMouseButton(left = true, pressed = pressed)
                        }
                    )
                    PressHoldMouseButton(
                        label = "RIGHT",
                        subLabel = "Context Menu",
                        enabled = isConnected,
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.weight(1f).height(84.dp),
                        onPressChanged = { pressed ->
                            if (pressed) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            viewModel.setMouseButton(left = false, pressed = pressed)
                        }
                    )
                }

                // Freeze / Center / Presentation Quick Keys Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (settings.activationMode == PointerActivationMode.ALWAYS_ON) {
                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                viewModel.toggleFreeze()
                            },
                            enabled = isConnected && settings.isMouseEnabled,
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(12.dp),
                            colors = if (settings.isFrozen) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (settings.isFrozen) "UNFREEZE" else "FREEZE",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.recenterPointer()
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (settings.connectionMode == ConnectionMode.SERVER) "CENTER" else "REZERO",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.sendBlackoutScreen()
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(0.95f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                    ) {
                        Text("BLACK", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }

                    FilledTonalButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.sendEscape()
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(0.75f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                    ) {
                        Text("ESC", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }

        // ── Pointer & Presenter Settings Card ────────────────────────────────
        SettingsCard(
            settings = settings,
            onActivationModeChange = { viewModel.setActivationMode(it) },
            onMouseEnabledChange = { viewModel.setMouseEnabled(it) },
            onSensitivityChange = { viewModel.setSensitivity(it) },
            onVolumeSlidesChange = { viewModel.setVolumeKeysControlSlides(it) },
            onUsePageKeysChange = { viewModel.setUsePageKeysForSlides(it) },
            onInvertYChange = { viewModel.setInvertY(it) }
        )
    }
}

@Composable
private fun HeaderSection(
    isConnected: Boolean,
    isConnecting: Boolean,
    statusLabel: String
) {
    val badgeColor = when {
        isConnected -> Color(0xFF2E7D32)
        isConnecting -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Laser Pointer",
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )

        Surface(
            shape = RoundedCornerShape(50),
            color = badgeColor.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                )
                Text(
                    text = statusLabel,
                    color = badgeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connectionMode: ConnectionMode,
    onModeChange: (ConnectionMode) -> Unit,
    btState: BtUiState,
    tcpState: TcpUiState,
    onGrantBtPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onSelectBtHost: (String) -> Unit,
    onDisconnectBt: () -> Unit,
    onPairNewComputer: () -> Unit,
    onTcpHostEdited: (String) -> Unit,
    onScanWifi: () -> Unit,
    onConnectTcp: (String?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connection", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                SegmentedModeSwitch(
                    leftLabel = "Bluetooth",
                    rightLabel = "USB / Wi-Fi",
                    isLeftSelected = connectionMode == ConnectionMode.BLUETOOTH,
                    onSelectLeft = { onModeChange(ConnectionMode.BLUETOOTH) },
                    onSelectRight = { onModeChange(ConnectionMode.SERVER) }
                )
            }

            if (connectionMode == ConnectionMode.BLUETOOTH) {
                when (btState.phase) {
                    BtConnectionPhase.PERMISSION_REQUIRED -> {
                        Button(
                            onClick = onGrantBtPermissions,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Bluetooth Permissions")
                        }
                    }

                    BtConnectionPhase.BT_DISABLED -> {
                        Button(
                            onClick = onEnableBluetooth,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Turn On Bluetooth")
                        }
                    }

                    BtConnectionPhase.BT_UNSUPPORTED -> {
                        Text(
                            text = btState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    else -> {
                        Text(
                            text = btState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 1-Tap Paired Computer Switcher (Mac <-> Windows seamlessly!)
                        if (btState.pairedHosts.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                btState.pairedHosts.forEach { host ->
                                    val isThisConnected = btState.phase == BtConnectionPhase.CONNECTED &&
                                        btState.connectedHostAddress == host.address
                                    val isThisConnecting = btState.phase == BtConnectionPhase.CONNECTING &&
                                        btState.connectingHostAddress == host.address

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                if (isThisConnected) onDisconnectBt()
                                                else onSelectBtHost(host.address)
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        color = when {
                                            isThisConnected -> Color(0xFF2E7D32).copy(alpha = 0.16f)
                                            isThisConnecting -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                            else -> MaterialTheme.colorScheme.surface
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (isThisConnected) Color(0xFF2E7D32)
                                            else MaterialTheme.colorScheme.outlineVariant
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = host.name,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = when {
                                                    isThisConnected -> "Connected (Tap to Disconnect)"
                                                    isThisConnecting -> "Connecting…"
                                                    else -> "Tap to Connect"
                                                },
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isThisConnected) Color(0xFF2E7D32)
                                                else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onPairNewComputer,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Pair New Mac / Windows PC")
                        }
                    }
                }
            } else {
                // USB (adb reverse) & Wi-Fi LAN Server Mode
                var hostDraft by remember(tcpState.host) { mutableStateOf(tcpState.host) }

                Text(
                    text = tcpState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hostDraft,
                        onValueChange = {
                            hostDraft = it
                            onTcpHostEdited(it)
                        },
                        label = { Text("Host IP (127.0.0.1 = USB)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        )
                    )
                    FilledTonalButton(
                        onClick = {
                            hostDraft = "127.0.0.1"
                            onTcpHostEdited("127.0.0.1")
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                    ) {
                        Text("USB", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Discovered Wi-Fi Computers
                if (tcpState.discoveredServers.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tcpState.discoveredServers.forEach { srv ->
                            AssistChip(
                                onClick = {
                                    hostDraft = srv.ip
                                    onConnectTcp(srv.ip)
                                },
                                label = { Text("${srv.hostName} (${srv.osName})") }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onScanWifi,
                        enabled = !tcpState.isScanningLan,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (tcpState.isScanningLan) "Scanning…" else "Scan Wi-Fi")
                    }
                    Button(
                        onClick = { onConnectTcp(null) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (tcpState.phase == TcpConnectionPhase.CONNECTED) "Disconnect"
                            else "Connect"
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dual-purpose surface:
 * - In HOLD_TRIGGER mode: holding thumb activates Air Gyroscope; releasing freezes cursor instantly.
 * - In ALL modes: sliding thumb acts as a smooth Trackpad, and tapping performs a quick Left Click!
 */
@Composable
private fun TouchpadAndTriggerSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    activationMode: PointerActivationMode,
    isGyroEnabled: Boolean,
    isFrozen: Boolean,
    isTriggerHeld: Boolean,
    sensitivity: Float,
    onTriggerStateChanged: (Boolean) -> Unit,
    onTouchDelta: (Int, Int) -> Unit,
    onSingleTapClick: () -> Unit
) {
    val curEnabled by rememberUpdatedState(enabled)
    val curSensitivity by rememberUpdatedState(sensitivity)
    val curOnTrigger by rememberUpdatedState(onTriggerStateChanged)
    val curOnDelta by rememberUpdatedState(onTouchDelta)
    val curOnTap by rememberUpdatedState(onSingleTapClick)

    val isActiveAir = enabled && isGyroEnabled && when (activationMode) {
        PointerActivationMode.ALWAYS_ON -> !isFrozen
        PointerActivationMode.HOLD_TRIGGER -> isTriggerHeld
    }

    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        isActiveAir -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isActiveAir) Color(0xFF4CAF50).copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface
            )
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!curEnabled) return@awaitEachGesture

                    curOnTrigger(true)
                    val downTime = System.currentTimeMillis()
                    var prevPos = down.position
                    var totalTravel = 0f
                    var remX = 0f
                    var remY = 0f

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val activeChange = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            event.changes.forEach { it.consume() }

                            if (activeChange == null || !activeChange.pressed) break

                            val delta = activeChange.position - prevPos
                            prevPos = activeChange.position
                            val stepDist = hypot(delta.x, delta.y)
                            totalTravel += stepDist

                            // Ignore micro thumb-rest jitter (< 8px total) while holding Air Trigger
                            if (totalTravel > 8f) {
                                val gain = 0.55f + (curSensitivity * 0.08f)
                                val sx = remX + delta.x * gain
                                val sy = remY + delta.y * gain
                                val ix = sx.toInt().coerceIn(-127, 127)
                                val iy = sy.toInt().coerceIn(-127, 127)
                                remX = sx - ix
                                remY = sy - iy
                                if (ix != 0 || iy != 0) {
                                    curOnDelta(ix, iy)
                                }
                            }
                        }

                        val duration = System.currentTimeMillis() - downTime
                        if (totalTravel <= 10f && duration < 230L) {
                            curOnTap()
                        }
                    } finally {
                        curOnTrigger(false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = when {
                    !enabled -> "TOUCHPAD & LASER TRIGGER"
                    activationMode == PointerActivationMode.HOLD_TRIGGER && isTriggerHeld -> "LASER ACTIVE"
                    activationMode == PointerActivationMode.HOLD_TRIGGER -> "HOLD THUMB TO POINT"
                    !isGyroEnabled -> "TRACKPAD SURFACE"
                    isFrozen -> "AIR FROZEN • TRACKPAD READY"
                    else -> "AIR LASER ACTIVE • TRACKPAD"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (isActiveAir) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (activationMode == PointerActivationMode.HOLD_TRIGGER) {
                    "Hold to move with wrist • Slide to nudge • Tap to click"
                } else {
                    "Slide thumb to fine-tune • Tap to click"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Dedicated vertical scroll strip on the right edge (works in both Bluetooth HID & Server modes).
 */
@Composable
private fun VerticalScrollStrip(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onScrollTick: (Int) -> Unit
) {
    val curEnabled by rememberUpdatedState(enabled)
    val curOnScroll by rememberUpdatedState(onScrollTick)
    var isTouching by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isTouching) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp)
            )
            .pointerInput(Unit) {
                val pxPerTick = 26f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!curEnabled) return@awaitEachGesture

                    isTouching = true
                    var prevY = down.position.y
                    var accumPx = 0f
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            event.changes.forEach { it.consume() }
                            if (change == null || !change.pressed) break

                            val dy = change.position.y - prevY
                            prevY = change.position.y
                            accumPx += dy

                            if (abs(accumPx) >= pxPerTick) {
                                val ticks = (accumPx / pxPerTick).toInt()
                                accumPx -= ticks * pxPerTick
                                // Dragging finger down -> negative HID wheel (scroll down)
                                curOnScroll((-ticks).coerceIn(-8, 8))
                            }
                        }
                    } finally {
                        isTouching = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("▲", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "S\nC\nR\nL",
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text("▼", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Low-latency press-and-hold mouse button that never gets cancelled by parent scroll
 * and always guarantees a matching button-up release event.
 */
@Composable
private fun PressHoldMouseButton(
    label: String,
    subLabel: String,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onPressChanged: (Boolean) -> Unit
) {
    val curEnabled by rememberUpdatedState(enabled)
    val curOnPress by rememberUpdatedState(onPressChanged)
    var isPressed by remember { mutableStateOf(false) }

    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        isPressed -> containerColor.copy(alpha = 0.75f)
        else -> containerColor
    }
    val fg = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!curEnabled) return@awaitEachGesture

                    isPressed = true
                    curOnPress(true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        isPressed = false
                        curOnPress(false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = fg)
            Text(subLabel, fontSize = 11.sp, color = fg.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun SettingsCard(
    settings: AppSettingsState,
    onActivationModeChange: (PointerActivationMode) -> Unit,
    onMouseEnabledChange: (Boolean) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onVolumeSlidesChange: (Boolean) -> Unit,
    onUsePageKeysChange: (Boolean) -> Unit,
    onInvertYChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pointer Trigger", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                SegmentedModeSwitch(
                    leftLabel = "Always On",
                    rightLabel = "Hold Trigger",
                    isLeftSelected = settings.activationMode == PointerActivationMode.ALWAYS_ON,
                    onSelectLeft = { onActivationModeChange(PointerActivationMode.ALWAYS_ON) },
                    onSelectRight = { onActivationModeChange(PointerActivationMode.HOLD_TRIGGER) }
                )
            }

            Column {
                Text(
                    text = "Sensitivity: ${String.format("%.1f", settings.sensitivity)}",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = settings.sensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange = 1f..30f
                )
            }

            SettingToggleRow(
                title = "Air Gyroscope Motion",
                subtitle = "Disable to use as a pure Trackpad + Slide Clicker",
                checked = settings.isMouseEnabled,
                onCheckedChange = onMouseEnabledChange
            )

            SettingToggleRow(
                title = "Volume Keys Control Slides",
                subtitle = "Vol Up = Prev Slide, Vol Down = Next Slide",
                checked = settings.volumeKeysControlSlides,
                onCheckedChange = onVolumeSlidesChange
            )

            SettingToggleRow(
                title = "Use PageUp / PageDown Keys",
                subtitle = "Off = Left/Right Arrow (best for Keynote, PowerPoint & Slides)",
                checked = settings.usePageKeysForSlides,
                onCheckedChange = onUsePageKeysChange
            )

            SettingToggleRow(
                title = "Invert Vertical Tilt",
                subtitle = null,
                checked = settings.invertY,
                onCheckedChange = onInvertYChange
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SegmentedModeSwitch(
    leftLabel: String,
    rightLabel: String,
    isLeftSelected: Boolean,
    onSelectLeft: () -> Unit,
    onSelectRight: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
            .padding(3.dp)
    ) {
        TextButton(
            onClick = onSelectLeft,
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (isLeftSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (isLeftSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            ),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(leftLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(
            onClick = onSelectRight,
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (!isLeftSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (!isLeftSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            ),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(rightLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun requiredBtPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
}

@android.annotation.SuppressLint("MissingPermission")
private fun requestBluetoothDiscoverability(context: Context) {
    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
    }
    if (context is Activity) {
        context.startActivity(intent)
    }
}
