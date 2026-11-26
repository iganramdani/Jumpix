package com.jumpropeai

import com.jumpropeai.ai.BattleMode
import com.jumpropeai.ai.KineticMove
import com.jumpropeai.ai.KineticTrickCoach
import com.jumpropeai.ai.OpticalRopeTracker
import com.jumpropeai.ai.PlyometricAnalyzer
import com.jumpropeai.ai.SplitBattleArenaEngine
import com.jumpropeai.audio.DoubleClapDetector
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.sensors.OpticalHeartRateScanner
import com.jumpropeai.sensors.PocketImuEngine
import com.jumpropeai.system.ThermalGovernor
import com.jumpropeai.system.ThermalTier
import com.jumpropeai.workout.GhostPacerEngine
import com.jumpropeai.workout.GhostProfile
import com.jumpropeai.workout.SkillAcademyEngine
import com.jumpropeai.workout.WorkoutModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ProV6EngineTest {

    @Test
    fun pocketImuDetectsOrientationInvariantJumpAndDoubleUnder() {
        val engine = PocketImuEngine()
        // Tilted phone in pocket: gravity along +X instead of +Y!
        engine.updateGravity(9.81f, 0f, 0f)

        var detectedRegular = false
        // Free-fall for 180 ms along gravity axis (+X -> rawVert = -ax), then sharp landing impulse (-X -> rawVert = +ax)
        for (t in 0L..180L step 10L) {
            engine.processLinearAcceleration(ax = 7.5f, ay = 0f, az = 0f, timestampMs = t)
        }
        for (t in 190L..230L step 10L) {
            val ev = engine.processLinearAcceleration(ax = -18.0f, ay = 0f, az = 0f, timestampMs = t)
            if (ev != null) {
                detectedRegular = !ev.isDoubleUnder
            }
        }
        assertTrue("Expected orientation-invariant Pocket IMU regular jump", detectedRegular)

        // Longer 310 ms free-fall should classify as Double Under
        var detectedDu = false
        for (t in 600L..910L step 10L) {
            engine.processLinearAcceleration(ax = 8.5f, ay = 0f, az = 0f, timestampMs = t)
        }
        for (t in 920L..970L step 10L) {
            val ev = engine.processLinearAcceleration(ax = -20.0f, ay = 0f, az = 0f, timestampMs = t)
            if (ev != null && ev.isDoubleUnder) {
                detectedDu = true
            }
        }
        assertTrue("Expected Pocket IMU Double Under on 310ms flight", detectedDu)
        assertEquals(2, engine.totalJumps)
        assertEquals(1, engine.doubleUnderCount)
    }

    @Test
    fun opticalHeartRateScannerLocks120BpmPulseAndComputesHrr60() {
        val scanner = OpticalHeartRateScanner()
        var lastSnap = scanner.processOpticalSample(50f, 60f, 0L)
        assertFalse("Should reject when no finger covers lens", lastSnap.isFingerDetected)

        // Simulate 120 BPM (2.0 Hz) pulse for 10 seconds at 30 FPS (33ms step)
        for (i in 0 until 300) {
            val tMs = i * 33L
            val pulse = sin(2.0 * PI * 2.0 * (tMs / 1000.0)).toFloat()
            val red = 210f + 12f * pulse
            val green = 40f + 1.5f * pulse
            lastSnap = scanner.processOpticalSample(red, green, tMs)
        }
        assertTrue("Finger should be locked", lastSnap.isFingerDetected)
        assertTrue("Expected BPM near 120, got ${lastSnap.currentBpm}", lastSnap.currentBpm in 110..130)
        assertTrue("Expected peakBpm >= 110", lastSnap.peakBpm >= 110)
    }

    @Test
    fun doubleClapDetectorTriggersOnlyOnPairedHighSnapTransients() {
        val detector = DoubleClapDetector()
        // Ambient floor
        for (t in 0L..500L step 10L) {
            assertFalse(detector.processFlux(FluxSample(timestampMs = t, mid = 0.2f, high1 = 0.2f, high2 = 0.2f, wideHigh = 0.2f)))
        }
        // First clap at 600ms
        assertFalse(detector.processFlux(FluxSample(timestampMs = 600L, mid = 1.2f, high1 = 8.0f, high2 = 8.0f, wideHigh = 8.0f)))
        // Second clap at 900ms (gap = 300ms inside 180..480ms window)
        assertTrue(detector.processFlux(FluxSample(timestampMs = 900L, mid = 1.1f, high1 = 8.5f, high2 = 8.5f, wideHigh = 8.5f)))
    }

    @Test
    fun plyometricAnalyzerComputesBallisticHeightGctAndRsi() {
        val plyo = PlyometricAnalyzer()
        // 3 jumps: flight = 200ms -> h = 0.125 * 9.80665 * 0.04 * 100 = 4.90 cm; GCT = 150ms -> RSI = 0.049 / 0.15 = 0.327
        var t = 0L
        repeat(4) {
            plyo.processFrame(t, isAirborne = false, leftAnkleY = 0.8f, rightAnkleY = 0.8f)
            t += 150L
            plyo.processFrame(t, isAirborne = true, leftAnkleY = 0.72f, rightAnkleY = 0.72f)
            t += 200L
            plyo.processFrame(t, isAirborne = false, leftAnkleY = 0.8f, rightAnkleY = 0.8f)
        }
        val snap = plyo.snapshot()
        assertTrue("Expected height ~4.9cm, got ${snap.avgJumpHeightCm}", snap.avgJumpHeightCm in 4.5f..5.3f)
        assertEquals(150, snap.avgGctMs)
        assertTrue("Expected positive RSI, got ${snap.reactiveStrengthIndex}", snap.reactiveStrengthIndex > 0.25f)
        assertEquals(100, snap.lrSymmetryPct)
    }

    @Test
    fun opticalRopeTrackerDetectsSubToeHorizontalWhipStreak() {
        val tracker = OpticalRopeTracker()
        val roiSize = 80 * 24
        val staticBg = ByteArray(roiSize) { 80 }
        tracker.processSubToeLumaRoi(staticBg, 0L, isAirborne = false)

        // Draw bright horizontal rope streak across rows 10..12 while airborne
        val whipFrame = ByteArray(roiSize) { 80 }
        for (row in 10..12) {
            for (col in 0 until 80) {
                whipFrame[row * 80 + col] = 220.toByte()
            }
        }
        val sig = tracker.processSubToeLumaRoi(whipFrame, 200L, isAirborne = true)
        assertTrue("Expected optical rope pass confirmation", sig.ropePassConfirmed)
        assertEquals(1, sig.verifiedPassCount)
    }

    @Test
    fun kineticTrickCoachSupports20MovesAndTripAutopsy() {
        assertEquals("Expected 20 calibrated v15 moves", 20, KineticMove.entries.size)
        val coach = KineticTrickCoach()
        val autopsy = coach.diagnoseTrip(nowMs = 5_000L)
        assertNotNull(autopsy.headline)
        assertTrue(autopsy.coachingFix.isNotBlank())
    }

    @Test
    fun ghostPacerAndSplitBattleArenaWorkAccurately() {
        val ghost = GhostPacerEngine()
        ghost.configure(GhostProfile.BOXER_190)
        // After 30 seconds at 190 RPM, ghost expects 95 jumps; user has 101 -> +6 AHEAD
        val snap = ghost.evaluate(elapsedMs = 30_000L, actualJumps = 101)
        assertTrue(snap.isActive)
        assertEquals(95, snap.ghostJumpsExpected)
        assertEquals(6, snap.deltaJumps)
        assertTrue(snap.statusText.contains("+6 AHEAD"))

        val arena = SplitBattleArenaEngine()
        arena.start(BattleMode.SPEED_DUEL_30S)
        assertEquals(1, arena.nextInterleavedPlayerSlot())
        assertEquals(2, arena.nextInterleavedPlayerSlot())
        assertEquals(1, arena.nextInterleavedPlayerSlot())
    }

    @Test
    fun skillAcademyAndThermalGovernorEvaluateCorrectly() {
        val sampleSession = WorkoutSession(
            id = "s1",
            timestampMillis = 1_700_000_000_000L,
            modeLabel = "Free",
            modeType = WorkoutModeType.FREE,
            jumpCount = 150,
            durationMs = 60_000L,
            averageRpm = 150,
            peakRpm = 175,
            estimatedCalories = 18.0,
            bestStreak = 120,
            trips = 0,
            formScore = 88,
            moveBreakdown = mapOf("Boxer Step" to 25)
        )
        val tree = SkillAcademyEngine.evaluateSkillTree(listOf(sampleSession))
        assertEquals(5, tree.size)
        assertTrue("Tier 1 should unlock on 120 streak", tree[0].unlocked)
        assertTrue("Tier 2 should unlock on 25 clean Boxer Steps", tree[1].unlocked)

        val plan = SkillAcademyEngine.buildAdaptivePlan(listOf(sampleSession))
        assertEquals(6, plan.size)

        assertEquals(ThermalTier.COOL, ThermalGovernor.mapAndroidThermalStatus(0))
        assertEquals(ThermalTier.WARM, ThermalGovernor.mapAndroidThermalStatus(2))
        assertEquals(ThermalTier.HOT, ThermalGovernor.mapAndroidThermalStatus(3))
    }
}
