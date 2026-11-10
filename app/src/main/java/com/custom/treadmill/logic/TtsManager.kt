package com.custom.treadmill.logic

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // [ФИКС] Не хардкодим русский. Пытаемся по очереди:
            // 1) локаль системы — чтобы интерфейс и озвучка совпадали;
            // 2) ru-RU — обратная совместимость со старым поведением;
            // 3) en-US — на случай, если совсем ничего другого нет.
            val candidates = listOf(
                Locale.getDefault(),
                Locale("ru", "RU"),
                Locale.US
            )
            var applied: Locale? = null
            for (loc in candidates) {
                val res = tts?.setLanguage(loc) ?: continue
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    applied = loc
                    break
                }
            }
            if (applied != null) {
                isInitialized = true
                tts?.setSpeechRate(1.0f)
                Log.d("TTS", "Язык TTS: $applied")
            } else {
                Log.e("TTS", "Ни одна локаль не поддерживается движком TTS")
            }
        } else {
            Log.e("TTS", "Ошибка инициализации TTS")
        }
    }

    fun speak(text: String) {
        if (isInitialized && text.isNotBlank()) {
            // QUEUE_FLUSH прерывает текущую фразу, чтобы не было задержек
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "treadmill_tts")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
