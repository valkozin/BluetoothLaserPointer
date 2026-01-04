package com.example.audiorecord

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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.audiorecord.ui.theme.AudioRecordTheme

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
            AudioRecordTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OrientationScreen(
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
fun OrientationScreen(
    modifier: Modifier = Modifier,
    tcpController: TcpMouseController,
    btController: BluetoothHidController
) {
    val context = LocalContext.current
    var angles by remember { mutableStateOf(OrientationAngles(0f, 0f, 0f)) }
    var connectionMode by remember { mutableStateOf(ConnectionMode.USB) }
    var isTcpConnected by remember { mutableStateOf(false) }
    var isBtConnected by remember { mutableStateOf(false) }
    var isMouseEnabled by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableStateOf(8f) }
    
    val isConnected = when (connectionMode) {
        ConnectionMode.USB -> isTcpConnected
        ConnectionMode.BLUETOOTH -> isBtConnected
    }
    
    // Converter
    val converter = remember { OrientationToMouseConverter() }

    // Bluetooth Permissions Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(context, "Permissions granted! Click button again to start.", Toast.LENGTH_SHORT).show()
            // The user clicking again is safer than trying to auto-trigger here 
            // because connectionMode might have changed or sensors moved.
        } else {
            Toast.makeText(context, "Bluetooth permissions required to use this feature.", Toast.LENGTH_LONG).show()
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

    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "Remote Mouse Pro", fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp))
        
        // Mode Selector
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mode: ", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                RadioButton(selected = connectionMode == ConnectionMode.USB, onClick = { connectionMode = ConnectionMode.USB })
                Text("USB")
                Spacer(modifier = Modifier.width(8.dp))
                RadioButton(selected = connectionMode == ConnectionMode.BLUETOOTH, onClick = { connectionMode = ConnectionMode.BLUETOOTH })
                Text("Bluetooth")
            }
        }

        Text(
            text = "Active Mode: ${connectionMode.name}", 
            color = MaterialTheme.colorScheme.primary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
        )
        Text(text = "Status: ${if (isConnected) "CONNECTED" else "DISCONNECTED"}", fontSize = 18.sp)
        
        Spacer(modifier = Modifier.height(8.dp))

        when (connectionMode) {
            ConnectionMode.USB -> {
                if (!isTcpConnected) {
                    Button(onClick = { tcpController.connect() }) { Text("Connect to PC (USB)") }
                } else {
                    Button(onClick = { tcpController.disconnect() }) { Text("Disconnect USB") }
                }
            }
            ConnectionMode.BLUETOOTH -> {
                var showTroubleshootDialog by remember { mutableStateOf(false) }
                Column {
                    Button(onClick = { checkAndRegisterBluetooth(context, permissionLauncher, btController) }) {
                        Text(if (isBtConnected) "Re-Register HID" else "Start HID Discovery")
                    }
                    if (!isBtConnected) {
                        Text("BT Status: Ready for Pairing", color = MaterialTheme.colorScheme.secondary)
                        Text("Look for device: 'Remote Mouse Pro'", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    
                    TextButton(onClick = { showTroubleshootDialog = true }) { Text("Pairing issues?") }
                    if (showTroubleshootDialog) {
                        AlertDialog(
                            onDismissRequest = { showTroubleshootDialog = false },
                            title = { Text("Windows Pairing Help") },
                            text = {
                                Text("1. If already paired but disconnects, 'REMOVE DEVICE' in Windows Settings.\n" +
                                     "2. Restart this app and select Bluetooth mode.\n" +
                                     "3. Click 'Start Bluetooth Discovery' first.\n" +
                                     "4. THEN open Windows 'Add device' menu.\n" +
                                     "5. Pair fresh as 'Remote Mouse Pro'.")
                            },
                            confirmButton = { Button(onClick = { showTroubleshootDialog = false }) { Text("OK") } }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Large Click Buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(
                onClick = { 
                    if (connectionMode == ConnectionMode.USB) tcpController.sendClick(true)
                    else btController.sendMouseCallback(0, 0, leftBtn = true)
                },
                modifier = Modifier.weight(1f).height(100.dp).padding(4.dp)
            ) {
                Text("LEFT CLICK")
            }
            Button(
                onClick = { 
                    if (connectionMode == ConnectionMode.USB) tcpController.sendClick(false)
                    else btController.sendMouseCallback(0, 0, rightBtn = true)
                },
                modifier = Modifier.weight(1f).height(100.dp).padding(4.dp)
            ) {
                Text("RIGHT CLICK")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { 
            if (connectionMode == ConnectionMode.USB) tcpController.sendCenterCommand()
        }, enabled = connectionMode == ConnectionMode.USB) {
            Text("Center Pointer (USB Only)")
        }
        
        Spacer(modifier = Modifier.height(20.dp))

        Text(text = "Sensitivity: ${String.format("%.1f", sensitivity)}")
        Slider(
            value = sensitivity,
            onValueChange = { sensitivity = it },
            valueRange = 1f..30f
        )

        Spacer(modifier = Modifier.height(10.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Enable Movement")
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = isMouseEnabled, onCheckedChange = { isMouseEnabled = it })
        }
        
        Spacer(modifier = Modifier.height(20.dp))

        Text(text = "Orientation Info:", fontSize = 14.sp)
        Text(text = "Azimuth: ${String.format("%.2f", angles.azimuth)}° | Pitch: ${String.format("%.2f", angles.pitch)}°")
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
    // In a Composable/Activity, we should ideally use a launcher.
    // But for a simple trigger in a utility function, we can use startActivity if context is activity.
    if (context is android.app.Activity) {
        context.startActivity(discoverableIntent)
    }
}

data class OrientationAngles(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float
)