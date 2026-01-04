package com.example.audiorecord

import kotlin.math.abs

class OrientationToMouseConverter(
    private val smoothing: Float = 0.2f
) {
    private var initialAzimuth: Float? = null
    private var initialPitch: Float? = null
    
    // Smoothed values
    private var smoothedDx = 0f
    private var smoothedDy = 0f

    fun reset() {
        initialAzimuth = null
        initialPitch = null
        smoothedDx = 0f
        smoothedDy = 0f
    }

    fun convert(azimuth: Float, pitch: Float, roll: Float, sensitivity: Float): Pair<Int, Int> {
        if (initialAzimuth == null || initialPitch == null) {
            initialAzimuth = azimuth
            initialPitch = pitch
            return Pair(0, 0)
        }

        val prevAzimuth = initialAzimuth ?: azimuth
        val prevPitch = initialPitch ?: pitch
        
        var deltaAzimuth = azimuth - prevAzimuth
        var deltaPitch = pitch - prevPitch
        
        // Fix wrapping
        if (deltaAzimuth > 180) deltaAzimuth -= 360
        if (deltaAzimuth < -180) deltaAzimuth += 360

        // Update "previous" status
        initialAzimuth = azimuth
        initialPitch = pitch

        // Fixed axes: original was (deltaAzimuth * -sensitivity)
        // If it was reversed, we change the sign.
        val rawDx = (deltaAzimuth * sensitivity)
        val rawDy = (deltaPitch * sensitivity)

        // Apply EMA Smoothing
        smoothedDx = (smoothing * rawDx) + (1 - smoothing) * smoothedDx
        smoothedDy = (smoothing * rawDy) + (1 - smoothing) * smoothedDy

        // Apply deadzone after smoothing
        val finalDx = if (abs(smoothedDx) < 0.5f) 0 else smoothedDx.toInt()
        val finalDy = if (abs(smoothedDy) < 0.5f) 0 else smoothedDy.toInt()

        return Pair(finalDx, finalDy)
    }
}
