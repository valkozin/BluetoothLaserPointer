package com.kozin.bluetoothlaserpointer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Gimbal-lock-free, sub-pixel accurate air-mouse motion engine.
 *
 * Primary mode:
 * - Integrates 3D gyroscope angular velocity (rad/s) projected onto the real-world
 *   vertical (gravity) and horizontal-right axes via Gram-Schmidt orthogonalization.
 * - Immune to magnetometer interference and gimbal lock at any phone tilt (0°..110°).
 *
 * Fallback mode:
 * - Supports orientation angle deltas (for devices without a hardware gyroscope).
 *
 * Signal processing:
 * - Soft-knee resting hand tremor deadzone (zero step discontinuity)
 * - dt-adaptive low-pass smoothing (eliminates jitter without coasting lag)
 * - Gentle pointer acceleration curve (1:1 slow precision + effortless wide sweeps)
 * - Lossless sub-pixel remainder accumulation (smooth diagonal & slow movement)
 */
class OrientationToMouseConverter(
    private val tremorDeadzoneRadPerSec: Float = 0.015f, // ~0.85 deg/s natural hand tremor floor
    private val smoothingTimeConstantSec: Float = 0.014f // ~14ms low-pass filter
) {
    // Unit vector pointing straight UP (toward ceiling) in device coordinates.
    // Defaults to a comfortable ~45° handheld posture until first gravity sample arrives.
    private var upX = 0f
    private var upY = 0.70710677f
    private var upZ = 0.70710677f

    private var lastGyroTimestampNs: Long = 0L

    // Filtered angular velocities (rad/s)
    private var filteredYawRate = 0f
    private var filteredPitchRate = 0f

    // Sub-pixel fractional accumulators (never lose slow or diagonal motion)
    private var remainderX = 0f
    private var remainderY = 0f

    // Fallback orientation state (degrees)
    private var prevAzimuthDeg: Float? = null
    private var prevPitchDeg: Float? = null

    fun reset() {
        lastGyroTimestampNs = 0L
        filteredYawRate = 0f
        filteredPitchRate = 0f
        remainderX = 0f
        remainderY = 0f
        prevAzimuthDeg = null
        prevPitchDeg = null
    }

    fun recenter() {
        filteredYawRate = 0f
        filteredPitchRate = 0f
        remainderX = 0f
        remainderY = 0f
        prevAzimuthDeg = null
        prevPitchDeg = null
    }

    /**
     * Updates the gravity reaction vector from TYPE_GRAVITY or low-passed TYPE_ACCELEROMETER.
     */
    fun updateGravity(gx: Float, gy: Float, gz: Float, isRawAccelerometer: Boolean = false) {
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 1.0f) return // Ignore free-fall / invalid zero readings

        val nx = gx / norm
        val ny = gy / norm
        val nz = gz / norm

        if (isRawAccelerometer) {
            // Smooth linear acceleration transients out of raw accelerometer
            val alpha = 0.08f
            val bx = upX + alpha * (nx - upX)
            val by = upY + alpha * (ny - upY)
            val bz = upZ + alpha * (nz - upZ)
            val bNorm = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-4f)
            upX = bx / bNorm
            upY = by / bNorm
            upZ = bz / bNorm
        } else {
            upX = nx
            upY = ny
            upZ = nz
        }
    }

    /**
     * Processes a 3D gyroscope sample (wx, wy, wz in rad/s) at [timestampNs]
     * and returns integer (dx, dy) pixels for this step while preserving sub-pixel remainders.
     */
    fun processGyroscope(
        wx: Float,
        wy: Float,
        wz: Float,
        timestampNs: Long,
        sensitivity: Float,
        invertY: Boolean = false
    ): Pair<Int, Int> {
        val prevTs = lastGyroTimestampNs
        lastGyroTimestampNs = timestampNs
        if (prevTs == 0L) return Pair(0, 0)

        val dt = ((timestampNs - prevTs) * 1e-9f)
        // Ignore negative or stale timestamps (e.g. sensor resume pause > 100ms)
        if (dt <= 0f || dt > 0.1f) {
            filteredYawRate = 0f
            filteredPitchRate = 0f
            return Pair(0, 0)
        }

        // 1. World-horizontal panning rate around ceiling vector up = (upX, upY, upZ).
        //    Turning phone right produces negative dot(w, up) -> positive rawYawRate.
        val rawYawRate = -(wx * upX + wy * upY + wz * upZ)

        // 2. World-horizontal right axis via Gram-Schmidt on phone +X = (1, 0, 0) against up.
        var rx = 1f - upX * upX
        var ry = -upX * upY
        var rz = -upX * upZ
        val rNorm = sqrt(rx * rx + ry * ry + rz * rz)
        if (rNorm > 0.15f) {
            rx /= rNorm
            ry /= rNorm
            rz /= rNorm
        } else {
            rx = 1f
            ry = 0f
            rz = 0f
        }
        // Tilting nose UP gives +wx -> negative rawPitchRate (screen Y up = negative dy).
        val pitchSign = if (invertY) 1f else -1f
        val rawPitchRate = pitchSign * (wx * rx + wy * ry + wz * rz)

        // 3. Soft-knee tremor deadzone (radial in 2D so diagonals never snap to X/Y axes!)
        val rawSpeed = hypot(rawYawRate, rawPitchRate)
        val cleanYaw: Float
        val cleanPitch: Float
        if (rawSpeed <= tremorDeadzoneRadPerSec) {
            cleanYaw = 0f
            cleanPitch = 0f
        } else {
            val scale = (rawSpeed - tremorDeadzoneRadPerSec) / rawSpeed
            cleanYaw = rawYawRate * scale
            cleanPitch = rawPitchRate * scale
        }

        // 4. dt-invariant exponential smoothing
        val alpha = if (smoothingTimeConstantSec <= 0f) {
            1f
        } else {
            (1f - exp(-dt / smoothingTimeConstantSec)).coerceIn(0.15f, 1f)
        }
        filteredYawRate += alpha * (cleanYaw - filteredYawRate)
        filteredPitchRate += alpha * (cleanPitch - filteredPitchRate)

        // Snap residual filter tail cleanly to zero when hand stops
        if (cleanYaw == 0f && abs(filteredYawRate) < 0.003f) filteredYawRate = 0f
        if (cleanPitch == 0f && abs(filteredPitchRate) < 0.003f) filteredPitchRate = 0f

        // 5. Ergonomic pointer acceleration curve:
        //    1:1 fine control at low speed, smooth boost on deliberate sweeps.
        val filteredSpeed = hypot(filteredYawRate, filteredPitchRate)
        val accelBoost = 1f + 0.45f * filteredSpeed.coerceAtMost(6f).pow(0.65f)

        // Convert rad/s * dt -> degrees * pixelsPerDegree
        val radToDeg = 57.29578f
        val pixelsPerDegree = sensitivity * 2.4f * accelBoost

        val deltaPxX = filteredYawRate * dt * radToDeg * pixelsPerDegree
        val deltaPxY = filteredPitchRate * dt * radToDeg * pixelsPerDegree

        return accumulateSubPixel(deltaPxX, deltaPxY)
    }

    /**
     * Fallback converter using orientation angles in degrees (for devices without a gyroscope).
     */
    fun convertOrientationFallback(
        azimuthDeg: Float,
        pitchDeg: Float,
        sensitivity: Float,
        invertY: Boolean = false
    ): Pair<Int, Int> {
        val prevAz = prevAzimuthDeg
        val prevPi = prevPitchDeg
        prevAzimuthDeg = azimuthDeg
        prevPitchDeg = pitchDeg

        if (prevAz == null || prevPi == null) {
            return Pair(0, 0)
        }

        var dAz = azimuthDeg - prevAz
        var dPi = pitchDeg - prevPi

        if (dAz > 180f) dAz -= 360f
        if (dAz < -180f) dAz += 360f

        if (invertY) dPi = -dPi

        val mag = hypot(dAz, dPi)
        val deadzoneDeg = 0.03f
        if (mag < deadzoneDeg) return Pair(0, 0)
        val scale = (mag - deadzoneDeg) / mag

        val pxPerDeg = sensitivity * 2.4f
        return accumulateSubPixel(
            deltaX = dAz * scale * pxPerDeg,
            deltaY = dPi * scale * pxPerDeg
        )
    }

    private fun accumulateSubPixel(deltaX: Float, deltaY: Float): Pair<Int, Int> {
        val totalX = remainderX + deltaX
        val totalY = remainderY + deltaY

        val outX = totalX.toInt().coerceIn(-127, 127)
        val outY = totalY.toInt().coerceIn(-127, 127)

        remainderX = (totalX - outX).coerceIn(-64f, 64f)
        remainderY = (totalY - outY).coerceIn(-64f, 64f)

        // Decay tiny stale sub-pixel remainder if reversing direction
        if (deltaX != 0f && sign(deltaX) != sign(remainderX) && abs(remainderX) < 0.2f) {
            remainderX = 0f
        }
        if (deltaY != 0f && sign(deltaY) != sign(remainderY) && abs(remainderY) < 0.2f) {
            remainderY = 0f
        }

        return Pair(outX, outY)
    }
}
