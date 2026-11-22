package com.jumpropeai.ai

import com.jumpropeai.audio.AcousticBand
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FusionEngineTest {

    @Test
    fun selfLearningLearnsHighestSnrBandAndLag() {
        val engine = FusionEngine()

        // Create 6 seconds (600 frames @ 10ms) of synthetic flux where HIGH_2 has sharp spikes at +90ms after each jump
        val jumpPeaks = listOf(1000L, 1500L, 2000L, 2500L, 3000L, 3500L)
        val audioHistory = (0..450).map { i ->
            val t = i * 10L
            val isSpike = jumpPeaks.any { pt -> kotlin.math.abs(t - (pt + 90L)) <= 10L }
            FluxSample(
                timestampMs = t,
                mid = 0.01f,
                high1 = if (isSpike) 0.03f else 0.01f,
                high2 = if (isSpike) 0.25f else 0.005f,
                wideHigh = if (isSpike) 0.15f else 0.005f
            )
        }

        var totalAccepted = 0
        for (pt in jumpPeaks) {
            val res = engine.evaluateCandidate(
                candidate = JumpCandidate(peakTimeMs = pt, landTimeMs = pt + 120L, amplitude = 0.25f, ankleAlternation = 0.05f),
                audioHistory = audioHistory,
                useAudioFusion = true
            )
            totalAccepted += res.acceptedJumps
        }

        assertEquals(6, totalAccepted)
        val fp = engine.currentFingerprint()
        assertTrue("Expected self-learning calibration to complete", fp.isCalibrated)
        assertEquals(AcousticBand.HIGH_2, fp.band)
        assertTrue("Expected learned lag near 90ms, got ${fp.lagMs}", fp.lagMs in 70L..110L)
    }

    @Test
    fun fallsBackGracefullyWhenAudioIsNoisyOrSilent() {
        val engine = FusionEngine()
        // Flat noisy audio (SNR ~ 1.0x)
        val noisyAudio = (0..400).map { i ->
            FluxSample(i * 10L, 0.05f, 0.05f, 0.05f, 0.05f)
        }

        var accepted = 0
        for (i in 0 until 5) {
            val pt = 1000L + i * 480L
            val res = engine.evaluateCandidate(
                candidate = JumpCandidate(pt, pt + 120L, amplitude = 0.24f, ankleAlternation = 0.04f),
                audioHistory = noisyAudio,
                useAudioFusion = true
            )
            accepted += res.acceptedJumps
        }

        assertEquals(5, accepted)
    }

    @Test
    fun stftEngineDetectsHighFrequencyImpulse() {
        val stft = StftAudioEngine(sampleRate = 16_000)
        val pcm = FloatArray(16_000) // 1 second
        // Inject a crisp 5 kHz rope-whip burst at 0.5s (samples 8000..8320)
        for (i in 8000 until 8320) {
            pcm[i] = (0.6 * sin(2.0 * PI * 5000.0 * i / 16_000.0)).toFloat()
        }
        val flux = stft.processOfflinePcm(pcm, 16_000)
        assertTrue(flux.isNotEmpty())
        val maxHi2 = flux.maxOf { it.high2 }
        val maxMid = flux.maxOf { it.mid }
        assertTrue("Expected 5kHz burst to dominate HIGH_2 ($maxHi2) over MID ($maxMid)", maxHi2 > maxMid * 2f)
    }
}
