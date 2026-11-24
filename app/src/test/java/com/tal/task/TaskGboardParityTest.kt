// SPDX-License-Identifier: GPL-3.0-only
package com.tal.task

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Locale
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
class TaskGboardParityTest {

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    private fun resolveAssetFile(relative: String): File {
        val f1 = File("src/main/assets/$relative")
        if (f1.isFile) return f1
        return File("app/src/main/assets/$relative")
    }

    private fun loadPtLexicon(): TaskGlideDecoder.GlideLexicon =
        resolveAssetFile("dict/pt_br.tsv").bufferedReader(Charsets.UTF_8).use {
            TaskGlideDecoder.GlideLexicon.fromLines(it.lineSequence())
        }

    private fun loadEnLexicon(): TaskGlideDecoder.GlideLexicon =
        resolveAssetFile("dict/en_us.tsv").bufferedReader(Charsets.UTF_8).use {
            TaskGlideDecoder.GlideLexicon.fromLines(it.lineSequence())
        }

    private data class SyntheticQwerty(
        val keyX: FloatArray,
        val keyY: FloatArray,
        val hasKey: BooleanArray,
        val keyW: Float,
        val keyH: Float
    )

    private fun buildSyntheticQwerty(): SyntheticQwerty {
        val keyX = FloatArray(26)
        val keyY = FloatArray(26)
        val hasKey = BooleanArray(26)
        val keyW = 108f
        val keyH = 156f
        val rows = listOf(
            "qwertyuiop" to 0f,
            "asdfghjkl" to 0.5f,
            "zxcvbnm" to 1.5f
        )
        rows.forEachIndexed { rowIdx, (chars, colOffset) ->
            val y = keyH * 0.5f + rowIdx * keyH
            chars.forEachIndexed { colIdx, ch ->
                val idx = ch - 'a'
                hasKey[idx] = true
                keyX[idx] = (colOffset + colIdx + 0.5f) * keyW
                keyY[idx] = y
            }
        }
        return SyntheticQwerty(keyX, keyY, hasKey, keyW, keyH)
    }

    private fun buildSwipePoints(qwerty: SyntheticQwerty, pathLetters: String): Pair<IntArray, IntArray> {
        val xs = ArrayList<Int>(64)
        val ys = ArrayList<Int>(64)
        val stepsPerEdge = 10
        for (i in 0 until pathLetters.length - 1) {
            val c0 = pathLetters[i] - 'a'
            val c1 = pathLetters[i + 1] - 'a'
            val x0 = qwerty.keyX[c0]
            val y0 = qwerty.keyY[c0]
            val x1 = qwerty.keyX[c1]
            val y1 = qwerty.keyY[c1]
            for (s in 0 until stepsPerEdge) {
                val t = s.toFloat() / stepsPerEdge
                val jitterX = if (s % 2 == 0) 4f else -3f
                val jitterY = if (s % 3 == 0) 5f else -4f
                xs.add((x0 + (x1 - x0) * t + jitterX).toInt())
                ys.add((y0 + (y1 - y0) * t + jitterY).toInt())
            }
        }
        val last = pathLetters.last() - 'a'
        xs.add((qwerty.keyX[last] + 3f).toInt())
        ys.add((qwerty.keyY[last] - 4f).toInt())
        return xs.toIntArray() to ys.toIntArray()
    }

    @Test
    fun glideDecodesPtBrAndEnUsAccurately() {
        val qwerty = buildSyntheticQwerty()
        val ptLex = loadPtLexicon()
        val enLex = loadEnLexicon()

        val ptSpec = listOf(
            TaskGlideDecoder.LanguageDecodeSpec(
                locale = Locale("pt", "BR"),
                weightForLocale = 1f,
                sourceDict = Dictionary.DICTIONARY_USER_TYPED,
                isBlacklisted = { false }
            )
        )
        val enSpec = listOf(
            TaskGlideDecoder.LanguageDecodeSpec(
                locale = Locale.US,
                weightForLocale = 1f,
                sourceDict = Dictionary.DICTIONARY_USER_TYPED,
                isBlacklisted = { false }
            )
        )

        val ptCases = listOf(
            "casa" to "casa",
            "voce" to "você",
            "nao" to "não",
            "obrigado" to "obrigado"
        )
        for ((stroke, expected) in ptCases) {
            val (xs, ys) = buildSwipePoints(qwerty, stroke)
            val t0 = System.nanoTime()
            val results = TaskGlideDecoder.decodeWithGeometry(
                xs = xs,
                ys = ys,
                pointCount = xs.size,
                keyCentersX = qwerty.keyX,
                keyCentersY = qwerty.keyY,
                hasKey = qwerty.hasKey,
                keyW = qwerty.keyW,
                keyH = qwerty.keyH,
                specs = ptSpec,
                ptLexicon = ptLex,
                enLexicon = enLex
            )
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            val topWords = results.map { it.word }
            assertTrue(elapsedMs < 40.0, "PT decode '$stroke' took ${elapsedMs}ms (>= 40ms)")
            assertTrue(topWords.take(2).contains(expected), "Expected '$expected' in top-2 for '$stroke', got $topWords")
        }

        val rank1Pt = listOf("casa" to "casa", "nao" to "não")
        for ((stroke, expected) in rank1Pt) {
            val (xs, ys) = buildSwipePoints(qwerty, stroke)
            val results = TaskGlideDecoder.decodeWithGeometry(
                xs, ys, xs.size, qwerty.keyX, qwerty.keyY, qwerty.hasKey, qwerty.keyW, qwerty.keyH,
                ptSpec, ptLex, enLex
            )
            assertEquals(expected, results.firstOrNull()?.word, "Expected #1 '$expected' for '$stroke'")
        }

        val enCases = listOf(
            "helo" to "hello",
            "world" to "world",
            "thanks" to "thanks"
        )
        for ((stroke, expected) in enCases) {
            val (xs, ys) = buildSwipePoints(qwerty, stroke)
            val t0 = System.nanoTime()
            val results = TaskGlideDecoder.decodeWithGeometry(
                xs = xs,
                ys = ys,
                pointCount = xs.size,
                keyCentersX = qwerty.keyX,
                keyCentersY = qwerty.keyY,
                hasKey = qwerty.hasKey,
                keyW = qwerty.keyW,
                keyH = qwerty.keyH,
                specs = enSpec,
                ptLexicon = ptLex,
                enLexicon = enLex
            )
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            val topWords = results.map { it.word }
            assertTrue(elapsedMs < 40.0, "EN decode '$stroke' took ${elapsedMs}ms (>= 40ms)")
            assertTrue(topWords.take(2).contains(expected), "Expected '$expected' in top-2 for '$stroke', got $topWords")
        }

        val rank1En = listOf("helo" to "hello", "world" to "world")
        for ((stroke, expected) in rank1En) {
            val (xs, ys) = buildSwipePoints(qwerty, stroke)
            val results = TaskGlideDecoder.decodeWithGeometry(
                xs, ys, xs.size, qwerty.keyX, qwerty.keyY, qwerty.hasKey, qwerty.keyW, qwerty.keyH,
                enSpec, ptLex, enLex
            )
            assertEquals(expected, results.firstOrNull()?.word, "Expected #1 '$expected' for '$stroke'")
        }
    }

    @Test
    fun glideContextDisambiguationAndMultilingual() {
        val qwerty = buildSyntheticQwerty()
        val ptLex = loadPtLexicon()
        val enLex = loadEnLexicon()

        val bilingualSpecs = listOf(
            TaskGlideDecoder.LanguageDecodeSpec(
                locale = Locale("pt", "BR"),
                weightForLocale = 1f,
                sourceDict = Dictionary.DICTIONARY_USER_TYPED,
                isBlacklisted = { false }
            ),
            TaskGlideDecoder.LanguageDecodeSpec(
                locale = Locale.US,
                weightForLocale = 0.95f,
                sourceDict = Dictionary.DICTIONARY_USER_TYPED,
                isBlacklisted = { false }
            )
        )

        val (diaX, diaY) = buildSwipePoints(qwerty, "dia")
        val diaResults = TaskGlideDecoder.decodeWithGeometry(
            diaX, diaY, diaX.size, qwerty.keyX, qwerty.keyY, qwerty.hasKey, qwerty.keyW, qwerty.keyH,
            bilingualSpecs, ptLex, enLex, prevWord1 = "bom", prevWord2 = null
        )
        assertEquals("dia", diaResults.firstOrNull()?.word, "Expected 'dia' after 'bom'")

        val (vcX, vcY) = buildSwipePoints(qwerty, "voce")
        val vcResults = TaskGlideDecoder.decodeWithGeometry(
            vcX, vcY, vcX.size, qwerty.keyX, qwerty.keyY, qwerty.hasKey, qwerty.keyW, qwerty.keyH,
            bilingualSpecs, ptLex, enLex
        )
        assertTrue(vcResults.take(2).any { it.word == "você" }, "Bilingual should decode PT 'você', got ${vcResults.map { it.word }}")

        val (thxX, thxY) = buildSwipePoints(qwerty, "thanks")
        val thxResults = TaskGlideDecoder.decodeWithGeometry(
            thxX, thxY, thxX.size, qwerty.keyX, qwerty.keyY, qwerty.hasKey, qwerty.keyW, qwerty.keyH,
            bilingualSpecs, ptLex, enLex
        )
        assertTrue(thxResults.take(2).any { it.word == "thanks" }, "Bilingual should decode EN 'thanks', got ${thxResults.map { it.word }}")

        // Tiny micro-tap (< 0.45 * keyW) must return empty
        val microX = intArrayOf(qwerty.keyX[0].toInt(), (qwerty.keyX[0] + 10f).toInt())
        val microY = intArrayOf(qwerty.keyY[0].toInt(), (qwerty.keyY[0] + 5f).toInt())
        val microResults = TaskGlideDecoder.decodeWithGeometry(
            microX, microY, 2, qwerty.keyX, qwerty.keyY, qwerty.hasKey, qwerty.keyW, qwerty.keyH,
            bilingualSpecs, ptLex, enLex
        )
        assertTrue(microResults.isEmpty(), "Tiny micro-tap must return empty glide results")
    }

    @Test
    fun emojiOfflineBilingualSearchAndNoAutocorrect() {
        val lines = resolveAssetFile("dict/emoji_keywords_pt_en.tsv").readLines(Charsets.UTF_8).asSequence()
        val searchDict = TaskEmojiDictionary.createFromLinesForTest(Locale("pt", "BR"), false, lines)
        val exactDict = TaskEmojiDictionary.createFromLinesForTest(
            Locale("pt", "BR"),
            true,
            resolveAssetFile("dict/emoji_keywords_pt_en.tsv").readLines(Charsets.UTF_8).asSequence()
        )
        val sv = SettingsValuesForSuggestion(false, false)
        val dummyWeights = floatArrayOf(-1f)

        fun query(dict: Dictionary, text: String) = dict.getSuggestions(
            ComposedData.createForWord(text),
            NgramContext.EMPTY_PREV_WORDS_INFO,
            0L,
            sv,
            0,
            1f,
            dummyWeights
        ) ?: emptyList()

        assertTrue(query(searchDict, "fogo").any { it.word == "🔥" })
        assertTrue(query(searchDict, "coracao").any { it.word == "❤️" })
        assertTrue(query(searchDict, "coração").any { it.word == "❤️" })
        assertTrue(query(searchDict, "rindo").any { it.word == "😂" })
        assertTrue(query(searchDict, "fire").any { it.word == "🔥" })
        assertTrue(query(searchDict, "rocket").any { it.word == "🚀" })
        assertTrue(query(searchDict, "shrug").any { it.word == "¯\\_(ツ)_/¯" })
        assertTrue(query(searchDict, "").isNotEmpty(), "Search mode empty query should show popular emojis")

        val exactMatches = query(exactDict, "fogo")
        assertTrue(exactMatches.any { it.word == "🔥" })
        for (info in exactMatches) {
            assertEquals(SuggestedWordInfo.KIND_PREDICTION, info.kind)
            assertFalse(info.isAppropriateForAutoCorrection, "Emoji exact suggestion must NEVER auto-correct on Space")
        }
        assertNotNull(searchDict.getWordProperty("🔥", false)?.mShortcutTargets?.firstOrNull()?.mWord)
    }

    @Test
    fun multilingualResolverAndNextWordAndGestureGuard() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val prefs = ctx.prefs()

        prefs.edit { putBoolean(Settings.PREF_MULTILINGUAL_PT_EN, true) }
        val ptSec = TaskMultilingualHelper.resolveSecondaryLocales(prefs, Locale("pt", "BR"), emptyList())
        assertTrue(ptSec.any { it.language == "en" }, "PT main should include EN secondary when bilingual is on")
        val enSec = TaskMultilingualHelper.resolveSecondaryLocales(prefs, Locale.US, emptyList())
        assertTrue(enSec.any { it.language == "pt" }, "EN main should include PT secondary when bilingual is on")
        // Verify zero duplicate main locale
        assertFalse(ptSec.contains(Locale("pt", "BR")))

        prefs.edit { putBoolean(Settings.PREF_MULTILINGUAL_PT_EN, false) }
        val disabledSec = TaskMultilingualHelper.resolveSecondaryLocales(prefs, Locale("pt", "BR"), emptyList())
        assertTrue(disabledSec.isEmpty(), "Disabling toggle should yield empty secondary locales")
        prefs.edit { putBoolean(Settings.PREF_MULTILINGUAL_PT_EN, true) }

        val bomNext = TaskHitboxBridge.getNextWordCandidates(null, "bom", isPt = true, bilingual = true)
        assertTrue(bomNext.contains("dia"), "Expected 'dia' after 'bom', got $bomNext")
        val tudoNext = TaskHitboxBridge.getNextWordCandidates(null, "tudo", isPt = true, bilingual = true)
        assertTrue(tudoNext.contains("bem"), "Expected 'bem' after 'tudo', got $tudoNext")
        val thankNext = TaskHitboxBridge.getNextWordCandidates(null, "thank", isPt = false, bilingual = true)
        assertTrue(thankNext.contains("you"), "Expected 'you' after 'thank', got $thankNext")
        val howAreNext = TaskHitboxBridge.getNextWordCandidates("how", "are", isPt = false, bilingual = true)
        assertTrue(howAreNext.contains("you"), "Expected 'you' after 'how are', got $howAreNext")

        // Verify setInGesture(true) bypasses resolveKey without updating tap telemetry
        val latinIME = Robolectric.setupService(LatinIME::class.java)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.onCreateInputView(latinIME, true)
        switcher.reloadMainKeyboard()
        val kb = switcher.keyboard!!
        val keyA = kb.getKey('a'.code)!!

        TaskHitboxBridge.resetTelemetry()
        TaskHitboxBridge.setInGesture(true)
        val resolvedDuringGesture = TaskHitboxBridge.resolveKey(
            kb,
            keyA.hitBox.centerX(),
            keyA.hitBox.centerY(),
            keyA
        )
        TaskHitboxBridge.setInGesture(false)
        assertEquals(keyA, resolvedDuringGesture)
        assertEquals(0, TaskHitboxBridge.getTelemetrySnapshot(latinIME).totalTaps, "Gesture taps must not pollute tap telemetry")
    }
}
