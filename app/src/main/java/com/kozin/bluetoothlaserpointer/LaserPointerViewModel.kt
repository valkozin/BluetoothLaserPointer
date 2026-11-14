package com.kozin.bluetoothlaserpointer

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionMode {
    BLUETOOTH,
    SERVER // Works over both USB (adb reverse) and Wi-Fi LAN!
}

enum class PointerActivationMode {
    ALWAYS_ON,    // Moves continuously; tap central button to Freeze / Unfreeze
    HOLD_TRIGGER  // Moves only while thumb holds the central Trigger pad (like a physical laser pointer!)
}

data class AppSettingsState(
    val connectionMode: ConnectionMode = ConnectionMode.BLUETOOTH,
    val activationMode: PointerActivationMode = PointerActivationMode.ALWAYS_ON,
    val isMouseEnabled: Boolean = true,
    val isFrozen: Boolean = false,
    val isTriggerHeld: Boolean = false,
    val sensitivity: Float = 12f,
    val invertY: Boolean = false,
    val volumeKeysControlSlides: Boolean = true,
    val usePageKeysForSlides: Boolean = false
) {
    /** Whether sensor motion should actively move the cursor right now. */
    val shouldStreamMotion: Boolean
        get() = isMouseEnabled && when (activationMode) {
            PointerActivationMode.ALWAYS_ON -> !isFrozen
            PointerActivationMode.HOLD_TRIGGER -> isTriggerHeld
        }
}

class LaserPointerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val btController = BluetoothHidController(application)
    val tcpController = TcpMouseController(application)
    val motionConverter = OrientationToMouseConverter()

    private val _settings = MutableStateFlow(loadInitialSettings())
    val settings: StateFlow<AppSettingsState> = _settings.asStateFlow()

    init {
        btController.init()
    }

    private fun loadInitialSettings(): AppSettingsState {
        val modeStr = prefs.getString(KEY_CONN_MODE, ConnectionMode.BLUETOOTH.name)
        val actStr = prefs.getString(KEY_ACTIVATION_MODE, PointerActivationMode.ALWAYS_ON.name)
        return AppSettingsState(
            connectionMode = runCatching { ConnectionMode.valueOf(modeStr!!) }.getOrDefault(ConnectionMode.BLUETOOTH),
            activationMode = runCatching { PointerActivationMode.valueOf(actStr!!) }.getOrDefault(PointerActivationMode.ALWAYS_ON),
            isMouseEnabled = true,
            isFrozen = false,
            isTriggerHeld = false,
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, 12f).coerceIn(1f, 30f),
            invertY = prefs.getBoolean(KEY_INVERT_Y, false),
            volumeKeysControlSlides = prefs.getBoolean(KEY_VOLUME_SLIDES, true),
            usePageKeysForSlides = prefs.getBoolean(KEY_USE_PAGE_KEYS, false)
        )
    }

    fun isCurrentlyConnected(): Boolean {
        return when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> btController.uiState.value.phase == BtConnectionPhase.CONNECTED
            ConnectionMode.SERVER -> tcpController.uiState.value.phase == TcpConnectionPhase.CONNECTED
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        prefs.edit().putString(KEY_CONN_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(connectionMode = mode, isTriggerHeld = false)
        if (mode == ConnectionMode.BLUETOOTH) {
            btController.ensureReady(autoConnectLast = true)
        }
    }

    fun setActivationMode(mode: PointerActivationMode) {
        prefs.edit().putString(KEY_ACTIVATION_MODE, mode.name).apply()
        motionConverter.recenter()
        _settings.value = _settings.value.copy(
            activationMode = mode,
            isFrozen = false,
            isTriggerHeld = false
        )
    }

    fun setMouseEnabled(enabled: Boolean) {
        motionConverter.recenter()
        _settings.value = _settings.value.copy(isMouseEnabled = enabled, isTriggerHeld = false)
    }

    fun toggleFreeze() {
        motionConverter.recenter()
        _settings.value = _settings.value.copy(isFrozen = !_settings.value.isFrozen)
    }

    fun setTriggerHeld(held: Boolean) {
        if (_settings.value.isTriggerHeld == held) return
        motionConverter.recenter()
        _settings.value = _settings.value.copy(isTriggerHeld = held)
    }

    fun setSensitivity(value: Float) {
        val clamped = value.coerceIn(1f, 30f)
        prefs.edit().putFloat(KEY_SENSITIVITY, clamped).apply()
        _settings.value = _settings.value.copy(sensitivity = clamped)
    }

    fun setInvertY(invert: Boolean) {
        prefs.edit().putBoolean(KEY_INVERT_Y, invert).apply()
        _settings.value = _settings.value.copy(invertY = invert)
    }

    fun setVolumeKeysControlSlides(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOLUME_SLIDES, enabled).apply()
        _settings.value = _settings.value.copy(volumeKeysControlSlides = enabled)
    }

    fun setUsePageKeysForSlides(usePageKeys: Boolean) {
        prefs.edit().putBoolean(KEY_USE_PAGE_KEYS, usePageKeys).apply()
        _settings.value = _settings.value.copy(usePageKeysForSlides = usePageKeys)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Unified Input Actions (Bluetooth HID + USB/Wi-Fi Server)
    // ────────────────────────────────────────────────────────────────────────

    fun sendMotionDelta(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> btController.sendMouseDelta(dx, dy)
            ConnectionMode.SERVER -> tcpController.sendMovement(dx, dy)
        }
    }

    fun sendScroll(wheelDelta: Int) {
        if (wheelDelta == 0) return
        when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> btController.sendScroll(wheelDelta)
            ConnectionMode.SERVER -> tcpController.sendScroll(wheelDelta)
        }
    }

    fun setMouseButton(left: Boolean, pressed: Boolean) {
        when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> {
                val mask = if (left) MouseReport.BUTTON_LEFT else MouseReport.BUTTON_RIGHT
                btController.setMouseButton(mask, pressed)
            }
            ConnectionMode.SERVER -> {
                tcpController.setMouseButton(left = left, pressed = pressed)
            }
        }
    }

    fun sendSlide(next: Boolean) {
        val usePageKeys = _settings.value.usePageKeysForSlides
        when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> {
                val key = if (usePageKeys) {
                    if (next) MouseReport.KEY_PAGE_DOWN else MouseReport.KEY_PAGE_UP
                } else {
                    if (next) MouseReport.KEY_RIGHT_ARROW else MouseReport.KEY_LEFT_ARROW
                }
                btController.sendKeyTap(key)
            }
            ConnectionMode.SERVER -> {
                tcpController.sendSlide(next = next, usePageKeys = usePageKeys)
            }
        }
    }

    fun sendBlackoutScreen() {
        when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> btController.sendKeyTap(MouseReport.KEY_B)
            ConnectionMode.SERVER -> tcpController.sendPresentationAction("BLACKOUT")
        }
    }

    fun sendEscape() {
        when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> btController.sendKeyTap(MouseReport.KEY_ESCAPE)
            ConnectionMode.SERVER -> tcpController.sendPresentationAction("ESC")
        }
    }

    fun recenterPointer() {
        motionConverter.recenter()
        if (_settings.value.connectionMode == ConnectionMode.SERVER) {
            tcpController.sendCenterCommand()
        } else {
            // In Bluetooth relative HID mode, we can rapidly sweep to top-left (-127, -127) and step
            // out — OR simply reset our gyro accumulator cleanly. Wait: relative HID doesn't know
            // monitor pixel resolution on Mac vs 4K Windows, so sweeping (-127,-127) lands at random
            // spots depending on macOS/Windows pointer acceleration! Resetting gyro state cleanly
            // (and centering on Server mode where exact screen resolution is known) is predictable.
        }
    }

    override fun onCleared() {
        super.onCleared()
        tcpController.cleanUp()
        btController.cleanUp()
    }

    companion object {
        private const val PREFS_NAME = "laser_pointer_prefs"
        private const val KEY_CONN_MODE = "conn_mode"
        private const val KEY_ACTIVATION_MODE = "activation_mode"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_INVERT_Y = "invert_y"
        private const val KEY_VOLUME_SLIDES = "volume_slides"
        private const val KEY_USE_PAGE_KEYS = "use_page_keys"
    }
}
