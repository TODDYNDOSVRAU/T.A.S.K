// SPDX-License-Identifier: GPL-3.0-only
package com.tal.task

import android.content.ComponentCallbacks2
import android.content.Context
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyPreviewChoreographer
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [
        ShadowInputMethodManager2::class,
        ShadowInputMethodService::class,
        ShadowLooper::class
    ]
)
class TaskV32AuditTest {

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    private fun awaitPtTrieLoaded(ctx: Context) {
        TaskHitboxBridge.onFieldStarted(ctx, EditorInfo(), "pt-BR")
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            if (TaskHitboxBridge.getTelemetrySnapshot(ctx).estimatedTrieRamKb > 0) return
            Thread.sleep(10L)
        }
    }

    @Test
    fun defaultVisualizerOffAndDeferredTapCommitAndLatencyP99() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("task_hitbox_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TaskHitboxBridge::class.java.getDeclaredField("prefsLoaded").apply {
            isAccessible = true
            setBoolean(TaskHitboxBridge, false)
        }

        val latinIME = Robolectric.setupService(LatinIME::class.java)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.onCreateInputView(latinIME, true)
        switcher.reloadMainKeyboard()
        val kb = assertNotNull(switcher.keyboard)
        awaitPtTrieLoaded(latinIME)

        // D13: Default visualizer mode is strictly OFF (2D zones run invisibly on real keyboard)
        assertEquals(
            TaskHitboxBridge.VisualizerMode.OFF,
            TaskHitboxBridge.getTelemetrySnapshot(latinIME).visualizerMode
        )

        // D17: ACTION_DOWN resolveKey must NOT increment totalTaps or calibrationSamples until ACTION_UP onTapCommitted
        TaskHitboxBridge.resetTelemetry()
        TaskHitboxBridge.resetThumbCalibration(latinIME)
        TaskHitboxBridge.updateFullTextContext(latinIME, "ca", "pt-BR")

        val keyS = assertNotNull(kb.getKey('s'.code))
        val resolved = TaskHitboxBridge.resolveKey(
            kb,
            keyS.hitBox.centerX() + 2,
            keyS.hitBox.centerY() + 3,
            keyS
        )
        assertNotNull(resolved)
        val afterDown = TaskHitboxBridge.getTelemetrySnapshot(latinIME)
        assertEquals(0, afterDown.totalTaps, "ACTION_DOWN resolveKey must not mutate committed tap count")
        assertEquals(0, afterDown.calibrationSamples, "ACTION_DOWN resolveKey must not mutate EMA calibration")

        TaskHitboxBridge.onTapCommitted(resolved.code)
        val afterUp = TaskHitboxBridge.getTelemetrySnapshot(latinIME)
        assertEquals(1, afterUp.totalTaps, "ACTION_UP onTapCommitted must commit tap telemetry")
        assertEquals(1, afterUp.calibrationSamples, "ACTION_UP onTapCommitted must commit EMA sample")

        // Cancelled / mismatched commit code must discard pending tap without calibrating
        TaskHitboxBridge.resolveKey(kb, keyS.hitBox.centerX(), keyS.hitBox.centerY(), keyS)
        TaskHitboxBridge.onTapCommitted(KeyCode.DELETE)
        val afterCancel = TaskHitboxBridge.getTelemetrySnapshot(latinIME)
        assertEquals(1, afterCancel.totalTaps, "Cancelled/overridden tap must not increment totalTaps")
        assertEquals(1, afterCancel.calibrationSamples, "Cancelled/overridden tap must not pollute EMA")

        // Warm 1,000-iteration resolveKey microbenchmark: p99 < 0.50 ms
        val samplesNs = LongArray(1000)
        val cx = keyS.hitBox.centerX()
        val cy = keyS.hitBox.centerY()
        repeat(100) { TaskHitboxBridge.resolveKey(kb, cx, cy, keyS) }
        for (i in samplesNs.indices) {
            val t0 = System.nanoTime()
            TaskHitboxBridge.resolveKey(kb, cx + (i and 3), cy - (i and 3), keyS)
            samplesNs[i] = System.nanoTime() - t0
        }
        samplesNs.sort()
        val p99Ms = samplesNs[990] / 1_000_000.0
        assertTrue(p99Ms < 0.50, "Expected warm resolveKey p99 < 0.50 ms, got ${"%.4f".format(p99Ms)} ms")
    }

    @Test
    fun spaceAndBackspaceBorderRescueAndLowerSpaceGuard() {
        val latinIME = Robolectric.setupService(LatinIME::class.java)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.onCreateInputView(latinIME, true)
        switcher.reloadMainKeyboard()
        val kb = assertNotNull(switcher.keyboard)
        awaitPtTrieLoaded(latinIME)
        TaskHitboxBridge.resetThumbCalibration(latinIME)

        val spaceKey = assertNotNull(kb.getKey(Constants.CODE_SPACE))
        val keyN = assertNotNull(kb.getKey('n'.code))
        val deleteKey = assertNotNull(kb.getKey(KeyCode.DELETE))
        val keyM = assertNotNull(kb.getKey('m'.code))

        val topSpaceX = keyN.hitBox.centerX().coerceIn(spaceKey.hitBox.left + 4, spaceKey.hitBox.right - 4)
        val topSpaceY = spaceKey.hitBox.top + (spaceKey.hitBox.height() * 0.10f).toInt()
        val midSpaceY = spaceKey.hitBox.top + (spaceKey.hitBox.height() * 0.55f).toInt()

        // Empty prefix (foldedPrefixLen == 0) must NEVER rescue top of Space
        TaskHitboxBridge.updateFullTextContext(latinIME, "", "pt-BR")
        assertEquals(
            spaceKey,
            TaskHitboxBridge.resolveKey(kb, topSpaceX, topSpaceY, spaceKey),
            "Space top border must never rescue when prefix is empty"
        )

        // Prefix 'importa' makes 'n' highly probable (>= 0.18f): top 18% rescues to 'n', bottom 82% stays Space
        TaskHitboxBridge.updateFullTextContext(latinIME, "importa", "pt-BR")
        val rescuedFromSpace = TaskHitboxBridge.resolveKey(kb, topSpaceX, topSpaceY, spaceKey)
        assertEquals(keyN.code, rescuedFromSpace?.code, "Top 18% of Space under 'n' after 'importa' must rescue to 'n'")

        val lowerSpaceResult = TaskHitboxBridge.resolveKey(kb, topSpaceX, midSpaceY, spaceKey)
        assertEquals(spaceKey.code, lowerSpaceResult?.code, "Lower 82% of Space must always remain Space")

        // Prefix 'tambe' makes 'm' overwhelmingly probable (>= 0.18f): left 15% of Backspace rescues to 'm'
        TaskHitboxBridge.updateFullTextContext(latinIME, "tambe", "pt-BR")
        val leftDelX = deleteKey.hitBox.left + (deleteKey.hitBox.width() * 0.08f).toInt()
        val rightDelX = deleteKey.hitBox.left + (deleteKey.hitBox.width() * 0.55f).toInt()
        val delY = deleteKey.hitBox.centerY()

        val rescuedFromDelete = TaskHitboxBridge.resolveKey(kb, leftDelX, delY, deleteKey)
        assertEquals(keyM.code, rescuedFromDelete?.code, "Left 15% of Backspace after 'tambe' must rescue to 'm'")

        val centerDeleteResult = TaskHitboxBridge.resolveKey(kb, rightDelX, delY, deleteKey)
        assertEquals(deleteKey.code, centerDeleteResult?.code, "Right 85% of Backspace must remain DELETE")
    }

    @Test
    fun packedGlideLexiconHasZeroStringArraysAndTrimsOnUiHidden() {
        val fields = TaskGlideDecoder.GlideLexicon::class.java.declaredFields
        val stringArrayFields = fields.filter { it.type == Array<String>::class.java }
        assertTrue(
            stringArrayFields.isEmpty(),
            "GlideLexicon must have 0 Array<String> fields on heap, found: ${stringArrayFields.map { it.name }}"
        )

        val latinIME = Robolectric.setupService(LatinIME::class.java)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.onCreateInputView(latinIME, true)
        switcher.reloadMainKeyboard()
        val kb = assertNotNull(switcher.keyboard)

        val keyC = assertNotNull(kb.getKey('c'.code))
        val keyA = assertNotNull(kb.getKey('a'.code))
        val keyS = assertNotNull(kb.getKey('s'.code))
        val xs = intArrayOf(keyC.hitBox.centerX(), keyA.hitBox.centerX(), keyS.hitBox.centerX(), keyA.hitBox.centerX())
        val ys = intArrayOf(keyC.hitBox.centerY(), keyA.hitBox.centerY(), keyS.hitBox.centerY(), keyA.hitBox.centerY())
        val pids = intArrayOf(0, 0, 0, 0)
        val spec = listOf(
            TaskGlideDecoder.LanguageDecodeSpec(
                locale = Locale("pt", "BR"),
                weightForLocale = 1f,
                sourceDict = Dictionary.DICTIONARY_USER_TYPED,
                isBlacklisted = { false }
            )
        )

        val beforeTrim = TaskGlideDecoder.decode(latinIME, xs, ys, pids, xs.size, kb, spec, null, null, false)
        assertTrue(beforeTrim.any { it.word == "casa" }, "Expected packed Glide decoder to decode 'casa'")

        val ptField = TaskGlideDecoder::class.java.getDeclaredField("ptLexicon").apply { isAccessible = true }
        assertNotNull(ptField.get(TaskGlideDecoder), "ptLexicon should be loaded before trim")

        TaskGlideDecoder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertNull(ptField.get(TaskGlideDecoder), "TRIM_MEMORY_UI_HIDDEN must release packed ptLexicon")

        val afterTrim = TaskGlideDecoder.decode(latinIME, xs, ys, pids, xs.size, kb, spec, null, null, false)
        assertTrue(afterTrim.any { it.word == "casa" }, "Glide decoder must reload cleanly after trim")
    }

    @Test
    fun imeHonorsAnimatorDurationScaleZero() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        AndroidSettings.Global.putFloat(ctx.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 0.0f)
        assertTrue(
            KeyPreviewChoreographer.isImeReduceMotionEnabled(ctx),
            "IME must honor ANIMATOR_DURATION_SCALE == 0f (D16 accessibility exception)"
        )

        AndroidSettings.Global.putFloat(ctx.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
        assertFalse(
            KeyPreviewChoreographer.isImeReduceMotionEnabled(ctx),
            "IME animations must remain active when ANIMATOR_DURATION_SCALE > 0f"
        )
    }

    @Test
    fun bottomPunctuationAndHintsDefaultOnAndToggleWorks() {
        val latinIME = Robolectric.setupService(LatinIME::class.java)
        val prefs = latinIME.prefs()
        prefs.edit {
            remove(Settings.PREF_SHOW_BOTTOM_PUNCTUATION)
            remove(Settings.PREF_SHOW_EMOJI_KEY)
            remove(Settings.PREF_SHOW_HINTS)
            remove(Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD)
        }

        assertTrue(Defaults.PREF_SHOW_BOTTOM_PUNCTUATION, "Default PREF_SHOW_BOTTOM_PUNCTUATION must be true (D20)")
        assertTrue(Defaults.PREF_SHOW_HINTS, "Default PREF_SHOW_HINTS must be true (D20)")
        assertTrue(Defaults.PREF_SHOW_EMOJI_KEY, "Default PREF_SHOW_EMOJI_KEY must be true (D20)")
        assertFalse(Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, "Default double-space period must be false (D22)")
        assertEquals("", Defaults.PREF_ADDITIONAL_SUBTYPES, "Default PREF_ADDITIONAL_SUBTYPES must be empty (D19)")

        Settings.getInstance().loadSettings(
            latinIME,
            latinIME.resources.configuration.locale,
            InputAttributes(EditorInfo(), false, latinIME.packageName)
        )
        val switcher = KeyboardSwitcher.getInstance()
        switcher.onCreateInputView(latinIME, true)
        switcher.setThemeNeedsReload()
        switcher.reloadMainKeyboard()
        val defaultKb = assertNotNull(switcher.keyboard)

        assertNotNull(defaultKb.getKey(Constants.CODE_SPACE), "Default bottom row must contain Space")
        assertNotNull(defaultKb.getKey(','.code), "Default bottom row must contain comma key (D20)")
        assertNotNull(defaultKb.getKey('.'.code), "Default bottom row must contain period key (D20)")

        // Disabling toggle hides dedicated ',' and '.' on bottom row
        prefs.edit { putBoolean(Settings.PREF_SHOW_BOTTOM_PUNCTUATION, false) }
        Settings.getInstance().loadSettings(
            latinIME,
            latinIME.resources.configuration.locale,
            InputAttributes(EditorInfo(), false, latinIME.packageName)
        )
        switcher.setThemeNeedsReload()
        switcher.reloadMainKeyboard()
        val minimalKb = assertNotNull(switcher.keyboard)
        assertNull(minimalKb.getKey(','.code), "Disabling PREF_SHOW_BOTTOM_PUNCTUATION must omit comma key")
        assertNull(minimalKb.getKey('.'.code), "Disabling PREF_SHOW_BOTTOM_PUNCTUATION must omit period key")

        // Restore defaults
        prefs.edit { putBoolean(Settings.PREF_SHOW_BOTTOM_PUNCTUATION, true) }
    }
}
