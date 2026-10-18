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
        .setMaxStreams(2)
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

    private fun loadSafe(context: Context, resId: Int): Int =
        runCatching { soundPool.load(context, resId, 1) }.getOrDefault(0)

    fun playCorrect() = play(correctId)
    fun playWrong() = play(wrongId)

    private fun play(soundId: Int) {
        if (soundId > 0) {
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}