package com.tal.task.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.tal.task.data.LocalClipboardStore
import com.tal.task.data.TaskPreferences
import com.tal.task.data.TaskTelemetry
import com.tal.task.data.ThemeMode
import com.tal.task.data.UserDictionaryStore
import com.tal.task.engine.BundledCorpus
import com.tal.task.engine.Language
import com.tal.task.engine.TrieDictionary
import com.tal.task.engine.VisualizerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * T.A.S.K. Android InputMethodService.
 * Enforces D7/D8 privacy & field guards:
 * - Zero INTERNET permission.
 * - Blocks local learning & clipboard capture on PASSWORD / NO_PERSONALIZED_LEARNING fields.
 * - Disables dynamic hitbox expansion on URI / Email / Password fields.
 */
class TaskInputMethodService : InputMethodService(), KeyboardHostCallbacks {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: TaskPreferences
    private lateinit var userDictStore: UserDictionaryStore
    private lateinit var dictionaries: Map<Language, TrieDictionary>

    private var keyboardView: TaskKeyboardView? = null
    private var clipboardManager: ClipboardManager? = null

    private var currentPrefix: String = ""
    private var previousWord: String? = null
    private var isIncognitoField: Boolean = false
    private var isDynamicAllowedForField: Boolean = true

    // Undo state for backspace right after an autocorrection
    private var lastAutoCorrectRaw: String? = null
    private var lastAutoCorrectCommitted: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isIncognitoField) {
            TaskTelemetry.recordIncognitoBlock()
            return@OnPrimaryClipChangedListener
        }
        val text = clipboardManager?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrBlank()) {
            LocalClipboardStore.push(text, incognito = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = TaskPreferences(this)
        userDictStore = UserDictionaryStore(this)
        dictionaries = mapOf(
            Language.PT_BR to BundledCorpus.createDictionary(Language.PT_BR),
            Language.EN_US to BundledCorpus.createDictionary(Language.EN_US)
        )
        dictionaries.values.forEach { userDictStore.loadInto(it) }

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onCreateInputView(): View {
        val view = TaskKeyboardView(this).apply {
            callbacks = this@TaskInputMethodService
            bindDictionaries(dictionaries)
        }
        keyboardView = view

        serviceScope.launch {
            prefs.config.collect { cfg ->
                view.applyConfig(cfg, resolveDarkTheme(cfg.themeMode))
            }
        }
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val policy = evaluateFieldPolicy(info)
        isIncognitoField = policy.incognito
        isDynamicAllowedForField = policy.dynamicAllowed
        if (isIncognitoField) TaskTelemetry.recordIncognitoBlock()

        currentPrefix = ""
        lastAutoCorrectRaw = null
        lastAutoCorrectCommitted = null
        syncContextFromInputConnection()

        keyboardView?.apply {
            applyConfig(prefs.config.value, resolveDarkTheme(prefs.config.value.themeMode))
            updateFieldPolicy(isDynamicAllowedForField, isIncognitoField)
            updateComposingContext(currentPrefix, previousWord)
        }
    }

    override fun onCommitText(text: String) {
        val ic = currentInputConnection ?: return
        lastAutoCorrectRaw = null
        lastAutoCorrectCommitted = null
        ic.commitText(text, 1)

        if (text.length == 1 && text[0].isLetter()) {
            currentPrefix += text
        } else {
            if (currentPrefix.length >= 2 && !isIncognitoField) {
                val lang = prefs.config.value.language
                dictionaries[lang]?.learnWord(currentPrefix, previousWord)
                userDictStore.recordWord(currentPrefix, lang, incognito = false)
                previousWord = currentPrefix
            }
            currentPrefix = ""
        }
        keyboardView?.updateComposingContext(currentPrefix, previousWord)
    }

    override fun onCommitWordSuggestion(word: String, replaceLength: Int) {
        val ic = currentInputConnection ?: return
        val rawBefore = currentPrefix
        if (replaceLength > 0) {
            ic.deleteSurroundingText(replaceLength, 0)
        }
        val committed = "$word "
        ic.commitText(committed, 1)

        lastAutoCorrectRaw = rawBefore.takeIf { it.isNotEmpty() && !it.equals(word, ignoreCase = true) }
        lastAutoCorrectCommitted = committed

        if (!isIncognitoField) {
            val lang = prefs.config.value.language
            dictionaries[lang]?.learnWord(word, previousWord)
            userDictStore.recordWord(word, lang, incognito = false)
        }
        previousWord = word
        currentPrefix = ""
        keyboardView?.updateComposingContext(currentPrefix, previousWord)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return

        // Undo last autocorrection on immediate backspace (D6)
        val raw = lastAutoCorrectRaw
        val committed = lastAutoCorrectCommitted
        if (raw != null && committed != null) {
            ic.deleteSurroundingText(committed.length, 0)
            ic.commitText(raw, 1)
            currentPrefix = raw
            lastAutoCorrectRaw = null
            lastAutoCorrectCommitted = null
            keyboardView?.updateComposingContext(currentPrefix, previousWord)
            return
        }

        ic.deleteSurroundingText(1, 0)
        if (currentPrefix.isNotEmpty()) {
            currentPrefix = currentPrefix.dropLast(1)
        } else {
            syncContextFromInputConnection()
        }
        keyboardView?.updateComposingContext(currentPrefix, previousWord)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val actionId = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(actionId)
        } else {
            ic.commitText("\n", 1)
        }
        currentPrefix = ""
        previousWord = null
        keyboardView?.updateComposingContext(currentPrefix, previousWord)
    }

    override fun onMoveCursor(delta: Int) {
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(delta).coerceAtMost(6)) {
            sendDownUpKeyEvents(keyCode)
        }
        syncContextFromInputConnection()
        keyboardView?.updateComposingContext(currentPrefix, previousWord)
    }

    override fun onVisualizerModeChanged(mode: VisualizerMode) {
        prefs.update { it.copy(visualizerMode = mode) }
    }

    override fun onLanguageSwitched(language: Language) {
        prefs.update { it.copy(language = language) }
    }

    private fun syncContextFromInputConnection() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        if (before.isEmpty()) {
            currentPrefix = ""
            return
        }
        var idx = before.length - 1
        while (idx >= 0 && before[idx].isLetter()) idx--
        currentPrefix = before.substring(idx + 1)

        val trimmedLeft = before.substring(0, (idx + 1).coerceAtLeast(0)).trimEnd()
        previousWord = trimmedLeft.takeLastWhile { it.isLetter() }.takeIf { it.isNotEmpty() }
    }

    private fun resolveDarkTheme(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> {
            val nightFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            nightFlags == Configuration.UI_MODE_NIGHT_YES
        }
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        prefs.release()
        userDictStore.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    data class FieldPolicy(
        val incognito: Boolean,
        val dynamicAllowed: Boolean
    )

    companion object {
        /**
         * Pure policy helper (unit-testable): enforces D7/D8 rules for Password, URI, Email, Incognito.
         */
        fun evaluateFieldPolicy(info: EditorInfo?): FieldPolicy {
            if (info == null) return FieldPolicy(incognito = false, dynamicAllowed = true)
            val inputType = info.inputType
            val clazz = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION

            val isPassword = clazz == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ) || (clazz == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

            val isNoPersonalLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

            val isUriOrEmail = clazz == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )

            val incognito = isPassword || isNoPersonalLearning
            val dynamicAllowed = !isPassword && !isUriOrEmail
            return FieldPolicy(incognito = incognito, dynamicAllowed = dynamicAllowed)
        }
    }
}
