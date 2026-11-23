package com.tal.task.engine

enum class Language(val code: String, val label: String) {
    PT_BR("pt-BR", "PT"),
    EN_US("en-US", "EN")
}

enum class HitboxSensitivity(
    val label: String,
    val gain: Float,
    val maxExpansion: Float
) {
    SOFT("Suave", gain = 0.55f, maxExpansion = 0.24f),
    STANDARD("Padrão", gain = 1.00f, maxExpansion = 0.34f),
    AGGRESSIVE("Agressivo", gain = 1.35f, maxExpansion = 0.40f)
}

enum class VisualizerMode(val label: String) {
    OFF("OFF"),
    OVERLAY("OVERLAY"),
    ENGINEERING("ENG");

    fun next(): VisualizerMode = when (this) {
        OFF -> OVERLAY
        OVERLAY -> ENGINEERING
        ENGINEERING -> OFF
    }
}

enum class KeyboardLayer {
    ALPHA_LOWER,
    ALPHA_UPPER,
    ALPHA_CAPS,
    SYMBOLS,
    SYMBOLS_EXTENDED,
    NUMBER_PAD
}

enum class EnterAction(val glyph: String, val isPrimaryAction: Boolean) {
    NEWLINE("↵", false),
    SEARCH("⌕", true),
    SEND("➤", true),
    GO("→", true),
    NEXT("⇥", false),
    DONE("✓", true)
}

sealed class KeyAction {
    data class Character(val char: Char) : KeyAction()
    data object Shift : KeyAction()
    data object Backspace : KeyAction()
    data object Space : KeyAction()
    data object Enter : KeyAction()
    data object ToggleSymbols : KeyAction()
    data object ToggleExtendedSymbols : KeyAction()
    data object SwitchLanguage : KeyAction()
    data object ToggleEmoji : KeyAction()
    data object ToggleClipboard : KeyAction()
}

data class KeySpec(
    val id: String,
    val label: String,
    val action: KeyAction,
    val weight: Float = 1f,
    val accents: List<Char> = emptyList(),
    val isSpecial: Boolean = false,
    val hintLabel: String? = null
)

data class KeyRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    fun containsCore(x: Float, y: Float, coreRatio: Float): Boolean {
        val halfCoreW = width * coreRatio * 0.5f
        val halfCoreH = height * coreRatio * 0.5f
        return x >= (centerX - halfCoreW) &&
            x <= (centerX + halfCoreW) &&
            y >= (centerY - halfCoreH) &&
            y <= (centerY + halfCoreH)
    }
}

data class LayoutKey(
    val index: Int,
    val row: Int,
    val spec: KeySpec,
    val rect: KeyRect
) {
    val letterChar: Char?
        get() = (spec.action as? KeyAction.Character)?.char?.lowercaseChar()?.takeIf { it in 'a'..'z' }
}

data class PredictedKeyState(
    val keyIndex: Int,
    val char: Char?,
    val probability: Float,
    val expansionFactor: Float,
    val visualRect: KeyRect
)

data class HitboxSnapshot(
    val keys: List<LayoutKey>,
    val predictedStates: List<PredictedKeyState>,
    val dynamicEnabled: Boolean,
    val sensitivity: HitboxSensitivity,
    val prefix: String,
    val computeLatencyMs: Double
)

data class TouchResolution(
    val touchX: Float,
    val touchY: Float,
    val geometricKeyIndex: Int,
    val resolvedKeyIndex: Int,
    val geometricChar: Char?,
    val resolvedChar: Char?,
    val resolvedProbability: Float,
    val wasRescued: Boolean,
    val protectedByCore: Boolean,
    val latencyMs: Double
)

data class WordSuggestion(
    val word: String,
    val score: Double,
    val isAutoCorrect: Boolean = false
)
