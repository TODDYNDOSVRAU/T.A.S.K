package com.tal.task.data

import android.content.Context
import android.content.SharedPreferences
import com.tal.task.engine.HitboxSensitivity
import com.tal.task.engine.Language
import com.tal.task.engine.VisualizerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("Sistema"),
    DARK("Escuro"),
    LIGHT("Claro")
}

data class TaskConfig(
    val language: Language = Language.PT_BR,
    val sensitivity: HitboxSensitivity = HitboxSensitivity.STANDARD,
    val visualizerMode: VisualizerMode = VisualizerMode.OVERLAY,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val showNumberRow: Boolean = false,
    val autoCorrect: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val showKeyPreview: Boolean = true
)

class TaskPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("task_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<TaskConfig> = _config.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _config.value = readConfig()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun readConfig(): TaskConfig = TaskConfig(
        language = Language.entries.getOrElse(prefs.getInt(KEY_LANG, 0)) { Language.PT_BR },
        sensitivity = HitboxSensitivity.entries.getOrElse(prefs.getInt(KEY_SENSITIVITY, 1)) { HitboxSensitivity.STANDARD },
        visualizerMode = VisualizerMode.entries.getOrElse(prefs.getInt(KEY_VISUALIZER, 1)) { VisualizerMode.OVERLAY },
        themeMode = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 1)) { ThemeMode.DARK },
        showNumberRow = prefs.getBoolean(KEY_NUMBER_ROW, false),
        autoCorrect = prefs.getBoolean(KEY_AUTOCORRECT, true),
        hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
        showKeyPreview = prefs.getBoolean(KEY_PREVIEW, true)
    )

    fun update(transform: (TaskConfig) -> TaskConfig) {
        val next = transform(_config.value)
        prefs.edit()
            .putInt(KEY_LANG, next.language.ordinal)
            .putInt(KEY_SENSITIVITY, next.sensitivity.ordinal)
            .putInt(KEY_VISUALIZER, next.visualizerMode.ordinal)
            .putInt(KEY_THEME, next.themeMode.ordinal)
            .putBoolean(KEY_NUMBER_ROW, next.showNumberRow)
            .putBoolean(KEY_AUTOCORRECT, next.autoCorrect)
            .putBoolean(KEY_HAPTICS, next.hapticsEnabled)
            .putBoolean(KEY_PREVIEW, next.showKeyPreview)
            .apply()
        _config.value = next
    }

    fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val KEY_LANG = "lang"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_VISUALIZER = "visualizer"
        private const val KEY_THEME = "theme"
        private const val KEY_NUMBER_ROW = "number_row"
        private const val KEY_AUTOCORRECT = "autocorrect"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_PREVIEW = "key_preview"
    }
}
