package com.custom.treadmill.data.repository

import android.content.Context
import com.custom.treadmill.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

enum class HrMode { TARGET, ZONE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ExperienceLevel { BEGINNER, INTERMEDIATE, ADVANCED }

@Serializable
data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val weightKg: Double = 75.0,
    val heightCm: Int = 175,
    val age: Int = 30,
    val isMale: Boolean = true,
    val restingHr: Int = 60,
    val level: ExperienceLevel = ExperienceLevel.BEGINNER
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = true,

    val profiles: List<UserProfile> = listOf(UserProfile()),
    // [ФИКС] firstOrNull вместо first: защита от пустого profiles в JSON
    val activeProfileId: String = profiles.firstOrNull()?.id ?: "",

    val minSpeedKmh: Double = 1.0,
    val maxSpeedKmh: Double = 20.0,
    val maxInclinePercent: Double = 15.0,
    val speedStepKmh: Double = 0.5,

    val hrEnabled: Boolean = false,
    val hrMode: HrMode = HrMode.TARGET,
    val targetHr: Int = 130,
    val zoneMin: Int = 120,
    val zoneMax: Int = 140,
    val thresholdHigh: Int = 5,
    val thresholdLow: Int = 5,
    val intervalSec: Int = 30,
    val stepKmh: Double = 0.5,
    val autoMaxHr: Boolean = true,
    val maxHr: Int = 190,

    // [ФИКС] Пустая строка теперь означает "пользователь очистил", а не
    // "не задано". Дефолты из ресурсов подставляются только при первом
    // запуске (когда JSON в SharedPreferences ещё нет) и сразу сохраняются.
    val ttsEnabled: Boolean = true,
    val ttsStartTemplate: String = "",
    val ttsSegmentTemplate: String = "",
    val ttsFinishTemplate: String = "",
    val ttsPauseText: String = "",
    val ttsResumeText: String = ""
) {
    val activeProfile: UserProfile
        get() = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull() ?: UserProfile()
}

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("treadmill_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // [ФИКС] Дефолтные настройки с заполненными из ресурсов TTS-шаблонами.
    private fun defaultWithTts(): AppSettings = AppSettings().copy(
        ttsStartTemplate = context.getString(R.string.tts_default_start),
        ttsSegmentTemplate = context.getString(R.string.tts_default_segment),
        ttsFinishTemplate = context.getString(R.string.tts_default_finish),
        ttsPauseText = context.getString(R.string.tts_default_pause),
        ttsResumeText = context.getString(R.string.tts_default_resume)
    )

    /**
     * [ФИКС] Логика "первый запуск vs последующие" изменилась:
     *  - Если JSON ещё не было (raw == null) — берём дефолты с ресурсами TTS
     *    И СРАЗУ СОХРАНЯЕМ. При следующем запуске это будет обычная загрузка.
     *  - Если JSON есть — доверяем ему как есть, включая пустые строки TTS.
     *    Пустая строка теперь означает "пользователь очистил" — не заполняем.
     *  - Битый JSON — сохраняем в settings_json_broken_<timestamp> и берём дефолты.
     */
    private fun load(): AppSettings {
        val raw = prefs.getString(KEY, null)

        if (raw == null) {
            val defaults = defaultWithTts()
            try {
                prefs.edit().putString(KEY, json.encodeToString(defaults)).apply()
            } catch (_: Exception) { }
            return defaults
        }

        val loaded = try {
            json.decodeFromString<AppSettings>(raw)
        } catch (e: Exception) {
            try {
                val brokenKey = "${KEY}_broken_${System.currentTimeMillis()}"
                prefs.edit().putString(brokenKey, raw).apply()
            } catch (_: Exception) { }
            return defaultWithTts()
        }

        // Миграция профиля с пустым именем
        val fixedProfiles = loaded.profiles.map {
            if (it.name.isBlank()) it.copy(name = context.getString(R.string.profiles_name)) else it
        }
        return loaded.copy(profiles = fixedProfiles)
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit().putString(KEY, json.encodeToString(s)).apply()
    }

    fun updateProfile(profile: UserProfile) {
        update { s ->
            s.copy(profiles = s.profiles.map { if (it.id == profile.id) profile else it })
        }
    }

    fun addProfile(profile: UserProfile) {
        update { s -> s.copy(profiles = s.profiles + profile) }
    }

    fun deleteProfile(id: String) {
        update { s ->
            val newList = s.profiles.filter { it.id != id }
            val newActive = if (s.activeProfileId == id) (newList.firstOrNull()?.id ?: "") else s.activeProfileId
            s.copy(profiles = newList.ifEmpty { listOf(UserProfile()) }, activeProfileId = newActive)
        }
    }

    fun setActiveProfile(id: String) {
        update { s -> s.copy(activeProfileId = id) }
    }

    companion object { private const val KEY = "settings_json" }
}
