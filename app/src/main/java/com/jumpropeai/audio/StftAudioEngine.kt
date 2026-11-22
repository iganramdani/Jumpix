package com.jumpropeai.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class AcousticBand(val label: String, val lowHz: Int, val highHz: Int) {
    MID("0.8–2.5 kHz", 800, 2500),
    HIGH_1("2.5–4.5 kHz", 2500, 4500),
    HIGH_2("4.5–7.0 kHz", 4500, 7000),
    WIDE_HIGH("2.5–6.5 kHz", 2500, 6500)
}

data class FluxSample(
    val timestampMs: Long,
    val mid: Float,
    val high1: Float,
    val high2: Float,
    val wideHigh: Float
) {
    fun valueFor(band: AcousticBand): Float = when (band) {
        AcousticBand.MID -> mid
        AcousticBand.HIGH_1 -> high1
        AcousticBand.HIGH_2 -> high2
        AcousticBand.WIDE_HIGH -> wideHigh
    }
}

/**
 * Lightweight pure-Kotlin 256-point Radix-2 STFT Spectral Flux engine (16 kHz, 10ms hop = 100 Hz).
 *
 * Powers both:
 * - Real-time microphone capture (with speaker echo-gating) for Fusion v6 & Audio-Only mode.
 * - Offline PCM analysis for MP4 video jump counting.
 */
class StftAudioEngine(
    private val sampleRate: Int = 16_000,
    private val maxHistoryFrames: Int = 600 // 6 seconds at 100 Hz
) {
    private val fftSize = 256 // 16ms window at 16 kHz
    private val hopSize = 160 // 10ms hop at 16 kHz
    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }

    private val prevLogMag = FloatArray(fftSize / 2 + 1)
    private var hasPrevSpectrum = false

    private val lock = Any()
    private val ringBuffer = ArrayDeque<FluxSample>(maxHistoryFrames + 16)
    @Volatile
    private var echoMuteUntilMs: Long = 0L

    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    // Audio-Only v4 state
    private var audioOnlyLastJumpMs: Long = -10_000L
    private var audioOnlyPendingFirstMs: Long? = null
    private val audioOnlyIntervals = ArrayDeque<Long>()
    private var audioOnlyPrevFlux = 0f
    private var audioOnlyPrev2Flux = 0f

    fun reset() {
        synchronized(lock) {
            ringBuffer.clear()
            prevLogMag.fill(0f)
            hasPrevSpectrum = false
            audioOnlyLastJumpMs = -10_000L
            audioOnlyPendingFirstMs = null
            audioOnlyIntervals.clear()
            audioOnlyPrevFlux = 0f
            audioOnlyPrev2Flux = 0f
        }
    }

    /**
     * Temporarily gates microphone frames while the app's own TTS / beep plays to prevent self-triggering.
     */
    fun notifySpeakerOutput(durationMs: Long = 200L) {
        echoMuteUntilMs = SystemClock.elapsedRealtime() + durationMs
    }

    @SuppressLint("MissingPermission")
    fun startLiveCapture(onAudioOnlyJump: ((Long) -> Unit)? = null) {
        if (!isRecording.compareAndSet(false, true)) return

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(fftSize * 4)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            isRecording.set(false)
            runCatching { recorder?.release() }
            return
        }

        recordThread = Thread({
            val readBuf = ShortArray(hopSize)
            val frameRing = FloatArray(fftSize)
            var filled = 0
            try {
                recorder.startRecording()
                while (isRecording.get()) {
                    val read = recorder.read(readBuf, 0, hopSize)
                    if (read <= 0) continue
                    val nowMs = SystemClock.elapsedRealtime()
                    val muted = nowMs < echoMuteUntilMs

                    // Shift ring buffer left by `read` and append new normalized samples
                    if (read < fftSize) {
                        System.arraycopy(frameRing, read, frameRing, 0, fftSize - read)
                    }
                    val startDst = max(0, fftSize - read)
                    for (i in 0 until min(read, fftSize)) {
                        frameRing[startDst + i] = if (muted) 0f else readBuf[i] / 32768f
                    }
                    filled = min(fftSize, filled + read)
                    if (filled >= fftSize) {
                        val sample = processFftFrame(frameRing, nowMs)
                        synchronized(lock) {
                            ringBuffer.addLast(sample)
                            while (ringBuffer.size > maxHistoryFrames) {
                                ringBuffer.removeFirst()
                            }
                        }
                        if (onAudioOnlyJump != null) {
                            val jumpTime = evaluateAudioOnlyStep(sample)
                            if (jumpTime != null) {
                                onAudioOnlyJump(jumpTime)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Gracefully stop capture on audio error
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }, "JumpRopeAudioSTFT").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun stopLiveCapture() {
        isRecording.set(false)
        recordThread?.interrupt()
        recordThread = null
    }

    /**
     * Offline batch processor for decoded 16 kHz mono PCM from an MP4 video.
     */
    fun processOfflinePcm(pcm: FloatArray, pcmSampleRate: Int = 16_000): List<FluxSample> {
        reset()
        val resampled = if (pcmSampleRate == sampleRate) {
            pcm
        } else {
            resampleLinear(pcm, pcmSampleRate, sampleRate)
        }
        val results = ArrayList<FluxSample>(max(1, resampled.size / hopSize))
        val windowBuf = FloatArray(fftSize)
        var offset = 0
        var frameIdx = 0
        while (offset + fftSize <= resampled.size) {
            System.arraycopy(resampled, offset, windowBuf, 0, fftSize)
            val tsMs = frameIdx * 10L
            results.add(processFftFrame(windowBuf, tsMs))
            offset += hopSize
            frameIdx++
        }
        return results
    }

    fun snapshotHistory(): List<FluxSample> = synchronized(lock) { ringBuffer.toList() }

    /**
     * Pure Kotlin 256-point Radix-2 FFT computing positive log-magnitude spectral flux across 4 bands.
     */
    internal fun processFftFrame(samples: FloatArray, timestampMs: Long): FluxSample {
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            re[i] = samples[i] * hannWindow[i]
        }
        fftRadix2(re, im)

        val binHz = sampleRate.toFloat() / fftSize.toFloat() // 62.5 Hz per bin
        var sumMid = 0f; var cntMid = 0
        var sumHi1 = 0f; var cntHi1 = 0
        var sumHi2 = 0f; var cntHi2 = 0
        var sumWide = 0f; var cntWide = 0

        val half = fftSize / 2
        for (k in 1..half) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / fftSize
            val logMag = ln1p(100.0 * mag).toFloat()
            val diff = if (hasPrevSpectrum) max(0f, logMag - prevLogMag[k]) else 0f
            prevLogMag[k] = logMag

            val freq = k * binHz
            if (freq in 800f..2500f) { sumMid += diff; cntMid++ }
            if (freq in 2500f..4500f) { sumHi1 += diff; cntHi1++ }
            if (freq in 4500f..7000f) { sumHi2 += diff; cntHi2++ }
            if (freq in 2500f..6500f) { sumWide += diff; cntWide++ }
        }
        hasPrevSpectrum = true

        return FluxSample(
            timestampMs = timestampMs,
            mid = if (cntMid > 0) sumMid / cntMid else 0f,
            high1 = if (cntHi1 > 0) sumHi1 / cntHi1 else 0f,
            high2 = if (cntHi2 > 0) sumHi2 / cntHi2 else 0f,
            wideHigh = if (cntWide > 0) sumWide / cntWide else 0f
        )
    }

    /**
     * Audio-Only v4 online onset detector (used when user picks Ultra-Eco Audio-Only mode).
     */
    private fun evaluateAudioOnlyStep(sample: FluxSample): Long? {
        val smoothed = 0.2f * audioOnlyPrev2Flux + 0.6f * audioOnlyPrevFlux + 0.2f * sample.wideHigh
        val prev = audioOnlyPrevFlux
        val prev2 = audioOnlyPrev2Flux
        audioOnlyPrev2Flux = audioOnlyPrevFlux
        audioOnlyPrevFlux = sample.wideHigh

        val history = snapshotHistory()
        if (history.size < 25) return null

        val recent = history.takeLast(150).map { it.wideHigh }
        val med = median(recent) + 1e-5f
        val mad = median(recent.map { abs(it - med) }) + 1e-5f

        val nowMs = sample.timestampMs
        val dt = nowMs - audioOnlyLastJumpMs
        val inBeat: Boolean
        val refractory: Long
        if (audioOnlyIntervals.size >= 3 && dt < 1500L) {
            val tempo = medianLong(audioOnlyIntervals.toList())
            refractory = max(270L, (tempo * 0.68).toLong())
            inBeat = dt in (tempo * 0.72).toLong()..(tempo * 1.28).toLong()
        } else {
            refractory = 300L
            inBeat = false
        }

        val zThresh = if (inBeat) 5.5f else 8.5f
        val minAbs = if (inBeat) 0.015f else 0.025f
        val isLocalPeak = prev >= prev2 && prev > smoothed

        if (isLocalPeak && prev > max(minAbs, med + zThresh * mad) && (prev / med) >= 2.2f) {
            if (dt >= refractory) {
                val pending = audioOnlyPendingFirstMs
                return if (pending != null && (nowMs - pending) in 260L..850L) {
                    recordAudioInterval(nowMs - pending)
                    audioOnlyPendingFirstMs = null
                    audioOnlyLastJumpMs = nowMs
                    nowMs
                } else if (audioOnlyLastJumpMs > 0L && dt <= 1100L) {
                    if (dt in 260L..900L) recordAudioInterval(dt)
                    audioOnlyLastJumpMs = nowMs
                    nowMs
                } else {
                    audioOnlyPendingFirstMs = nowMs
                    audioOnlyLastJumpMs = nowMs
                    null
                }
            }
        }
        return null
    }

    private fun recordAudioInterval(dt: Long) {
        audioOnlyIntervals.addLast(dt)
        while (audioOnlyIntervals.size > 6) audioOnlyIntervals.removeFirst()
    }

    companion object {
        internal fun fftRadix2(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wLenRe = cos(ang).toFloat()
                val wLenIm = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var wRe = 1f
                    var wIm = 0f
                    val half = len shr 1
                    for (k in 0 until half) {
                        val uRe = re[i + k]
                        val uIm = im[i + k]
                        val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                        val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                        re[i + k] = uRe + vRe
                        im[i + k] = uIm + vIm
                        re[i + k + half] = uRe - vRe
                        im[i + k + half] = uIm - vIm
                        val nextWRe = wRe * wLenRe - wIm * wLenIm
                        wIm = wRe * wLenIm + wIm * wLenRe
                        wRe = nextWRe
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun resampleLinear(input: FloatArray, fromSr: Int, toSr: Int): FloatArray {
            if (input.isEmpty() || fromSr <= 0) return FloatArray(0)
            val ratio = fromSr.toDouble() / toSr.toDouble()
            val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val idx = srcPos.toInt().coerceIn(0, input.lastIndex)
                val nextIdx = (idx + 1).coerceAtMost(input.lastIndex)
                val frac = (srcPos - idx).toFloat()
                out[i] = input[idx] * (1f - frac) + input[nextIdx] * frac
            }
            return out
        }

        fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) * 0.5f else sorted[mid]
        }

        fun percentile(values: List<Float>, p: Float): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val idx = ((p / 100f) * sorted.lastIndex).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }

        fun medianLong(values: List<Long>): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}
