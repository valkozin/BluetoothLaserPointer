package com.kozin.bluetoothlaserpointer

object MouseReport {
    // Composite HID Descriptor: Mouse (ID 1) + Keyboard (ID 2)
    val COMPOSITE_REPORT_DESCRIPTOR = byteArrayOf(
        // Mouse Report Descriptor (ID 1)
        0x05.toByte(), 0x01.toByte(),         // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(),         // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(),         // Collection (Application)
        0x85.toByte(), 0x01.toByte(),         //   REPORT_ID (1)
        0x09.toByte(), 0x01.toByte(),         //   Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(),         //   Collection (Physical)
        0x05.toByte(), 0x09.toByte(),         //     Usage Page (Buttons)
        0x19.toByte(), 0x01.toByte(),         //     Usage Minimum (1)
        0x29.toByte(), 0x03.toByte(),         //     Usage Maximum (3)
        0x15.toByte(), 0x00.toByte(),         //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),         //     Logical Maximum (1)
        0x95.toByte(), 0x03.toByte(),         //     Report Count (3)
        0x75.toByte(), 0x01.toByte(),         //     Report Size (1)
        0x81.toByte(), 0x02.toByte(),         //     Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(),         //     Report Count (1)
        0x75.toByte(), 0x05.toByte(),         //     Report Size (5)
        0x81.toByte(), 0x03.toByte(),         //     Input (Constant) for padding
        0x05.toByte(), 0x01.toByte(),         //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),         //     Usage (X)
        0x09.toByte(), 0x31.toByte(),         //     Usage (Y)
        0x15.toByte(), 0x81.toByte(),         //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(),         //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),         //     Report Size (8)
        0x95.toByte(), 0x02.toByte(),         //     Report Count (2)
        0x81.toByte(), 0x06.toByte(),         //     Input (Data, Variable, Relative)
        0xC0.toByte(),                        //   End Collection
        0xC0.toByte(),                        // End Collection

        // Keyboard Report Descriptor (ID 2)
        0x05.toByte(), 0x01.toByte(),         // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),         // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),         // Collection (Application)
        0x85.toByte(), 0x02.toByte(),         //   REPORT_ID (2)
        0x05.toByte(), 0x07.toByte(),         //   Usage Page (Keyboard)
        0x19.toByte(), 0xE0.toByte(),         //   Usage Minimum (Keyboard LeftControl)
        0x29.toByte(), 0xE7.toByte(),         //   Usage Maximum (Keyboard Right GUI)
        0x15.toByte(), 0x00.toByte(),         //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),         //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),         //   Report Size (1)
        0x95.toByte(), 0x08.toByte(),         //   Report Count (8)
        0x81.toByte(), 0x02.toByte(),         //   Input (Data, Variable, Absolute), Modifier keys
        0x95.toByte(), 0x01.toByte(),         //   Report Count (1)
        0x75.toByte(), 0x08.toByte(),         //   Report Size (8)
        0x81.toByte(), 0x03.toByte(),         //   Input (Constant), Reserved byte
        0x95.toByte(), 0x06.toByte(),         //   Report Count (6)
        0x75.toByte(), 0x08.toByte(),         //   Report Size (8)
        0x15.toByte(), 0x00.toByte(),         //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(),         //   Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(),         //   Usage Page (Keyboard)
        0x19.toByte(), 0x00.toByte(),         //   Usage Minimum (Reserved)
        0x29.toByte(), 0x65.toByte(),         //   Usage Maximum (Keyboard Application)
        0x81.toByte(), 0x00.toByte(),         //   Input (Data,Array,Absolute), Key codes
        0xC0.toByte()                         // End Collection
    )

    // Key codes
    const val KEY_LEFT_ARROW = 0x50.toByte()
    const val KEY_RIGHT_ARROW = 0x4F.toByte()
    const val KEY_PAGE_UP = 0x4B.toByte()
    const val KEY_PAGE_DOWN = 0x4E.toByte()
}
