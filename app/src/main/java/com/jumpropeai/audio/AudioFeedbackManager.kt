package com.jumpropeai.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

sealed interface AudioCue {
    data object JumpTick : AudioCue
    data object SessionStart : AudioCue
    data class GestureTriggered(val starting: Boolean) : AudioCue
    data class Milestone(val count: Int) : AudioCue
    data class HiitWorkStart(val round: Int) : AudioCue
    data object HiitRestStart : AudioCue
    data object CountdownTick : AudioCue
    data class WorkoutCompleted(val totalJumps: Int) : AudioCue
}

/**
 * Provides real-time audio cues (jump tick beeps, touchless gesture confirmation, HIIT phase tones,
 * and International English Text-to-Speech voice coaching).
 */
class AudioFeedbackManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 65)
    }.getOrNull()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setSpeechRate(1.08f)
                isTtsReady = true
            }
        }
    }

    fun play(cue: AudioCue, enabled: Boolean) {
        if (!enabled) return
        when (cue) {
            AudioCue.JumpTick -> {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
            }

            AudioCue.SessionStart -> {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                speak("Go!")
            }

            is AudioCue.GestureTriggered -> {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                speak(if (cue.starting) "Ready!" else "Paused")
            }

            is AudioCue.Milestone -> {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
                speak("${cue.count} jumps!")
            }

            is AudioCue.HiitWorkStart -> {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                speak("Round ${cue.round}, jump!")
            }

            AudioCue.HiitRestStart -> {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                speak("Rest")
            }

            AudioCue.CountdownTick -> {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 75)
            }

            is AudioCue.WorkoutCompleted -> {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 260)
                speak("Workout complete! ${cue.totalJumps} jumps. Great job!")
            }
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jumpvex_${System.currentTimeMillis()}")
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        runCatching { toneGenerator?.release() }
        tts = null
    }
}
