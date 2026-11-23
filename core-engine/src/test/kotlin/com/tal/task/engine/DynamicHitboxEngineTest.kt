package com.tal.task.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class DynamicHitboxEngineTest {

    private val engine = DynamicHitboxEngine()
    private val ptDict = BundledCorpus.createDictionary(Language.PT_BR)
    private val enDict = BundledCorpus.createDictionary(Language.EN_US)

    private fun buildStandardKeys(language: Language = Language.PT_BR): List<LayoutKey> {
        val rows = KeyboardLayoutSpec.buildRows(
            layer = KeyboardLayer.ALPHA_LOWER,
            language = language,
            showNumberRow = false
        )
        return KeyboardLayoutSpec.layoutKeys(
            rows = rows,
            totalWidth = 1080f,
            totalHeight = 720f,
            horizontalPadding = 24f,
            verticalPadding = 16f,
            keyGapX = 12f,
            keyGapY = 18f
        )
    }

    @Test
    fun `D8 hard expansion cap never exceeds +40 percent even on AGGRESSIVE`() {
        val keys = buildStandardKeys(Language.PT_BR)
        val snapshot = engine.computeSnapshot(
            keys = keys,
            dictionary = ptDict,
            prefix = "teclad",
            prevWord = null,
            sensitivity = HitboxSensitivity.AGGRESSIVE,
            dynamicEnabled = true
        )

        for (state in snapshot.predictedStates) {
            assertTrue(
                "Expansion ${state.expansionFactor} exceeded hard cap 0.40",
                state.expansionFactor <= DynamicHitboxEngine.HARD_MAX_EXPANSION + 1e-6f
            )
        }
    }

    @Test
    fun `D8 core protection guarantees 100 percent accuracy inside every key core even at zero probability`() {
        val keys = buildStandardKeys(Language.PT_BR)
        // Prefix "teclad" strongly predicts 'o' (teclado); test every single letter key's protected core
        val snapshot = engine.computeSnapshot(
            keys = keys,
            dictionary = ptDict,
            prefix = "teclad",
            prevWord = null,
            sensitivity = HitboxSensitivity.AGGRESSIVE,
            dynamicEnabled = true
        )

        var testedPoints = 0
        for (key in keys) {
            val ch = key.letterChar ?: continue
            val offsets = listOf(
                0f to 0f,
                -0.25f to 0f,
                0.25f to 0f,
                0f to -0.25f,
                0f to 0.25f,
                -0.22f to -0.22f,
                0.22f to 0.22f
            )
            for ((ox, oy) in offsets) {
                val tx = key.rect.centerX + key.rect.width * ox
                val ty = key.rect.centerY + key.rect.height * oy
                val res = engine.resolveTouch(tx, ty, snapshot)
                assertEquals(
                    "Core protection failed for key '$ch' at offset ($ox, $oy)",
                    key.index,
                    res.resolvedKeyIndex
                )
                assertTrue("Expected protectedByCore=true", res.protectedByCore)
                assertFalse("Core hit must never be marked rescued", res.wasRescued)
                testedPoints++
            }
        }
        assertTrue("Expected > 150 core protection checks", testedPoints > 150)
    }

    @Test
    fun `D7 dynamic hitbox reduces off-center spatial noise error rate by at least 25 percent`() {
        val keys = buildStandardKeys(Language.PT_BR)
        val keyByChar = keys.mapNotNull { k -> k.letterChar?.let { it to k } }.toMap()
        val testWords = listOf(
            "teclado", "predicao", "precisao", "sistema", "projeto", "trabalho",
            "configuracao", "informacao", "mensagem", "aplicativo", "interface",
            "dinamico", "sugestao", "dicionario", "portugues", "privacidade"
        )

        val rng = Random(42)
        var staticErrors = 0
        var dynamicErrors = 0
        var totalTaps = 0

        for (word in testWords) {
            for (i in 1 until word.length) {
                val prefix = word.substring(0, i)
                val targetChar = word[i]
                val targetKey = keyByChar[targetChar] ?: continue
                val snapshot = engine.computeSnapshot(
                    keys = keys,
                    dictionary = ptDict,
                    prefix = prefix,
                    prevWord = null,
                    sensitivity = HitboxSensitivity.STANDARD,
                    dynamicEnabled = true
                )
                val staticSnapshot = engine.computeSnapshot(
                    keys = keys,
                    dictionary = ptDict,
                    prefix = prefix,
                    prevWord = null,
                    sensitivity = HitboxSensitivity.STANDARD,
                    dynamicEnabled = false
                )

                // Simulate 8 off-center touches displaced 52%..74% of key width (crossing edge into neighbor)
                repeat(8) {
                    val angle = rng.nextDouble(0.0, 2.0 * PI)
                    val radiusFraction = rng.nextDouble(0.54, 0.72).toFloat()
                    val tx = targetKey.rect.centerX + (cos(angle) * targetKey.rect.width * radiusFraction).toFloat()
                    val ty = targetKey.rect.centerY + (sin(angle) * targetKey.rect.height * radiusFraction * 0.65f).toFloat()

                    val staticRes = engine.resolveTouch(tx, ty, staticSnapshot)
                    val dynamicRes = engine.resolveTouch(tx, ty, snapshot)

                    if (staticRes.resolvedChar != targetChar) staticErrors++
                    if (dynamicRes.resolvedChar != targetChar) dynamicErrors++
                    totalTaps++
                }
            }
        }

        val reduction = (staticErrors - dynamicErrors).toDouble() / staticErrors.coerceAtLeast(1)
        println("Spatial noise benchmark: taps=$totalTaps staticErrors=$staticErrors dynamicErrors=$dynamicErrors reduction=${"%.1f".format(reduction * 100)}%")
        assertTrue("Static errors should be > 0 for meaningful benchmark", staticErrors > 20)
        assertTrue(
            "Expected >= 25% error reduction, got ${"%.1f".format(reduction * 100)}%",
            reduction >= 0.25
        )
    }

    @Test
    fun `D7 engine p99 latency is strictly at most 2_0 ms`() {
        val keys = buildStandardKeys(Language.PT_BR)
        val prefixes = listOf("", "t", "te", "tec", "tecl", "tecla", "p", "pr", "pre", "pred", "co", "con", "conf")
        val latencies = ArrayList<Double>(600)

        // Warmup JIT
        repeat(100) { idx ->
            val snap = engine.computeSnapshot(keys, ptDict, prefixes[idx % prefixes.size], "bom", HitboxSensitivity.STANDARD, true)
            engine.resolveTouch(540f, 300f, snap)
        }

        // Measure 500 full cycles (snapshot + touch resolution + 3 word suggestions)
        repeat(500) { idx ->
            val prefix = prefixes[idx % prefixes.size]
            val t0 = System.nanoTime()
            val snap = engine.computeSnapshot(keys, ptDict, prefix, "bom", HitboxSensitivity.STANDARD, true)
            engine.resolveTouch(410f + (idx % 120), 220f + (idx % 90), snap)
            ptDict.suggestWords(prefix, "bom", limit = 3)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            latencies += elapsedMs
        }

        latencies.sort()
        val p50 = latencies[(latencies.size * 0.50).toInt()]
        val p99 = latencies[(latencies.size * 0.99).toInt()]
        println("Engine benchmark (500 cycles): p50=${"%.4f".format(p50)} ms, p99=${"%.4f".format(p99)} ms")
        assertTrue("Expected p99 <= 2.0 ms, got $p99 ms", p99 <= 2.0)
    }

    @Test
    fun `PT-BR accent restoration and EN-US bigram suggestions work offline`() {
        val ptSuggestions = ptDict.suggestWords("nao", prevWord = null, limit = 3)
        assertTrue("Expected 'não' in suggestions, got $ptSuggestions", ptSuggestions.any { it.word == "não" })

        val enSuggestions = enDict.suggestWords("", prevWord = "thank", limit = 3)
        assertEquals("you", enSuggestions.firstOrNull()?.word)

        // Local learning boosts custom word
        ptDict.learnWord("tarkas", prevWord = "projeto")
        val learned = ptDict.suggestWords("tark", prevWord = "projeto", limit = 3)
        assertNotNull(learned.firstOrNull { it.word == "tarkas" })
    }
}
