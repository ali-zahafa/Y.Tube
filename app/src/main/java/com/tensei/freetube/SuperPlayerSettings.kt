package com.tensei.freetube

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal object SuperPlayerSettings {
    private lateinit var preferences: SharedPreferences

    var skipSilence by mutableStateOf(false)
        private set
    var audioBoost by mutableStateOf(false)
        private set
    var deArrowTitles by mutableStateOf(false)
        private set

    fun init(context: Context) {
        preferences = context.getSharedPreferences("super_player", Context.MODE_PRIVATE)
        skipSilence = preferences.getBoolean("skip_silence", false)
        audioBoost = preferences.getBoolean("audio_boost", false)
        deArrowTitles = preferences.getBoolean("dearrow_titles", false)
    }

    fun updateSkipSilence(enabled: Boolean) {
        skipSilence = enabled
        preferences.edit().putBoolean("skip_silence", enabled).apply()
    }

    fun updateAudioBoost(enabled: Boolean) {
        audioBoost = enabled
        preferences.edit().putBoolean("audio_boost", enabled).apply()
    }

    fun updateDeArrowTitles(enabled: Boolean) {
        deArrowTitles = enabled
        preferences.edit().putBoolean("dearrow_titles", enabled).apply()
    }
}
