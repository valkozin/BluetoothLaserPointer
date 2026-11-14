package com.kozin.bluetoothlaserpointer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun mouseReport_hasExactFourByteStructureAndClampsBounds() {
        val report = MouseReport.buildMouseReport(
            buttons = MouseReport.BUTTON_LEFT or MouseReport.BUTTON_RIGHT,
            dx = 250,
            dy = -200,
            wheel = -5
        )
        assertEquals(MouseReport.MOUSE_INPUT_SIZE, report.size)
        assertArrayEquals(
            byteArrayOf(0x03, 127, (-127).toByte(), (-5).toByte()),
            report
        )
    }

    @Test
    fun keyboardReport_hasExactEightByteStructure() {
        val report = MouseReport.buildKeyboardReport(
            modifiers = MouseReport.MOD_LEFT_GUI,
            keyCode = MouseReport.KEY_RIGHT_ARROW
        )
        assertEquals(MouseReport.KEYBOARD_INPUT_SIZE, report.size)
        assertEquals(MouseReport.MOD_LEFT_GUI, report[0])
        assertEquals(0.toByte(), report[1])
        assertEquals(MouseReport.KEY_RIGHT_ARROW, report[2])
    }

    @Test
    fun gyroConverter_preservesSlowSubPixelMovementWithoutDeadzoneLoss() {
        val converter = OrientationToMouseConverter()
        // Phone flat on table: gravity up = (0, 0, +9.81)
        converter.updateGravity(0f, 0f, 9.81f)

        var totalDx = 0
        var ts = 1_000_000_000L
        // Gentle slow pan right: wz = -0.05 rad/s (~2.8 deg/s) for 0.5 seconds at 100Hz
        repeat(50) {
            val (dx, _) = converter.processGyroscope(
                wx = 0f,
                wy = 0f,
                wz = -0.05f,
                timestampNs = ts,
                sensitivity = 10f
            )
            totalDx += dx
            ts += 10_000_000L // 10ms
        }

        assertTrue("Expected smooth positive rightward pixels, got $totalDx", totalDx > 10)
    }

    @Test
    fun gyroConverter_worksAt90DegreeVerticalPostureWithoutGimbalLock() {
        val converter = OrientationToMouseConverter()
        // Phone held 90 degrees upright: gravity up = (0, +9.81, 0)
        converter.updateGravity(0f, 9.81f, 0f)

        var totalDx = 0
        var totalDy = 0
        var ts = 1_000_000_000L
        // Pan right around world Y (wy = -0.4 rad/s) and tilt nose up around +X (wx = +0.4 rad/s)
        repeat(25) {
            val (dx, dy) = converter.processGyroscope(
                wx = 0.4f,
                wy = -0.4f,
                wz = 0f,
                timestampNs = ts,
                sensitivity = 12f
            )
            totalDx += dx
            totalDy += dy
            ts += 10_000_000L
        }

        assertTrue("Expected positive rightward X movement at 90° vertical, got $totalDx", totalDx > 20)
        assertTrue("Expected negative upward Y movement when tilting up, got $totalDy", totalDy < -20)
    }

    @Test
    fun orientationFallback_handles180DegreeAzimuthWrapCleanly() {
        val converter = OrientationToMouseConverter()
        converter.convertOrientationFallback(azimuthDeg = 179f, pitchDeg = 0f, sensitivity = 10f)
        val (dx, dy) = converter.convertOrientationFallback(azimuthDeg = -179f, pitchDeg = 0f, sensitivity = 10f)
        // +179 -> -179 is a +2 degree rightward turn, NOT a -358 degree jump!
        assertTrue("Expected small positive dx across +-180 seam, got $dx", dx in 1..60)
        assertEquals(0, dy)
    }
}
