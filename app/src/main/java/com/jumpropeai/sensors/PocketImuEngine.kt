package com.jumpropeai.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

data class PocketJumpEvent(
    val timestampMs: Long,
    val flightTimeMs: Long,
    val peakImpactG: Float,
    val isDoubleUnder: Boolean
)

/**
 * Jumpvex v4.2 — 100% Phone-Only Pocket / Waistband 100 Hz IMU Jump Counter (Zero-GC).
 *
 * Projects 3-axis linear acceleration onto the live gravity unit vector:
 *   a_vert(t) = a_lin(t) · (g(t) / ||g(t)||)
 * so phone orientation inside a pocket or running belt never degrades accuracy.
 * Detects ballistic free-fall followed by landing impulse while rejecting walking gait.
 */
class PocketImuEngine {

    private companion object {
        const val MIN_FLIGHT_MS = 115L
        const val MAX_FLIGHT_MS = 680L
        const val DOUBLE_UNDER_FLIGHT_MS = 260L
        const val MIN_LANDING_IMPULSE_MS2 = 8.5f
        const val FREEFALL_ENTER_MS2 = -3.2f
        const val REFRACTORY_MS = 190L
        const val GRAVITY_EMA_ALPHA = 0.08f
        const val VERT_EMA_ALPHA = 0.35f
    }

    private var gx = 0f
    private var gy = 9.81f
    private var gz = 0f
    private var hasGravity = false

    private var smoothedVert = 0f
    private var inFreeFall = false
    private var freeFallStartMs = -1L
    private var minFreeFallAccel = 0f
    private var lastJumpMs = -10_000L

    var totalJumps: Int = 0
        private set
    var doubleUnderCount: Int = 0
        private set
    var lastVerticalAccelMs2: Float = 0f
        private set

    fun reset() {
        gx = 0f
        gy = 9.81f
        gz = 0f
        hasGravity = false
        smoothedVert = 0f
        inFreeFall = false
        freeFallStartMs = -1L
        minFreeFallAccel = 0f
        lastJumpMs = -10_000L
        totalJumps = 0
        doubleUnderCount = 0
        lastVerticalAccelMs2 = 0f
    }

    fun updateGravity(x: Float, y: Float, z: Float) {
        if (!hasGravity) {
            gx = x
            gy = y
            gz = z
            hasGravity = true
        } else {
            gx += GRAVITY_EMA_ALPHA * (x - gx)
            gy += GRAVITY_EMA_ALPHA * (y - gy)
            gz += GRAVITY_EMA_ALPHA * (z - gz)
        }
    }

    /**
     * Feeds a linear acceleration sample (m/s^2, gravity removed) at [timestampMs].
     * Returns a [PocketJumpEvent] immediately upon confirmed ballistic landing, or null.
     */
    fun processLinearAcceleration(
        ax: Float,
        ay: Float,
        az: Float,
        timestampMs: Long
    ): PocketJumpEvent? {
        val gMag = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.1f)
        // Dot product with upward unit vector (-g / ||g||) so upward push > 0, free-fall < 0
        val rawVert = -(ax * gx + ay * gy + az * gz) / gMag
        smoothedVert += VERT_EMA_ALPHA * (rawVert - smoothedVert)
        lastVerticalAccelMs2 = smoothedVert

        if (!inFreeFall) {
            if (smoothedVert <= FREEFALL_ENTER_MS2 && (timestampMs - lastJumpMs) >= REFRACTORY_MS) {
                inFreeFall = true
                freeFallStartMs = timestampMs
                minFreeFallAccel = smoothedVert
            }
            return null
        }

        if (smoothedVert < minFreeFallAccel) {
            minFreeFallAccel = smoothedVert
        }

        val elapsedFlight = timestampMs - freeFallStartMs
        if (elapsedFlight > MAX_FLIGHT_MS) {
            inFreeFall = false
            return null
        }

        // Landing impulse confirmation (rejects gentle walking steps)
        if (smoothedVert >= MIN_LANDING_IMPULSE_MS2) {
            inFreeFall = false
            if (elapsedFlight >= MIN_FLIGHT_MS && abs(minFreeFallAccel) >= 4.0f) {
                lastJumpMs = timestampMs
                totalJumps++
                val isDu = elapsedFlight >= DOUBLE_UNDER_FLIGHT_MS
                if (isDu) doubleUnderCount++
                return PocketJumpEvent(
                    timestampMs = timestampMs,
                    flightTimeMs = elapsedFlight,
                    peakImpactG = smoothedVert / 9.81f,
                    isDoubleUnder = isDu
                )
            }
        }
        return null
    }

    /**
     * Convenience Android SensorManager controller that feeds [PocketImuEngine] at ~100 Hz.
     */
    class AndroidSensorHost(
        context: Context,
        private val engine: PocketImuEngine,
        private val onJump: (PocketJumpEvent) -> Unit
    ) : SensorEventListener {

        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        private val fallbackAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private var isRunning = false

        fun start(): Boolean {
            if (isRunning || sensorManager == null) return false
            val mainSensor = linearAccelSensor ?: fallbackAccelSensor ?: return false
            engine.reset()
            gravitySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorManager.registerListener(this, mainSensor, SensorManager.SENSOR_DELAY_FASTEST)
            isRunning = true
            return true
        }

        fun stop() {
            if (!isRunning) return
            sensorManager?.unregisterListener(this)
            isRunning = false
        }

        override fun onSensorChanged(event: SensorEvent) {
            val nowMs = event.timestamp / 1_000_000L
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> engine.updateGravity(event.values[0], event.values[1], event.values[2])
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    engine.processLinearAcceleration(
                        event.values[0],
                        event.values[1],
                        event.values[2],
                        nowMs
                    )?.let(onJump)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // Fallback when device lacks dedicated linear accel sensor
                    engine.updateGravity(event.values[0], event.values[1], event.values[2])
                    val linX = event.values[0] - engine.gx
                    val linY = event.values[1] - engine.gy
                    val linZ = event.values[2] - engine.gz
                    engine.processLinearAcceleration(linX, linY, linZ, nowMs)?.let(onJump)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
}
