package com.kozin.bluetoothlaserpointer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

@SuppressLint("MissingPermission") // Permissions are handled in UI/Activity
class BluetoothHidController(private val context: Context) {

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private val bluetoothAdapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    private val userExecutor = Executors.newSingleThreadExecutor()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered device=$pluggedDevice")
            if (registered) {
                hostDevice = pluggedDevice
                onConnectionStateChanged?.invoke(hostDevice != null)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: device=$device state=$state")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                hostDevice = device
                onConnectionStateChanged?.invoke(true)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (hostDevice == device) {
                    hostDevice = null
                    onConnectionStateChanged?.invoke(false)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            Log.d(TAG, "onGetReport: device=$device type=$type id=$id")
            // Windows sometimes asks for reports to check device health.
            // For a mouse, we can just send an empty/zero report.
            bluetoothHidDevice?.replyReport(device, type, id, byteArrayOf(0, 0, 0))
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            Log.d(TAG, "onSetReport: device=$device type=$type id=$id")
            // Acknowledge the report request
            bluetoothHidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            Log.d(TAG, "onVirtualCableUnplug: device=$device")
            hostDevice = null
            onConnectionStateChanged?.invoke(false)
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = proxy as BluetoothHidDevice
                Log.d(TAG, "HID Device Profile proxy connected")
                // Don't auto-register here, wait for UI/permissions
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = null
                onConnectionStateChanged?.invoke(false)
            }
        }
    }

    private var originalName: String? = null

    fun init() {
        try {
            bluetoothAdapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            // On API 31+, this requires BLUETOOTH_CONNECT. If called too early, it crashes.
            originalName = bluetoothAdapter?.name
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in init: ${e.message}. Will try again later.")
        } catch (e: Exception) {
            Log.e(TAG, "Error in init: ${e.message}")
        }
    }

    fun setName(newName: String) {
        try {
            if (originalName == null) originalName = bluetoothAdapter?.name
            bluetoothAdapter?.name = newName
            Log.d(TAG, "Bluetooth name set to: $newName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set name: ${e.message}")
        }
    }

    fun restoreName() {
        originalName?.let {
            try {
                bluetoothAdapter?.name = it
                Log.d(TAG, "Bluetooth name restored to: $it")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore name: ${e.message}")
            }
        }
    }

    fun registerApp() {
        if (bluetoothHidDevice == null) {
            Log.e(TAG, "Cannot register: HID profile not connected yet")
            return
        }

        // Set name to something recognizable
        setName("Remote Mouse Pro")
        
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Remote Mouse Pro",
            "Android Bluetooth HID Mouse",
            "Android HID Device",
            0x00.toByte(), // Generic subclass since it's composite
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

        val registered = bluetoothHidDevice?.registerApp(
            sdp,
            null,
            qos,
            userExecutor,
            callback
        ) ?: false
        
        Log.d(TAG, "registerApp result: $registered")
        
        // Try to connect to already paired devices if app is registered
        if (registered) {
            tryConnectPairedDevices()
        }
    }

    fun tryConnectPairedDevices() {
        val pairedDevices = bluetoothAdapter?.bondedDevices ?: return
        for (device in pairedDevices) {
            Log.d(TAG, "Checking paired device: ${device.name} (${device.address})")
            // We can't easily know if it's a "Mac", but we can try to connect
            // Many Android HID devices only connect to one host at a time.
            bluetoothHidDevice?.connect(device)
        }
    }
    
    fun unregisterApp() {
        bluetoothHidDevice?.unregisterApp()
    }

    fun sendMouseCallback(dx: Int, dy: Int, leftBtn: Boolean = false, rightBtn: Boolean = false) {
        val device = hostDevice ?: return
        val hidDevice = bluetoothHidDevice ?: return

        var buttons: Int = 0
        if (leftBtn) buttons = buttons or 0x01
        if (rightBtn) buttons = buttons or 0x02

        // Mouse Report: [Buttons, X, Y]
        val report = ByteArray(3)
        report[0] = buttons.toByte()
        report[1] = dx.coerceIn(-127, 127).toByte()
        report[2] = dy.coerceIn(-127, 127).toByte()
        
        // Use Report ID 1
        val sent = hidDevice.sendReport(device, 1, report)
        if (!sent) {
            Log.e(TAG, "Failed to send HID mouse report")
        }
    }

    fun sendKeyboardKey(keyCode: Byte) {
        val device = hostDevice ?: return
        val hidDevice = bluetoothHidDevice ?: return

        // Keyboard Report: [Modifiers, Reserved, Key1, Key2, Key3, Key4, Key5, Key6]
        val pressReport = ByteArray(8)
        pressReport[2] = keyCode

        val releaseReport = ByteArray(8)

        // Send Press (ID 2)
        hidDevice.sendReport(device, 2, pressReport)
        
        // Short delay for the host to register the key press
        // In a real app, you might want to handle this better, but for single keys it usually works
        userExecutor.execute {
            try { Thread.sleep(20) } catch (e: Exception) {}
            hidDevice.sendReport(device, 2, releaseReport)
        }
    }
    
    fun cleanUp() {
        bluetoothHidDevice?.unregisterApp()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHidDevice)
    }

    companion object {
        private const val TAG = "BluetoothHidController"
    }
}
