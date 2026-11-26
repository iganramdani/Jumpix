package com.jumpropeai.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

enum class PacemakerMode(val label: String) {
    OFF("Off"),
    CADENCE_LOCK("Cadence Lock"),
    SPEED_LADDER("Speed Ladder (+10/30s)"),
    DOUBLE_UNDER_DRILL("Double-Under 1-2-3-4-WHIP")
}

/**
 * Jumpvex v4.2 — Zero-KB Procedural PCM Audio Pacemaker Synthesizer.
 *
 * Synthesizes crisp woodblock & metallic whip-accent clicks mathematically in RAM (0 KB audio assets)
 * for Target RPM Lock (100–240 RPM), Progressive Speed Ladder, and Double-Under Polyrhythm Drills.
 */
class PacemakerSynthesizer(
    private val onTickPlayed: (Long) -> Unit = {}
) {
    private companion object {
        const val SAMPLE_RATE = 22_050
        const val CLICK_SAMPLES = 520 // ~23.5 ms percussive click
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var normalClickPcm: ShortArray = synthesizeClick(freqHz = 1180.0, decay = 9.5)
    private var accentClickPcm: ShortArray = synthesizeClick(freqHz = 1920.0, decay = 7.0)

    @Volatile
    var mode: PacemakerMode = PacemakerMode.OFF
        private set

    @Volatile
    var targetRpm: Int = 150
        private set

    fun configure(newMode: PacemakerMode, newRpm: Int) {
        mode = newMode
        targetRpm = newRpm.coerceIn(100, 240)
    }

    fun start() {
        stop()
        if (mode == PacemakerMode.OFF) return
        loopJob = scope.launch {
            var beatIndex = 0
            val startMs = System.currentTimeMillis()
            val track = buildAudioTrack() ?: return@launch
            try {
                track.play()
                while (isActive && mode != PacemakerMode.OFF) {
                    val elapsedSec = (System.currentTimeMillis() - startMs) / 1000L
                    val effectiveRpm = when (mode) {
                        PacemakerMode.SPEED_LADDER ->
                            (targetRpm + (elapsedSec / 30L).toInt() * 10).coerceAtMost(240)
                        else -> targetRpm
                    }
                    val intervalMs = (60_000L / effectiveRpm.coerceAtLeast(100)).coerceAtLeast(250L)
                    val isAccent = when (mode) {
                        PacemakerMode.DOUBLE_UNDER_DRILL -> (beatIndex % 5 == 4)
                        else -> (beatIndex % 8 == 0)
                    }
                    val pcm = if (isAccent) accentClickPcm else normalClickPcm
                    onTickPlayed(35L)
                    track.write(pcm, 0, pcm.size)
                    beatIndex++
                    delay(intervalMs)
                }
            } catch (_: Exception) {
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun buildAudioTrack(): AudioTrack? = runCatching {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CLICK_SAMPLES * 4)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrNull()

    private fun synthesizeClick(freqHz: Double, decay: Double): ShortArray {
        val out = ShortArray(CLICK_SAMPLES)
        for (i in 0 until CLICK_SAMPLES) {
            val t = i.toDouble() / SAMPLE_RATE.toDouble()
            val env = exp(-decay * i.toDouble() / CLICK_SAMPLES.toDouble())
            val wave = sin(2.0 * PI * freqHz * t) * 0.72 + sin(2.0 * PI * freqHz * 1.5 * t) * 0.28
            out[i] = (wave * env * 22_000.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }
}
