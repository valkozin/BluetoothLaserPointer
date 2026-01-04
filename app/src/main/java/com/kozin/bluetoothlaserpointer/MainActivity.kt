package com.kozin.bluetoothlaserpointer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kozin.bluetoothlaserpointer.ui.theme.BluetoothLaserPointerTheme

enum class ConnectionMode { USB, BLUETOOTH }

class MainActivity : ComponentActivity() {
    private lateinit var tcpController: TcpMouseController
    private lateinit var btController: BluetoothHidController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        tcpController = TcpMouseController()
        btController = BluetoothHidController(this)
        btController.init()

        setContent {
            BluetoothLaserPointerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        tcpController = tcpController,
                        btController = btController
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tcpController.disconnect()
        btController.cleanUp()
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    tcpController: TcpMouseController,
    btController: BluetoothHidController
) {
    val context = LocalContext.current
    var angles by remember { mutableStateOf(OrientationAngles(0f, 0f, 0f)) }
    var connectionMode by remember { mutableStateOf(ConnectionMode.BLUETOOTH) }
    var isTcpConnected by remember { mutableStateOf(false) }
    var isBtConnected by remember { mutableStateOf(false) }
    var isMouseEnabled by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableStateOf(10f) }
    
    val isConnected = when (connectionMode) {
        ConnectionMode.USB -> isTcpConnected
        ConnectionMode.BLUETOOTH -> isBtConnected
    }
    
    // Auto-enable movement once connected
    LaunchedEffect(isConnected) {
        if (isConnected) {
            isMouseEnabled = true
        }
    }

    // Converter
    val converter = remember { OrientationToMouseConverter() }

    // Bluetooth Permissions Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(context, "Permissions granted! Click Start Discovery again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Bluetooth permissions required.", Toast.LENGTH_LONG).show()
        }
    }

    // Listen to connection states
    DisposableEffect(Unit) {
        tcpController.onConnectionStateChanged = { isTcpConnected = it }
        btController.onConnectionStateChanged = { isBtConnected = it }
        onDispose {
            tcpController.onConnectionStateChanged = null
            btController.onConnectionStateChanged = null
        }
    }

    DisposableEffect(isMouseEnabled, connectionMode) {
        if (!isMouseEnabled) return@DisposableEffect onDispose {}

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        converter.reset()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    
                    val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                    val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                    
                    angles = OrientationAngles(azimuth, pitch, roll)
                    
                    if (isMouseEnabled) {
                        val (dx, dy) = converter.convert(azimuth, pitch, roll, sensitivity)
                        if (dx != 0 || dy != 0) {
                            when (connectionMode) {
                                ConnectionMode.USB -> if (isTcpConnected) tcpController.sendMovement(dx, dy)
                                ConnectionMode.BLUETOOTH -> if (isBtConnected) btController.sendMouseCallback(dx, dy)
                            }
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Laser Pointer",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = "Status: ${if (isConnected) "CONNECTED" else "DISCONNECTED"}",
            color = if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connection Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    ConnectionToggle(currentMode = connectionMode, onModeChange = { connectionMode = it })
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                if (connectionMode == ConnectionMode.USB) {
                    Column {
                        Text(
                            "Note: Requires 'mouse_server.py' running on PC.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { if (isTcpConnected) tcpController.disconnect() else tcpController.connect() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isTcpConnected) "Disconnect USB" else "Connect USB")
                        }
                    }
                } else {
                    Column {
                        Button(
                            onClick = { checkAndRegisterBluetooth(context, permissionLauncher, btController) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isBtConnected) "Refresh Pairing" else "Start HID Discovery")
                        }
                        if (!isBtConnected) {
                            Text(
                                "Pair with Windows as 'Remote Mouse Pro'",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Controls Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { 
                            if (connectionMode == ConnectionMode.USB) tcpController.sendClick(true)
                            else btController.sendMouseCallback(0, 0, leftBtn = true)
                        },
                        modifier = Modifier.weight(1f).height(120.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("LEFT", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { 
                            if (connectionMode == ConnectionMode.USB) tcpController.sendClick(false)
                            else btController.sendMouseCallback(0, 0, rightBtn = true)
                        },
                        modifier = Modifier.weight(1f).height(120.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("RIGHT", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { 
                        if (connectionMode == ConnectionMode.USB) tcpController.sendCenterCommand()
                        else converter.recenter()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected
                ) {
                    Text("Center Pointer")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable Movement", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = isMouseEnabled, onCheckedChange = { isMouseEnabled = it })
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(text = "Sensitivity: ${String.format("%.1f", sensitivity)}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = 1f..30f
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        
        // Debug Info
        Text(
            text = "Azimuth: ${String.format("%.1f", angles.azimuth)}° | Pitch: ${String.format("%.1f", angles.pitch)}°",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ConnectionToggle(currentMode: ConnectionMode, onModeChange: (ConnectionMode) -> Unit) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            .padding(4.dp)
    ) {
        val usbSelected = currentMode == ConnectionMode.USB
        TextButton(
            onClick = { onModeChange(ConnectionMode.USB) },
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (usbSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (usbSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            ),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("USB")
        }
        TextButton(
            onClick = { onModeChange(ConnectionMode.BLUETOOTH) },
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (!usbSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (!usbSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            ),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Bluetooth")
        }
    }
}

fun checkAndRegisterBluetooth(
    context: Context,
    launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    btController: BluetoothHidController
) {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }
    
    val allGranted = permissions.all { 
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
    }
    
    if (allGranted) {
        btController.registerApp()
        requestDiscoverability(context)
    } else {
        launcher.launch(permissions)
    }
}

fun requestDiscoverability(context: Context) {
    val discoverableIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
    discoverableIntent.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
    if (context is android.app.Activity) {
        context.startActivity(discoverableIntent)
    }
}

data class OrientationAngles(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float
)
