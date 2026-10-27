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
    val activeProfileId: String = profiles.first().id,

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

    // TTS — пустые по умолчанию, SettingsStore.load() заполнит из ресурсов
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

    private fun load(): AppSettings {
        val loaded = try {
            prefs.getString(KEY, null)?.let { json.decodeFromString<AppSettings>(it) } ?: AppSettings()
        } catch (_: Exception) {
            AppSettings()
        }
        // Заполняем TTS-шаблоны из ресурсов, если пусты
        val withDefaults = loaded.copy(
            ttsStartTemplate = loaded.ttsStartTemplate.ifBlank { context.getString(R.string.tts_default_start) },
            ttsSegmentTemplate = loaded.ttsSegmentTemplate.ifBlank { context.getString(R.string.tts_default_segment) },
            ttsFinishTemplate = loaded.ttsFinishTemplate.ifBlank { context.getString(R.string.tts_default_finish) },
            ttsPauseText = loaded.ttsPauseText.ifBlank { context.getString(R.string.tts_default_pause) },
            ttsResumeText = loaded.ttsResumeText.ifBlank { context.getString(R.string.tts_default_resume) }
        )
        // Миграция профиля с пустым именем
        val fixedProfiles = withDefaults.profiles.map {
            if (it.name.isBlank()) it.copy(name = context.getString(R.string.profiles_name)) else it
        }
        return withDefaults.copy(profiles = fixedProfiles)
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
