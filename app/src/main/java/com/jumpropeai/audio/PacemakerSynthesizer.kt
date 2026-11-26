package com.jumpropeai.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class PacemakerMode(val label: String) {
    OFF("Off"),
    CADENCE_LOCK("Cadence Lock"),
    SPEED_LADDER("Speed Ladder (+15/30s)"),
    DOUBLE_UNDER_DRILL("DU Drill 1-2-3-4-WHIP"),
    IJRC_SPRINT("IJRC Sprint Pacer (360+)")
}

/**
 * Jumpvex v6.1 — Sample-Accurate Procedural PCM Audio Pacemaker (`±0.05 ms` precision, 0 KB assets!).
 *
 * Eliminates coroutine `delay()` jitter by writing exact silent PCM sample spans directly into the
 * hardware `AudioTrack` ring stream — supporting `100` up to `420 SPM` for IJRC Speed Sprint training!
 */
class PacemakerSynthesizer(
    private val onTickPlayed: (Long) -> Unit = {}
) {
    private companion object {
        const val SAMPLE_RATE = 22_050
        const val CLICK_SAMPLES = 440 // ~20 ms crisp percussive woodblock
        const val CHUNK_SILENCE_MAX = 2048
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val normalClickPcm: ShortArray = synthesizeClick(freqHz = 1180.0, decay = 9.5)
    private val accentClickPcm: ShortArray = synthesizeClick(freqHz = 1960.0, decay = 7.0)
    private val zeroPcm = ShortArray(CHUNK_SILENCE_MAX)

    @Volatile
    var mode: PacemakerMode = PacemakerMode.OFF
        private set

    @Volatile
    var targetRpm: Int = 160
        private set

    fun configure(newMode: PacemakerMode, newRpm: Int) {
        mode = newMode
        targetRpm = newRpm.coerceIn(100, 420)
    }

    fun start() {
        stop()
        if (mode == PacemakerMode.OFF) return
        loopJob = scope.launch {
            var beatIndex = 0
            var totalFramesWritten = 0L
            val track = buildAudioTrack() ?: return@launch
            try {
                track.play()
                while (isActive && mode != PacemakerMode.OFF) {
                    val elapsedSec = totalFramesWritten / SAMPLE_RATE.toLong()
                    val effectiveRpm = when (mode) {
                        PacemakerMode.SPEED_LADDER ->
                            (targetRpm + (elapsedSec / 30L).toInt() * 15).coerceAtMost(420)
                        PacemakerMode.IJRC_SPRINT ->
                            max(320, targetRpm)
                        else -> targetRpm
                    }
                    val periodSamples = max(CLICK_SAMPLES + 64, (SAMPLE_RATE * 60.0 / effectiveRpm).roundToInt())
                    val isAccent = when (mode) {
                        PacemakerMode.DOUBLE_UNDER_DRILL -> (beatIndex % 5 == 4)
                        PacemakerMode.IJRC_SPRINT -> (beatIndex % 2 == 0) // Right-foot IJRC accent!
                        else -> (beatIndex % 8 == 0)
                    }
                    val pcm = if (isAccent) accentClickPcm else normalClickPcm
                    onTickPlayed(30L)
                    track.write(pcm, 0, pcm.size)

                    var remSilence = periodSamples - pcm.size
                    while (remSilence > 0 && isActive && mode != PacemakerMode.OFF) {
                        val n = min(remSilence, CHUNK_SILENCE_MAX)
                        track.write(zeroPcm, 0, n)
                        remSilence -= n
                    }
                    totalFramesWritten += periodSamples
                    beatIndex++
                }
            } catch (_: Exception) {
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
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
        ).coerceAtLeast(SAMPLE_RATE / 4)
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
