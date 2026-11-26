package com.jumpropeai.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.Arrays
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
    BASS("60–250 Hz", 60, 250),
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
    val wideHigh: Float,
    val bass: Float = 0f,
    val tonalBuzzer: Float = 0f
) {
    fun valueFor(band: AcousticBand): Float = when (band) {
        AcousticBand.BASS -> bass
        AcousticBand.MID -> mid
        AcousticBand.HIGH_1 -> high1
        AcousticBand.HIGH_2 -> high2
        AcousticBand.WIDE_HIGH -> wideHigh
    }
}

/**
 * Lightweight pure-Kotlin 256-point Radix-2 STFT Spectral Flux engine (16 kHz, 10ms/5ms hop = 100/200 Hz).
 *
 * v16 Upgrades:
 * - Supports High-Resolution `200 Hz` (`5ms` hop = 80 samples) offline mode for IJRC Competition Speed Wire Whips.
 * - Extracts `tonalBuzzer` (`800–1450 Hz` narrowband tone excess) for automatic IJRU Start/Finish Horn detection.
 * - Integrates Zero-GC `DoubleClapDetector` in live capture for hands-free Start/Pause from 5+ meters.
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

    // Pre-allocated FFT buffers (Zero-GC at 100/200 Hz)
    private val fftRe = FloatArray(fftSize)
    private val fftIm = FloatArray(fftSize)
    private val prevLogMag = FloatArray(fftSize / 2 + 1)
    private var hasPrevSpectrum = false
    private val doubleClapDetector = DoubleClapDetector()

    private val lock = Any()
    private val ringBuffer = ArrayDeque<FluxSample>(maxHistoryFrames + 16)
    @Volatile
    private var echoMuteUntilMs: Long = 0L

    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    // Audio-Only v14 Zero-GC state & scratch arrays
    private var audioOnlyLastJumpMs: Long = -10_000L
    private var audioOnlyPendingFirstMs: Long? = null
    private val audioOnlyIntervals = LongArray(6)
    private var audioOnlyIntervalHead = 0
    private var audioOnlyIntervalCount = 0
    private val audioIntervalSortScratch = LongArray(6)
    private val audioFluxScratch = FloatArray(160)
    private var audioOnlyPrevFlux = 0f
    private var audioOnlyPrev2Flux = 0f

    fun reset() {
        synchronized(lock) {
            ringBuffer.clear()
            prevLogMag.fill(0f)
            hasPrevSpectrum = false
            doubleClapDetector.reset()
            audioOnlyLastJumpMs = -10_000L
            audioOnlyPendingFirstMs = null
            audioOnlyIntervalHead = 0
            audioOnlyIntervalCount = 0
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
    fun startLiveCapture(
        onAudioOnlyJump: ((Long) -> Unit)? = null,
        onDoubleClap: (() -> Unit)? = null
    ) {
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

                    if (read < fftSize) {
                        System.arraycopy(frameRing, read, frameRing, 0, fftSize - read)
                    }
                    val startDst = max(0, fftSize - read)
                    for (i in 0 until min(read, fftSize)) {
                        frameRing[startDst + i] = if (muted) 0f else readBuf[i] * (1f / 32768f)
                    }
                    filled = min(fftSize, filled + read)
                    if (filled >= fftSize) {
                        val sample = processFftFrame(frameRing, nowMs)
                        var jumpTime: Long? = null
                        var clapTriggered = false
                        synchronized(lock) {
                            ringBuffer.addLast(sample)
                            while (ringBuffer.size > maxHistoryFrames) {
                                ringBuffer.removeFirst()
                            }
                            if (onAudioOnlyJump != null) {
                                jumpTime = evaluateAudioOnlyStepLocked(sample)
                            }
                            if (!muted && onDoubleClap != null && doubleClapDetector.processFlux(sample)) {
                                clapTriggered = true
                            }
                        }
                        if (jumpTime != null && onAudioOnlyJump != null) {
                            onAudioOnlyJump(jumpTime!!)
                        }
                        if (clapTriggered && onDoubleClap != null) {
                            onDoubleClap()
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
     * Offline batch processor for decoded mono PCM (supports 100 Hz standard or 200 Hz high-res 5ms hop).
     */
    fun processOfflinePcm(
        pcm: FloatArray,
        pcmSampleRate: Int = 16_000,
        highRes200Hz: Boolean = false
    ): List<FluxSample> {
        reset()
        val resampled = if (pcmSampleRate == sampleRate) {
            pcm
        } else {
            resampleAntiAliased(pcm, pcmSampleRate, sampleRate)
        }
        val stepHop = if (highRes200Hz) hopSize / 2 else hopSize // 80 samples (5ms) vs 160 samples (10ms)
        val dtMs = if (highRes200Hz) 5L else 10L
        val results = ArrayList<FluxSample>(max(1, resampled.size / stepHop))
        val windowBuf = FloatArray(fftSize)
        var offset = 0
        var frameIdx = 0L
        while (offset + fftSize <= resampled.size) {
            System.arraycopy(resampled, offset, windowBuf, 0, fftSize)
            val tsMs = frameIdx * dtMs
            results.add(processFftFrame(windowBuf, tsMs))
            offset += stepHop
            frameIdx++
        }
        return results
    }

    /**
     * O(1)-memory streaming offline PCM -> 100 Hz / 200 Hz STFT consumer for long videos.
     */
    inner class StreamingOfflineSession(
        inputSampleRate: Int,
        highRes200Hz: Boolean = false
    ) {
        private val inSr = inputSampleRate.coerceAtLeast(8_000)
        private val step = inSr.toDouble() / sampleRate.toDouble()
        private val filterRadius = max(1, (step * 0.5).toInt())
        private val effHop = if (highRes200Hz) hopSize / 2 else hopSize
        private val dtMs = if (highRes200Hz) 5L else 10L
        private var srcPos = 0.0
        private val ring16k = FloatArray(fftSize)
        private var ringCount = 0
        private var hopAccum = 0
        private var frameIdx = 0L
        private val outFlux = ArrayList<FluxSample>(8192)

        init {
            reset()
        }

        fun feedMonoChunk(chunk: FloatArray, length: Int) {
            if (length <= 0) return
            if (inSr == sampleRate) {
                for (i in 0 until length) {
                    push16kSample(chunk[i])
                }
                return
            }
            while (srcPos < length) {
                val center = srcPos.toInt().coerceIn(0, length - 1)
                var acc = 0f
                var wSum = 0f
                val startK = (center - filterRadius).coerceAtLeast(0)
                val endK = (center + filterRadius).coerceAtMost(length - 1)
                for (k in startK..endK) {
                    val w = (filterRadius + 1 - abs(k - center)).toFloat()
                    acc += chunk[k] * w
                    wSum += w
                }
                val filtered = if (wSum > 0f) acc / wSum else chunk[center]
                push16kSample(filtered)
                srcPos += step
            }
            srcPos -= length.toDouble()
            if (srcPos < 0.0) srcPos = 0.0
        }

        private fun push16kSample(s: Float) {
            if (ringCount < fftSize) {
                ring16k[ringCount++] = s
                if (ringCount == fftSize) {
                    outFlux.add(processFftFrame(ring16k, frameIdx * dtMs))
                    frameIdx++
                    hopAccum = 0
                }
            } else {
                System.arraycopy(ring16k, 1, ring16k, 0, fftSize - 1)
                ring16k[fftSize - 1] = s
                hopAccum++
                if (hopAccum >= effHop) {
                    hopAccum = 0
                    outFlux.add(processFftFrame(ring16k, frameIdx * dtMs))
                    frameIdx++
                }
            }
        }

        fun finish(): List<FluxSample> = outFlux
    }

    fun snapshotHistory(): List<FluxSample> = synchronized(lock) { ringBuffer.toList() }

    /**
     * Pure Kotlin 256-point Radix-2 FFT computing positive log-magnitude spectral flux across 5 bands
     * plus narrowband IJRU competition buzzer tone prominence (`800–1450 Hz`).
     */
    internal fun processFftFrame(samples: FloatArray, timestampMs: Long): FluxSample {
        for (i in 0 until fftSize) {
            fftRe[i] = samples[i] * hannWindow[i]
            fftIm[i] = 0f
        }
        fftRadix2(fftRe, fftIm)

        val binHz = sampleRate.toFloat() / fftSize.toFloat() // 62.5 Hz per bin
        var sumBass = 0f; var cntBass = 0
        var sumMid = 0f; var cntMid = 0
        var sumHi1 = 0f; var cntHi1 = 0
        var sumHi2 = 0f; var cntHi2 = 0
        var sumWide = 0f; var cntWide = 0
        var peakToneLinear = 0f
        var sumBroadLinear = 0f; var cntBroadLinear = 0

        val half = fftSize / 2
        for (k in 1..half) {
            val mag = sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]) / fftSize
            val logMag = ln1p(100.0 * mag).toFloat()
            val diff = if (hasPrevSpectrum) max(0f, logMag - prevLogMag[k]) else 0f
            prevLogMag[k] = logMag

            val freq = k * binHz
            if (freq in 60f..255f) { sumBass += diff; cntBass++ }
            if (freq in 800f..2500f) { sumMid += diff; cntMid++ }
            if (freq in 2500f..4500f) { sumHi1 += diff; cntHi1++ }
            if (freq in 4500f..7000f) { sumHi2 += diff; cntHi2++ }
            if (freq in 2500f..6500f) { sumWide += diff; cntWide++ }
            if (freq in 750f..1500f && mag > peakToneLinear) { peakToneLinear = mag }
            if (freq in 250f..5000f) { sumBroadLinear += mag; cntBroadLinear++ }
        }
        hasPrevSpectrum = true

        val broadFloor = if (cntBroadLinear > 0) sumBroadLinear / cntBroadLinear else 0f
        val buzzerScore = if (peakToneLinear > 0.0012f) peakToneLinear / (broadFloor + 1e-4f) else 0f

        return FluxSample(
            timestampMs = timestampMs,
            mid = if (cntMid > 0) sumMid / cntMid else 0f,
            high1 = if (cntHi1 > 0) sumHi1 / cntHi1 else 0f,
            high2 = if (cntHi2 > 0) sumHi2 / cntHi2 else 0f,
            wideHigh = if (cntWide > 0) sumWide / cntWide else 0f,
            bass = if (cntBass > 0) sumBass / cntBass else 0f,
            tonalBuzzer = buzzerScore
        )
    }

    /**
     * Zero-GC Audio-Only v14 online onset detector (must be called while holding [lock]).
     */
    private fun evaluateAudioOnlyStepLocked(sample: FluxSample): Long? {
        val smoothed = 0.2f * audioOnlyPrev2Flux + 0.6f * audioOnlyPrevFlux + 0.2f * sample.wideHigh
        val prev = audioOnlyPrevFlux
        val prev2 = audioOnlyPrev2Flux
        audioOnlyPrev2Flux = audioOnlyPrevFlux
        audioOnlyPrevFlux = sample.wideHigh

        val totalSize = ringBuffer.size
        if (totalSize < 25) return null

        val count = min(150, totalSize)
        val startIdx = totalSize - count
        for (i in 0 until count) {
            audioFluxScratch[i] = ringBuffer[startIdx + i].wideHigh
        }
        Arrays.sort(audioFluxScratch, 0, count)
        val midIdx = count / 2
        val rawMed = if (count % 2 == 0) {
            (audioFluxScratch[midIdx - 1] + audioFluxScratch[midIdx]) * 0.5f
        } else {
            audioFluxScratch[midIdx]
        }
        val med = rawMed + 1e-5f

        for (i in 0 until count) {
            audioFluxScratch[i] = abs(ringBuffer[startIdx + i].wideHigh - med)
        }
        Arrays.sort(audioFluxScratch, 0, count)
        val rawMad = if (count % 2 == 0) {
            (audioFluxScratch[midIdx - 1] + audioFluxScratch[midIdx]) * 0.5f
        } else {
            audioFluxScratch[midIdx]
        }
        val mad = rawMad + 1e-5f

        val nowMs = sample.timestampMs
        val dt = nowMs - audioOnlyLastJumpMs
        val inBeat: Boolean
        val refractory: Long
        if (audioOnlyIntervalCount >= 3 && dt < 1500L) {
            for (i in 0 until audioOnlyIntervalCount) {
                audioIntervalSortScratch[i] = audioOnlyIntervals[i]
            }
            Arrays.sort(audioIntervalSortScratch, 0, audioOnlyIntervalCount)
            val tempo = audioIntervalSortScratch[audioOnlyIntervalCount / 2]
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
        audioOnlyIntervals[audioOnlyIntervalHead] = dt
        audioOnlyIntervalHead = (audioOnlyIntervalHead + 1) % audioOnlyIntervals.size
        if (audioOnlyIntervalCount < audioOnlyIntervals.size) audioOnlyIntervalCount++
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

        /**
         * Anti-aliased low-pass decimation resampler preventing >8 kHz noise/hi-hats from folding
         * into the 2.5–7.0 kHz rope-slap spectrum when downsampling 44.1/48 kHz video audio to 16 kHz.
         */
        internal fun resampleAntiAliased(input: FloatArray, fromSr: Int, toSr: Int): FloatArray {
            if (input.isEmpty() || fromSr <= 0) return FloatArray(0)
            val ratio = fromSr.toDouble() / toSr.toDouble()
            val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
            val out = FloatArray(outLen)
            if (ratio <= 1.05) {
                for (i in 0 until outLen) {
                    val srcPos = i * ratio
                    val idx = srcPos.toInt().coerceIn(0, input.lastIndex)
                    val nextIdx = (idx + 1).coerceAtMost(input.lastIndex)
                    val frac = (srcPos - idx).toFloat()
                    out[i] = input[idx] * (1f - frac) + input[nextIdx] * frac
                }
                return out
            }

            val radius = max(1, (ratio * 0.5).toInt())
            for (i in 0 until outLen) {
                val center = (i * ratio).toInt().coerceIn(0, input.lastIndex)
                val startK = (center - radius).coerceAtLeast(0)
                val endK = (center + radius).coerceAtMost(input.lastIndex)
                var sum = 0f
                var wSum = 0f
                for (k in startK..endK) {
                    val w = (radius + 1 - abs(k - center)).toFloat()
                    sum += input[k] * w
                    wSum += w
                }
                out[i] = if (wSum > 0f) sum / wSum else input[center]
            }
            return out
        }

        fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val arr = FloatArray(values.size)
            for (i in values.indices) arr[i] = values[i]
            Arrays.sort(arr)
            val mid = arr.size / 2
            return if (arr.size % 2 == 0) (arr[mid - 1] + arr[mid]) * 0.5f else arr[mid]
        }

        fun percentile(values: List<Float>, p: Float): Float {
            if (values.isEmpty()) return 0f
            val arr = FloatArray(values.size)
            for (i in values.indices) arr[i] = values[i]
            Arrays.sort(arr)
            val idx = ((p / 100f) * arr.lastIndex).toInt().coerceIn(0, arr.lastIndex)
            return arr[idx]
        }

        fun medianLong(values: List<Long>): Long {
            if (values.isEmpty()) return 0L
            val arr = LongArray(values.size)
            for (i in values.indices) arr[i] = values[i]
            Arrays.sort(arr)
            return arr[arr.size / 2]
        }
    }
}
