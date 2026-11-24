// SPDX-License-Identifier: GPL-3.0-only
package com.tal.task

import android.content.Context
import android.graphics.RectF
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [
        ShadowInputMethodManager2::class,
        ShadowInputMethodService::class,
        ShadowLooper::class
    ]
)
class TaskV33ErrorProofTest {

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    private fun setupFreshKeyboard(): Pair<LatinIME, helium314.keyboard.keyboard.Keyboard> {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("task_hitbox_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val latinIME = Robolectric.setupService(LatinIME::class.java)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.onCreateInputView(latinIME, true)
        switcher.reloadMainKeyboard()
        val kb = assertNotNull(switcher.keyboard)
        TaskHitboxBridge.onFieldStarted(latinIME, EditorInfo(), "pt-BR")
        TaskHitboxBridge.resetThumbCalibration(latinIME)
        TaskHitboxBridge.resetTelemetry()
        return latinIME to kb
    }

    @Test
    fun perKeyLeftBiasOnEGrowsELeftBoundary() {
        val (latinIME, kb) = setupFreshKeyboard()
        val keyE = assertNotNull(kb.getKey('e'.code))
        val keyW = assertNotNull(kb.getKey('w'.code))

        val baselineE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', baselineE))

        val tapX = (keyE.hitBox.exactCenterX() - keyE.hitBox.width() * 0.24f).toInt()
        val tapY = keyE.hitBox.centerY()
        repeat(12) {
            TaskHitboxBridge.updateFullTextContext(latinIME, "", "pt-BR")
            TaskHitboxBridge.resolveKey(kb, tapX, tapY, keyE)
            TaskHitboxBridge.onTapCommitted('e'.code)
        }

        val adaptedE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', adaptedE))
        assertTrue(
            adaptedE.left < baselineE.left - 2f,
            "Repeated left-of-center taps on E must grow E's left boundary leftward (was ${baselineE.left}, now ${adaptedE.left})"
        )
        // Must respect neighbor W's protected central core (52% -> half-core = 0.26 * w)
        assertTrue(
            adaptedE.left >= keyW.hitBox.exactCenterX() + keyW.hitBox.width() * 0.25f,
            "E growth must never invade W's protected central core"
        )
    }

    @Test
    fun backspaceCorrectionPairTeachesNeighborAndConfusion() {
        val (latinIME, kb) = setupFreshKeyboard()
        val keyW = assertNotNull(kb.getKey('w'.code))
        val keyE = assertNotNull(kb.getKey('e'.code))

        val baselineE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', baselineE))

        TaskHitboxBridge.updateFullTextContext(latinIME, "t", "pt-BR")
        // Tap inside the right half of W's 52% protected core so W is committed before user corrects to E
        val nearRightCoreW = (keyW.hitBox.exactCenterX() + keyW.hitBox.width() * 0.18f).toInt()
        val tapY = keyW.hitBox.centerY()
        val resolvedW = TaskHitboxBridge.resolveKey(kb, nearRightCoreW, tapY, keyW)
        assertEquals('w'.code, resolvedW?.code, "Touch inside W protected core must resolve W before correction")
        TaskHitboxBridge.onTapCommitted('w'.code)

        TaskHitboxBridge.onBackspaceCommitted()

        val resolvedE = TaskHitboxBridge.resolveKey(kb, keyE.hitBox.centerX(), tapY, keyE)
        assertEquals('e'.code, resolvedE?.code)
        TaskHitboxBridge.onTapCommitted('e'.code)

        val snap = TaskHitboxBridge.getTelemetrySnapshot(latinIME)
        assertTrue(snap.learnedCorrectionPairs >= 1, "Backspace + neighbor tap must record correction pair")

        val adaptedE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', adaptedE))
        assertTrue(
            adaptedE.left < baselineE.left,
            "Mistyping W then backspacing to E must expand E's left hitbox toward W"
        )
    }

    @Test
    fun autocorrectSingleCharDiffLearnsPerKeyOffset() {
        val (latinIME, kb) = setupFreshKeyboard()
        val baselineE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', baselineE))

        val chars = "twste"
        for (i in chars.indices) {
            val ch = chars[i]
            val k = assertNotNull(kb.getKey(ch.code))
            val tx = if (i == 1) (k.hitBox.exactCenterX() + k.hitBox.width() * 0.18f).toInt() else k.hitBox.centerX()
            val resolved = TaskHitboxBridge.resolveKey(kb, tx, k.hitBox.centerY(), k)
            assertEquals(ch.code, resolved?.code, "Key '$ch' core tap should resolve '$ch'")
            TaskHitboxBridge.onTapCommitted(ch.code)
        }
        TaskHitboxBridge.onWordCommitted("twste", "teste")

        val snap = TaskHitboxBridge.getTelemetrySnapshot(latinIME)
        assertTrue(snap.learnedCorrectionPairs >= 1, "Single-char autocorrect diff must learn correction pair")

        val adaptedE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', adaptedE))
        assertTrue(
            adaptedE.left < baselineE.left,
            "Autocorrect 'twste'->'teste' must expand E's left hitbox toward W"
        )
    }

    @Test
    fun autocorrectRevertShieldBlocksAfterTwoReverts() {
        setupFreshKeyboard()
        assertFalse(TaskHitboxBridge.shouldBlockAutoCorrection("vc"))

        TaskHitboxBridge.onAutocorrectReverted("vc", "você")
        assertFalse(
            TaskHitboxBridge.shouldBlockAutoCorrection("vc"),
            "Single revert must not prematurely shield"
        )

        TaskHitboxBridge.onAutocorrectReverted("vc", "você")
        assertTrue(
            TaskHitboxBridge.shouldBlockAutoCorrection("vc"),
            "Second revert must activate Autocorrect Revert Shield for 'vc'"
        )
        assertFalse(TaskHitboxBridge.shouldBlockAutoCorrection("casa"))
    }

    @Test
    fun periodAndCommaInnerEdgeGuardPreventsAccidentalDot() {
        val (latinIME, _) = setupFreshKeyboard()
        val prefs = latinIME.prefs()
        prefs.edit { putBoolean(Settings.PREF_SHOW_BOTTOM_PUNCTUATION, true) }
        Settings.getInstance().loadSettings(
            latinIME,
            latinIME.resources.configuration.locale,
            InputAttributes(EditorInfo(), false, latinIME.packageName)
        )
        val switcher = KeyboardSwitcher.getInstance()
        switcher.setThemeNeedsReload()
        switcher.reloadMainKeyboard()
        val kb = assertNotNull(switcher.keyboard)

        val keyA = assertNotNull(kb.getKey('a'.code))
        val dotKey = assertNotNull(kb.getKey('.'.code))

        // Simulate active continuous typing ("ola") so foldedPrefixLen >= 2 and lastAlphaTapTimeMs is fresh
        TaskHitboxBridge.updateFullTextContext(latinIME, "ola", "pt-BR")
        TaskHitboxBridge.resolveKey(kb, keyA.hitBox.centerX(), keyA.hitBox.centerY(), keyA)
        TaskHitboxBridge.onTapCommitted('a'.code)

        val innerDotX = dotKey.hitBox.left + (dotKey.hitBox.width() * 0.12f).toInt()
        val outerDotX = dotKey.hitBox.left + (dotKey.hitBox.width() * 0.65f).toInt()
        val dotY = dotKey.hitBox.centerY()

        val innerResolved = TaskHitboxBridge.resolveKey(kb, innerDotX, dotY, dotKey)
        assertEquals(
            Constants.CODE_SPACE,
            innerResolved?.code,
            "Inner left 26% edge of '.' during fast mid-word typing must rescue to Space"
        )

        val centerResolved = TaskHitboxBridge.resolveKey(kb, outerDotX, dotY, dotKey)
        assertEquals(
            '.'.code,
            centerResolved?.code,
            "Center/outer 74% of '.' must stay period"
        )
    }

    @Test
    fun emeraldAndTalDarkThemesConfiguredAndVoicePinned() {
        val latinIME = Robolectric.setupService(LatinIME::class.java)
        val prefs = latinIME.prefs()
        assertTrue(
            defaultPinnedToolbarPref.contains("${ToolbarKey.VOICE.name}${Separators.KV}true"),
            "Voice mic must be pinned by default on top-right of strip"
        )

        prefs.edit {
            putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_TASK_EMERALD)
            putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_TASK_EMERALD)
        }
        val emeraldColors = KeyboardTheme.getColorsForCurrentTheme(latinIME)
        assertEquals(0xFF000000.toInt(), emeraldColors.get(ColorType.MAIN_BACKGROUND))
        assertEquals(0xFF084D3D.toInt(), emeraldColors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
        assertEquals(0xFFA3F0D6.toInt(), emeraldColors.get(ColorType.GESTURE_TRAIL))

        // Restore T.A.L. Dark/Light defaults
        prefs.edit {
            putString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
            putString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
        }
    }

    @Test
    fun revertedAutocorrectRollsBackPoisonedPerKeyLearning() {
        val (latinIME, kb) = setupFreshKeyboard()

        val chars = "twste"
        for (i in chars.indices) {
            val ch = chars[i]
            val k = assertNotNull(kb.getKey(ch.code))
            val tx = if (i == 1) (k.hitBox.exactCenterX() + k.hitBox.width() * 0.18f).toInt() else k.hitBox.centerX()
            TaskHitboxBridge.resolveKey(kb, tx, k.hitBox.centerY(), k)
            TaskHitboxBridge.onTapCommitted(ch.code)
        }
        val preCommitE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', preCommitE))

        TaskHitboxBridge.onWordCommitted("twste", "teste")
        val afterCommitPairs = TaskHitboxBridge.getTelemetrySnapshot(latinIME).learnedCorrectionPairs
        assertEquals(1, afterCommitPairs)

        val postCommitE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', postCommitE))
        assertTrue(
            postCommitE.left < preCommitE.left,
            "onWordCommitted must shift E's left boundary leftward before revert"
        )

        // Immediately reverting the autocorrect must undo poisoned per-key directional growth
        TaskHitboxBridge.onAutocorrectReverted("twste", "teste")
        val afterRevertPairs = TaskHitboxBridge.getTelemetrySnapshot(latinIME).learnedCorrectionPairs
        assertEquals(0, afterRevertPairs, "Revert must roll back learnedCorrectionPairs")

        val restoredE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', restoredE))
        assertEquals(
            preCommitE.left,
            restoredE.left,
            0.05f,
            "Revert must restore E's left boundary so rejected autocorrects do not poison hitboxes"
        )
    }

    @Test
    fun multiBackspaceStackTrainsAfterDeletingTwoChars() {
        val (latinIME, kb) = setupFreshKeyboard()
        val keyW = assertNotNull(kb.getKey('w'.code))
        val keyS = assertNotNull(kb.getKey('s'.code))
        val keyE = assertNotNull(kb.getKey('e'.code))

        val baselineE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', baselineE))

        val wTapX = (keyW.hitBox.exactCenterX() + keyW.hitBox.width() * 0.18f).toInt()
        TaskHitboxBridge.resolveKey(kb, wTapX, keyW.hitBox.centerY(), keyW)
        TaskHitboxBridge.onTapCommitted('w'.code)

        TaskHitboxBridge.resolveKey(kb, keyS.hitBox.centerX(), keyS.hitBox.centerY(), keyS)
        TaskHitboxBridge.onTapCommitted('s'.code)

        // Delete 2 chars ('s' then 'w') before retyping 'e'
        TaskHitboxBridge.onBackspaceCommitted()
        TaskHitboxBridge.onBackspaceCommitted()

        TaskHitboxBridge.resolveKey(kb, keyE.hitBox.centerX(), keyE.hitBox.centerY(), keyE)
        TaskHitboxBridge.onTapCommitted('e'.code)

        val snap = TaskHitboxBridge.getTelemetrySnapshot(latinIME)
        assertTrue(snap.learnedCorrectionPairs >= 1, "Multi-backspace stack must match the erased 'w' tap")

        val adaptedE = RectF()
        assertTrue(TaskHitboxBridge.getTargetZoneCopyForTest(kb, 'e', adaptedE))
        assertTrue(
            adaptedE.left < baselineE.left,
            "Retyping E after deleting 'w'+'s' must expand E leftward toward W"
        )
    }

    @Test
    fun verbatimWordPreferredContributesToRevertShield() {
        setupFreshKeyboard()
        assertFalse(TaskHitboxBridge.shouldBlockAutoCorrection("tarkas"))

        TaskHitboxBridge.onVerbatimWordPreferred("tarkas")
        assertFalse(TaskHitboxBridge.shouldBlockAutoCorrection("tarkas"))

        TaskHitboxBridge.onAutocorrectReverted("tarkas", "tarjas")
        assertTrue(
            TaskHitboxBridge.shouldBlockAutoCorrection("tarkas"),
            "1 manual verbatim pick + 1 autocorrect revert must activate revert shield"
        )
    }
}
