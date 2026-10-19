package com.mathmaster.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * FIX #8 — Lightweight sound manager using SoundPool.
 * Pre-loads short sounds into memory and plays them with zero lag. Falls back
 * gracefully if a resource is missing (e.g., during initial build before the
 * audio files are added to res/raw/).
 */
class SoundManager(context: Context) {
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // Load returns 0 if the sound file is missing or invalid. Playback checks
    // for this and silently skips.
    private val correctId: Int = loadSafe(context, R.raw.correct_sound)
    private val wrongId: Int = loadSafe(context, R.raw.wrong_sound)
    // FIX #8 — session-complete sound. Optional: if the resource is missing, playback
    // is silently skipped (loadSafe returns 0). Same pattern as the other sounds.
    private val sessionCompleteId: Int = loadSafe(context, R.raw.session_complete)

    private fun loadSafe(context: Context, resId: Int): Int =
        runCatching { soundPool.load(context, resId, 1) }.getOrDefault(0)

    // FIX #8 — volume balance. The correct_sound.mp3 is typically mastered louder
    // than wrong_sound.mp3 (buzzes are intentionally less aggressive). To make them
    // feel balanced, we quiet the correct sound to 65%. Session-complete plays at
    // 85% so it feels slightly more celebratory than a correct answer.
    private val correctVolume = 0.65f
    private val wrongVolume = 1.0f
    private val sessionCompleteVolume = 0.85f

    fun playCorrect() = play(correctId, correctVolume)
    fun playWrong() = play(wrongId, wrongVolume)
    fun playSessionComplete() = play(sessionCompleteId, sessionCompleteVolume)

    private fun play(soundId: Int, volume: Float) {
        if (soundId > 0) {
            soundPool.play(soundId, volume, volume, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}
