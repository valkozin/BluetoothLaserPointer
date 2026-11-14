package com.kozin.bluetoothlaserpointer

/**
 * Standard USB/Bluetooth HID 1.11 Composite Report Descriptor for Windows & macOS:
 * - Report ID 1: 4-byte Relative Mouse [buttons, dx, dy, wheel]
 * - Report ID 2: 8-byte Standard Keyboard Input [modifiers, reserved, key0..key5]
 *                + 1-byte LED Status Output [leds]
 */
object MouseReport {

    const val ID_MOUSE: Int = 1
    const val ID_KEYBOARD: Int = 2

    const val MOUSE_INPUT_SIZE: Int = 4
    const val KEYBOARD_INPUT_SIZE: Int = 8
    const val KEYBOARD_OUTPUT_SIZE: Int = 1

    // Mouse button bitmasks
    const val BUTTON_NONE: Int = 0x00
    const val BUTTON_LEFT: Int = 0x01
    const val BUTTON_RIGHT: Int = 0x02
    const val BUTTON_MIDDLE: Int = 0x04

    // Keyboard modifier bitmasks (Usage Page 0x07, 0xE0..0xE7)
    const val MOD_NONE: Byte = 0x00
    const val MOD_LEFT_CTRL: Byte = 0x01
    const val MOD_LEFT_SHIFT: Byte = 0x02
    const val MOD_LEFT_ALT: Byte = 0x04
    const val MOD_LEFT_GUI: Byte = 0x08 // Command on macOS, Windows key on Windows

    // Keyboard HID Usage IDs (Usage Page 0x07)
    const val KEY_B: Byte = 0x05          // Blackout screen (PowerPoint / Keynote / Google Slides)
    const val KEY_L: Byte = 0x0F          // Laser pointer toggle (Google Slides 'L', PPT Ctrl/Cmd+L)
    const val KEY_W: Byte = 0x1A          // Whiteout screen
    const val KEY_ENTER: Byte = 0x28
    const val KEY_ESCAPE: Byte = 0x29     // Exit slideshow
    const val KEY_SPACE: Byte = 0x2C
    const val KEY_F5: Byte = 0x3E         // Start PowerPoint slideshow (Windows)
    const val KEY_PAGE_UP: Byte = 0x4B
    const val KEY_PAGE_DOWN: Byte = 0x4E
    const val KEY_RIGHT_ARROW: Byte = 0x4F
    const val KEY_LEFT_ARROW: Byte = 0x50
    const val KEY_DOWN_ARROW: Byte = 0x51
    const val KEY_UP_ARROW: Byte = 0x52

    val COMPOSITE_REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        // ── Mouse Collection (Report ID 1: 4 bytes Input) ────────────────────
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), 0x01.toByte(), //   Report ID (1)
        0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
        // Buttons 1..3 (Left, Right, Middle) -> 3 bits
        0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
        0x19.toByte(), 0x01.toByte(), //     Usage Minimum (Button 1)
        0x29.toByte(), 0x03.toByte(), //     Usage Maximum (Button 3)
        0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
        0x95.toByte(), 0x03.toByte(), //     Report Count (3)
        0x75.toByte(), 0x01.toByte(), //     Report Size (1)
        0x81.toByte(), 0x02.toByte(), //     Input (Data, Variable, Absolute)
        // 5-bit constant padding -> completes Byte 0
        0x95.toByte(), 0x01.toByte(), //     Report Count (1)
        0x75.toByte(), 0x05.toByte(), //     Report Size (5)
        0x81.toByte(), 0x03.toByte(), //     Input (Constant, Variable, Absolute)
        // X, Y, Wheel relative axes -> Bytes 1, 2, 3
        0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), //     Usage (X)
        0x09.toByte(), 0x31.toByte(), //     Usage (Y)
        0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), //     Report Size (8)
        0x95.toByte(), 0x03.toByte(), //     Report Count (3)
        0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
        0xC0.toByte(),                //   End Collection (Physical)
        0xC0.toByte(),                // End Collection (Application)

        // ── Keyboard Collection (Report ID 2: 8 bytes Input, 1 byte Output) ──
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), 0x02.toByte(), //   Report ID (2)
        // Modifier byte (LCtrl..RGUI) -> Byte 0 Input
        0x05.toByte(), 0x07.toByte(), //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xE0.toByte(), //   Usage Minimum (Left Control)
        0x29.toByte(), 0xE7.toByte(), //   Usage Maximum (Right GUI)
        0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), //   Report Size (1)
        0x95.toByte(), 0x08.toByte(), //   Report Count (8)
        0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute)
        // Reserved byte -> Byte 1 Input
        0x95.toByte(), 0x01.toByte(), //   Report Count (1)
        0x75.toByte(), 0x08.toByte(), //   Report Size (8)
        0x81.toByte(), 0x01.toByte(), //   Input (Constant, Array, Absolute)
        // LED status report (Num/Caps/Scroll/Compose/Kana + 3-bit pad) -> 1 byte Output
        0x95.toByte(), 0x05.toByte(), //   Report Count (5)
        0x75.toByte(), 0x01.toByte(), //   Report Size (1)
        0x05.toByte(), 0x08.toByte(), //   Usage Page (LEDs)
        0x19.toByte(), 0x01.toByte(), //   Usage Minimum (Num Lock)
        0x29.toByte(), 0x05.toByte(), //   Usage Maximum (Kana)
        0x91.toByte(), 0x02.toByte(), //   Output (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(), //   Report Count (1)
        0x75.toByte(), 0x03.toByte(), //   Report Size (3)
        0x91.toByte(), 0x01.toByte(), //   Output (Constant, Array, Absolute)
        // 6-key rollover array -> Bytes 2..7 Input
        0x95.toByte(), 0x06.toByte(), //   Report Count (6)
        0x75.toByte(), 0x08.toByte(), //   Report Size (8)
        0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(), //   Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(), //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00.toByte(), //   Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(), //   Usage Maximum (Keyboard Application)
        0x81.toByte(), 0x00.toByte(), //   Input (Data, Array, Absolute)
        0xC0.toByte()                 // End Collection (Application)
    )

    fun buildMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray {
        return byteArrayOf(
            (buttons and 0x07).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )
    }

    fun buildKeyboardReport(modifiers: Byte = MOD_NONE, keyCode: Byte = 0): ByteArray {
        return ByteArray(KEYBOARD_INPUT_SIZE).also {
            it[0] = modifiers
            it[2] = keyCode
        }
    }
}
