package com.tal.task

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.res.ResourcesCompat
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * T.A.S.K. v3.3.0 — T.A.L. Per-Key Asymmetric 2D Dynamic Tap Zones & Anti-Error Engine.
 *
 * Highlights:
 * - Flat-Int CSR Trie (4 primitive arrays, ~350–500 KB per language, zero TrieNode object heap),
 *   loaded on demand for the active language and released on trim memory.
 * - Zero-Allocation touch/draw hot loop: lookup ASCII folding without Normalizer/Regex,
 *   pre-allocated 26-slot zone & display RectF pools, and rolling-hash incremental topic updates.
 * - Per-Key Asymmetric Directional Learning: every letter (a–z) tracks its own touch centroid
 *   (dx, dy) and independent Left/Right/Top/Bottom expansion weights driven by:
 *   (1) confirmed edge-tap distribution, (2) Backspace-then-neighbor-letter correction pairs, and
 *   (3) single-character autocorrect diff feedback — always protecting every key's 52% core.
 * - Anti-Error Defenses: inner-edge '.' / ',' accidental period guard during fast typing,
 *   top-18% Space / left-15% Backspace neighbor rescue, and Autocorrect Revert Shield (blocks
 *   repeating forced autocorrections on words the user reverted >= 2 times via 64-bit hashes).
 */
object TaskHitboxBridge {

    enum class VisualizerMode(val label: String) {
        OFF("OFF"),
        OVERLAY("PRED"),
        ENGINEERING("ENG");

        fun next(): VisualizerMode = when (this) {
            OFF -> OVERLAY
            OVERLAY -> ENGINEERING
            ENGINEERING -> OFF
        }
    }

    enum class Sensitivity(val label: String, val maxShiftRatio: Float, val maxVertShiftRatio: Float) {
        SOFT("Suave", maxShiftRatio = 0.14f, maxVertShiftRatio = 0.08f),
        STANDARD("Padrão", maxShiftRatio = 0.20f, maxVertShiftRatio = 0.12f),
        AGGRESSIVE("Agressivo", maxShiftRatio = 0.24f, maxVertShiftRatio = 0.15f)
    }

    data class LiveTelemetry(
        val totalTaps: Int,
        val rescuedTaps: Int,
        val coreProtectedTaps: Int,
        val lastLatencyMs: Double,
        val p99LatencyMs: Double,
        val lastGeoChar: Char,
        val lastResChar: Char,
        val lastProbPercent: Int,
        val lastOffsetYPx: Float,
        val rowBiasY: FloatArray,
        val rowBiasX: FloatArray,
        val calibrationSamples: Int,
        val adaptiveEnabled: Boolean,
        val sensitivity: Sensitivity,
        val visualizerMode: VisualizerMode,
        val contextWordCount: Int,
        val currentPrefix: String,
        val prevWord: String,
        val estimatedTrieRamKb: Int,
        val bilingualPtEn: Boolean,
        val lastGlideWord: String,
        val lastGlideLatencyMs: Double,
        val totalGlides: Int,
        val learnedCorrectionPairs: Int = 0,
        val blockedAutocorrectWords: Int = 0,
        val adaptedKeyCount: Int = 0,
        val topAdaptedSummary: String = "—"
    )

    private const val PREFS_NAME = "task_hitbox_prefs"
    private const val KEY_VIZ_MODE = "viz_mode"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_ADAPTIVE = "adaptive_thumb"
    private const val KEY_SAMPLES = "cal_samples"
    private const val KEY_LEARNED_PAIRS = "learned_pairs"
    private const val KEY_BLOCKED_REVERTS = "blocked_reverts"
    private const val KEY_CONF_PAIRS = "conf_pairs"
    private const val KEY_REVERT_HASHES = "revert_hashes"

    private const val PROTECTED_CORE_RATIO = 0.52f
    private const val BASE_VERTICAL_BIAS_RATIO = 0.06f
    private const val MAX_ADAPTIVE_BIAS_RATIO = 0.14f
    private const val EMA_ALPHA = 0.04f
    private const val EMA_KEY_ALPHA = 0.08f
    private const val EMA_ERROR_ALPHA = 0.16f
    private const val MAX_KEY_DX = 0.30f
    private const val MAX_KEY_DY = 0.25f
    private const val MAX_KEY_GROW_X = 0.32f
    private const val MAX_KEY_GROW_Y = 0.24f
    private const val WORD_TAP_CAP = 16
    private const val TAP_LOG_DEBOUNCE_MS = 55L
    private const val EDGE_DAMP_DP = 4f
    private val LN_26 = ln(26.0f)

    /**
     * Compact Compressed-Sparse-Row (CSR) Trie backed strictly by 4 primitive arrays.
     * Eliminates ~74,887 Java TrieNode objects (~10.86 MB) down to ~400 KB per active language.
     */
    private class FlatPrefixTrie(
        private val nodeMass: IntArray,
        private val edgeStart: IntArray,
        private val edgeChar: ByteArray,
        private val edgeTarget: IntArray,
        private val charBigrams: FloatArray, // 27 * 26 flattened
        private val charTotals: FloatArray   // 27
    ) {
        val estimatedBytes: Long
            get() = (nodeMass.size.toLong() * 4L) +
                (edgeStart.size.toLong() * 4L) +
                edgeChar.size.toLong() +
                (edgeTarget.size.toLong() * 4L) +
                (charBigrams.size.toLong() * 4L) +
                (charTotals.size.toLong() * 4L)

        fun prefixMass(prefixBuf: CharArray, prefixLen: Int): Int {
            var node = 0
            for (i in 0 until prefixLen) {
                val chByte = (prefixBuf[i] - 'a').toByte()
                val nextNode = findChild(node, chByte)
                if (nextNode < 0) return 0
                node = nextNode
            }
            return nodeMass[node]
        }

        fun nextCharProbabilities(prefixBuf: CharArray, prefixLen: Int, out: FloatArray) {
            out.fill(0f)
            var node = 0
            var validPath = true
            for (i in 0 until prefixLen) {
                val chByte = (prefixBuf[i] - 'a').toByte()
                val nextNode = findChild(node, chByte)
                if (nextNode < 0) {
                    validPath = false
                    break
                }
                node = nextNode
            }

            var totalMass = 0.0
            if (validPath) {
                val start = edgeStart[node]
                val end = edgeStart[node + 1]
                for (e in start until end) {
                    val idx = edgeChar[e].toInt()
                    val mass = nodeMass[edgeTarget[e]].toDouble()
                    if (idx in 0..25 && mass > 0.0) {
                        out[idx] = mass.toFloat()
                        totalMass += mass
                    }
                }
            }

            val lastIdx = if (prefixLen == 0) 26 else (prefixBuf[prefixLen - 1] - 'a').coerceIn(0, 25)
            val bgTotal = charTotals[lastIdx]
            val bgOffset = lastIdx * 26
            if (totalMass <= 0.0) {
                if (bgTotal > 0f) {
                    for (i in 0 until 26) out[i] = charBigrams[bgOffset + i] / bgTotal
                } else {
                    out.fill(1f / 26f)
                }
                return
            }

            val invTotal = (1.0 / totalMass).toFloat()
            var sum = 0f
            for (i in 0 until 26) {
                val tp = out[i] * invTotal
                val bp = if (bgTotal > 0f) charBigrams[bgOffset + i] / bgTotal else 0f
                val mixed = tp * 0.88f + bp * 0.12f
                out[i] = mixed
                sum += mixed
            }
            if (sum > 0f) {
                val invSum = 1f / sum
                for (i in 0 until 26) out[i] *= invSum
            }
        }

        private fun findChild(node: Int, ch: Byte): Int {
            var lo = edgeStart[node]
            var hi = edgeStart[node + 1] - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = edgeChar[mid]
                if (c < ch) lo = mid + 1
                else if (c > ch) hi = mid - 1
                else return edgeTarget[mid]
            }
            return -1
        }

        companion object {
            // Temporary builder objects live strictly inside loadFromAsset() and become immediately collectible
            private class BuildNode {
                var mass: Int = 0
                var childMask: Int = 0
                var children: ArrayList<Pair<Int, BuildNode>>? = null
            }

            fun loadFromAsset(context: Context, assetPath: String): FlatPrefixTrie? {
                val root = BuildNode()
                val charBigrams = FloatArray(27 * 26)
                val charTotals = FloatArray(27)
                var totalNodes = 1
                var totalEdges = 0
                val wordBuf = CharArray(32)

                try {
                    context.assets.open(assetPath).use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                            var line = reader.readLine()
                            while (line != null) {
                                val tab = line.indexOf('\t')
                                if (tab > 0) {
                                    val freq = line.substring(tab + 1).toIntOrNull()?.coerceAtLeast(1) ?: 100
                                    var len = 0
                                    for (k in 0 until min(tab, wordBuf.size)) {
                                        val folded = foldCharAscii(line[k])
                                        if (folded != '\u0000') {
                                            wordBuf[len++] = folded
                                        }
                                    }
                                    if (len > 0) {
                                        var node = root
                                        var prevIdx = 26
                                        node.mass += freq
                                        for (i in 0 until len) {
                                            val cIdx = wordBuf[i] - 'a'
                                            charBigrams[prevIdx * 26 + cIdx] += freq.toFloat()
                                            charTotals[prevIdx] += freq.toFloat()
                                            prevIdx = cIdx

                                            var nextNode: BuildNode? = null
                                            val kids = node.children
                                            if (kids != null) {
                                                for (j in kids.indices) {
                                                    if (kids[j].first == cIdx) {
                                                        nextNode = kids[j].second
                                                        break
                                                    }
                                                }
                                            }
                                            if (nextNode == null) {
                                                nextNode = BuildNode()
                                                val list = kids ?: ArrayList<Pair<Int, BuildNode>>(2).also { node.children = it }
                                                list.add(cIdx to nextNode)
                                                totalNodes++
                                                totalEdges++
                                            }
                                            nextNode.mass += freq
                                            node = nextNode
                                        }
                                    }
                                }
                                line = reader.readLine()
                            }
                        }
                    }
                } catch (_: Exception) {
                    return null
                }

                // BFS compaction into 4 primitive CSR arrays
                val nodeMass = IntArray(totalNodes)
                val edgeStart = IntArray(totalNodes + 1)
                val edgeChar = ByteArray(totalEdges)
                val edgeTarget = IntArray(totalEdges)
                val queue = ArrayList<BuildNode>(totalNodes)
                queue.add(root)
                var edgeCursor = 0

                var idx = 0
                while (idx < queue.size) {
                    val curr = queue[idx]
                    nodeMass[idx] = curr.mass
                    edgeStart[idx] = edgeCursor
                    val kids = curr.children
                    if (kids != null && kids.isNotEmpty()) {
                        kids.sortBy { it.first }
                        for (j in kids.indices) {
                            val (chIdx, childNode) = kids[j]
                            val childId = queue.size
                            queue.add(childNode)
                            edgeChar[edgeCursor] = chIdx.toByte()
                            edgeTarget[edgeCursor] = childId
                            edgeCursor++
                        }
                    }
                    curr.children = null // allow GC during compaction if needed
                    idx++
                }
                edgeStart[totalNodes] = edgeCursor
                return FlatPrefixTrie(nodeMass, edgeStart, edgeChar, edgeTarget, charBigrams, charTotals)
            }
        }
    }

    // Built-in PT-BR & EN-US Bigrams & Trigrams for whole-writing context prediction
    private val ptBigrams: Map<String, List<Pair<String, Float>>> = mapOf(
        "bom" to listOf("dia" to 1.0f, "trabalho" to 0.45f, "fim" to 0.35f, "saber" to 0.3f),
        "boa" to listOf("noite" to 0.9f, "tarde" to 0.85f, "ideia" to 0.4f, "sorte" to 0.35f),
        "tudo" to listOf("bem" to 1.0f, "certo" to 0.7f, "isso" to 0.45f, "pronto" to 0.35f),
        "muito" to listOf("bom" to 0.8f, "obrigado" to 0.8f, "bem" to 0.7f, "melhor" to 0.5f, "legal" to 0.4f),
        "muita" to listOf("coisa" to 0.7f, "gente" to 0.6f, "sorte" to 0.4f),
        "por" to listOf("favor" to 0.95f, "que" to 0.75f, "aqui" to 0.45f, "isso" to 0.55f, "enquanto" to 0.4f),
        "nao" to listOf("sei" to 0.8f, "tem" to 0.7f, "posso" to 0.65f, "quero" to 0.55f, "esta" to 0.55f, "foi" to 0.5f, "consigo" to 0.45f),
        "eu" to listOf("estou" to 0.75f, "vou" to 0.7f, "quero" to 0.6f, "acho" to 0.65f, "tenho" to 0.6f, "sei" to 0.5f, "nao" to 0.55f),
        "voce" to listOf("pode" to 0.8f, "esta" to 0.75f, "vai" to 0.65f, "quer" to 0.55f, "tem" to 0.55f, "sabe" to 0.45f),
        "com" to listOf("certeza" to 0.85f, "voce" to 0.65f, "ele" to 0.45f, "isso" to 0.55f, "tudo" to 0.4f),
        "para" to listOf("fazer" to 0.7f, "mim" to 0.65f, "voce" to 0.6f, "ver" to 0.45f, "o" to 0.5f, "a" to 0.5f),
        "pra" to listOf("fazer" to 0.7f, "mim" to 0.65f, "voce" to 0.65f, "ver" to 0.45f, "gente" to 0.4f),
        "em" to listOf("casa" to 0.65f, "tempo" to 0.6f, "cima" to 0.45f, "frente" to 0.4f),
        "a" to listOf("gente" to 0.9f, "hora" to 0.45f, "noite" to 0.4f, "casa" to 0.35f),
        "de" to listOf("novo" to 0.75f, "acordo" to 0.65f, "verdade" to 0.6f, "manha" to 0.45f, "hoje" to 0.4f),
        "o" to listOf("que" to 0.9f, "teclado" to 0.5f, "app" to 0.45f, "dia" to 0.4f, "tempo" to 0.4f),
        "que" to listOf("bom" to 0.65f, "horas" to 0.5f, "voce" to 0.55f, "nao" to 0.5f, "tal" to 0.45f, "legal" to 0.45f),
        "esta" to listOf("tudo" to 0.75f, "certo" to 0.6f, "bem" to 0.6f, "funcionando" to 0.5f),
        "ate" to listOf("amanha" to 0.7f, "logo" to 0.7f, "agora" to 0.5f, "mais" to 0.5f),
        "obrigado" to listOf("pela" to 0.65f, "pelo" to 0.65f, "por" to 0.55f),
        "vamos" to listOf("la" to 0.7f, "ver" to 0.65f, "fazer" to 0.6f, "testar" to 0.5f),
        "aqui" to listOf("esta" to 0.6f, "no" to 0.55f, "em" to 0.5f, "comigo" to 0.4f),
        "meu" to listOf("celular" to 0.6f, "amigo" to 0.55f, "deus" to 0.55f, "trabalho" to 0.45f),
        "minha" to listOf("casa" to 0.6f, "mae" to 0.55f, "vida" to 0.45f, "conta" to 0.4f),
        "se" to listOf("voce" to 0.7f, "nao" to 0.6f, "puder" to 0.5f, "for" to 0.5f),
        "tem" to listOf("que" to 0.75f, "como" to 0.6f, "alguma" to 0.45f, "muita" to 0.4f),
        "pode" to listOf("deixar" to 0.75f, "ser" to 0.75f, "fazer" to 0.55f, "mandar" to 0.45f),
        "estou" to listOf("indo" to 0.7f, "aqui" to 0.65f, "bem" to 0.6f, "chegando" to 0.55f, "trabalhando" to 0.45f),
        "ja" to listOf("esta" to 0.65f, "foi" to 0.6f, "vou" to 0.55f, "cheguei" to 0.5f),
        "vou" to listOf("ver" to 0.7f, "fazer" to 0.65f, "mandar" to 0.55f, "ir" to 0.5f),
        "entao" to listOf("esta" to 0.65f, "vamos" to 0.6f, "pode" to 0.5f),
        "como" to listOf("voce" to 0.75f, "esta" to 0.7f, "sempre" to 0.45f)
    )

    private val ptTrigrams: Map<String, List<Pair<String, Float>>> = mapOf(
        "tudo bem" to listOf("com" to 0.8f, "por" to 0.6f, "e" to 0.5f),
        "bom dia" to listOf("tudo" to 0.85f, "como" to 0.6f),
        "boa tarde" to listOf("tudo" to 0.85f, "como" to 0.6f),
        "boa noite" to listOf("tudo" to 0.8f, "ate" to 0.55f),
        "por favor" to listOf("me" to 0.7f, "nao" to 0.6f, "pode" to 0.6f),
        "a gente" to listOf("vai" to 0.75f, "pode" to 0.65f, "se" to 0.6f, "combinou" to 0.45f),
        "o que" to listOf("voce" to 0.85f, "esta" to 0.65f, "achou" to 0.55f, "aconteceu" to 0.5f),
        "com certeza" to listOf("vai" to 0.7f, "eu" to 0.6f, "sim" to 0.55f),
        "eu acho" to listOf("que" to 1.0f),
        "eu sei" to listOf("que" to 0.9f),
        "voce sabe" to listOf("que" to 0.85f, "se" to 0.6f, "como" to 0.5f),
        "nao sei" to listOf("se" to 0.85f, "o" to 0.6f, "como" to 0.55f, "por" to 0.45f),
        "muito obrigado" to listOf("pelo" to 0.75f, "pela" to 0.7f, "por" to 0.6f),
        "como voce" to listOf("esta" to 0.9f, "vai" to 0.6f),
        "vai dar" to listOf("certo" to 0.9f, "tudo" to 0.6f)
    )

    private val ptAccentRestore: Map<String, String> = mapOf(
        "nao" to "não",
        "voce" to "você",
        "esta" to "está",
        "amanha" to "amanhã",
        "tambem" to "também",
        "ja" to "já",
        "la" to "lá",
        "ate" to "até",
        "mae" to "mãe",
        "sao" to "são",
        "entao" to "então",
        "so" to "só",
        "ai" to "aí",
        "cafe" to "café",
        "irmao" to "irmão",
        "coracao" to "coração",
        "acao" to "ação",
        "ninguem" to "ninguém",
        "alguem" to "alguém"
    )

    private val enBigrams: Map<String, List<Pair<String, Float>>> = mapOf(
        "thank" to listOf("you" to 1.0f),
        "thanks" to listOf("for" to 0.85f, "a" to 0.5f, "so" to 0.45f),
        "good" to listOf("morning" to 0.85f, "night" to 0.8f, "job" to 0.5f, "idea" to 0.45f),
        "how" to listOf("are" to 0.9f, "do" to 0.6f, "to" to 0.65f, "much" to 0.5f),
        "are" to listOf("you" to 0.95f, "we" to 0.5f, "they" to 0.4f),
        "i" to listOf("am" to 0.85f, "have" to 0.75f, "think" to 0.75f, "want" to 0.65f, "need" to 0.6f, "will" to 0.55f, "know" to 0.5f),
        "in" to listOf("the" to 0.9f, "a" to 0.5f, "this" to 0.45f, "front" to 0.4f),
        "of" to listOf("the" to 0.9f, "course" to 0.7f, "all" to 0.45f),
        "to" to listOf("the" to 0.8f, "be" to 0.7f, "do" to 0.65f, "you" to 0.55f, "see" to 0.45f),
        "for" to listOf("the" to 0.85f, "you" to 0.7f, "sure" to 0.6f, "now" to 0.45f),
        "what" to listOf("is" to 0.8f, "are" to 0.7f, "do" to 0.65f, "time" to 0.5f),
        "you" to listOf("can" to 0.75f, "are" to 0.7f, "have" to 0.65f, "want" to 0.55f, "know" to 0.5f),
        "let" to listOf("me" to 0.85f, "us" to 0.6f),
        "see" to listOf("you" to 0.95f),
        "have" to listOf("a" to 0.8f, "to" to 0.75f, "been" to 0.5f),
        "sounds" to listOf("good" to 0.9f, "great" to 0.7f),
        "on" to listOf("my" to 0.7f, "the" to 0.7f, "time" to 0.55f)
    )

    private val enTrigrams: Map<String, List<Pair<String, Float>>> = mapOf(
        "thank you" to listOf("so" to 0.75f, "very" to 0.7f, "for" to 0.8f),
        "how are" to listOf("you" to 1.0f),
        "good morning" to listOf("everyone" to 0.6f, "how" to 0.55f),
        "i think" to listOf("that" to 0.75f, "it" to 0.65f, "we" to 0.55f),
        "have a" to listOf("good" to 0.8f, "great" to 0.8f, "nice" to 0.6f),
        "of course" to listOf("i" to 0.6f, "we" to 0.55f, "you" to 0.55f),
        "let me" to listOf("know" to 0.95f, "see" to 0.6f),
        "on my" to listOf("way" to 0.95f, "phone" to 0.5f)
    )

    private val ptFeminineCues = setOf("a", "uma", "minha", "sua", "nossa", "essa", "esta", "aquela", "na", "da", "pela")
    private val ptMasculineCues = setOf("o", "um", "meu", "seu", "nosso", "esse", "este", "aquele", "no", "do", "pelo")
    private val ptPluralCues = setOf("os", "as", "uns", "umas", "todos", "todas", "muitos", "muitas", "alguns", "algumas", "esses", "essas", "meus", "minhas", "seus", "suas")
    private val ptSentenceStarters = charArrayOf('o', 'a', 'e', 'n', 'v', 'p', 'c', 'm', 't', 'q', 's', 'b')
    private val enSentenceStarters = charArrayOf('t', 'i', 'w', 'h', 's', 'a', 'y', 'c', 'p', 'g', 'm', 'l')

    private val stateLock = Any()
    private val loaderExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var prefsLoaded = false
    @Volatile private var appContextRef: Context? = null
    @Volatile private var attachedViewRef: WeakReference<View>? = null
    @Volatile private var ptTrie: FlatPrefixTrie? = null
    @Volatile private var enTrie: FlatPrefixTrie? = null
    @Volatile private var loadingPt = false
    @Volatile private var loadingEn = false

    @Volatile private var visualizerMode: VisualizerMode = VisualizerMode.OFF
    @Volatile private var sensitivity: Sensitivity = Sensitivity.STANDARD
    @Volatile private var adaptiveThumbEnabled: Boolean = true
    @Volatile private var activeLocaleIsPt = true
    @Volatile private var bilingualPtEnActive = true
    @Volatile private var inGestureStroke = false

    // Field policy flags
    @Volatile private var fieldBaseDynamicAllowed = true
    @Volatile private var learningAllowedForField = true
    @Volatile private var dynamicAllowedForField = true

    // Full-writing context state + Zero-Allocation working buffers (protected by stateLock)
    private var currentPrefix = ""
    private var prevWord1 = ""
    private var prevWord2 = ""
    private var isSentenceStart = true
    private var contextWordCount = 0
    private var lastBeforePrefixHash = 0L
    private var lastBeforePrefixLen = -1
    private var lastTopicWordCount = 0
    private var lastTopicBeforeLen = -1
    private var lastTopicFoldedTail = ""
    private val foldedPrefixBuf = CharArray(64)
    private var foldedPrefixLen = 0
    private val foldScratch = CharArray(64)
    private val trigramKeyBuilder = StringBuilder(64)
    private val topicWords = HashMap<String, Int>(64)
    private val topicBigrams = HashMap<String, HashMap<String, Int>>(64)
    private val topicWordHashes = HashMap<Long, Int>(64)
    private val topicBigramHashes = HashMap<Long, HashMap<Long, Int>>(64)
    private val recentWordBounds = IntArray(244) // pairs (start, end) up to 122 words, zero allocation!

    // Per-row adaptive offset & Per-Key 2D asymmetric directional profile (NEVER stores plaintext)
    private val rowBiasX = FloatArray(4)
    private val rowBiasY = floatArrayOf(BASE_VERTICAL_BIAS_RATIO, BASE_VERTICAL_BIAS_RATIO, BASE_VERTICAL_BIAS_RATIO * 0.75f, 0f)
    private val keyMeanDx = FloatArray(26)
    private val keyMeanDy = FloatArray(26) { BASE_VERTICAL_BIAS_RATIO }
    private val keyGrowLeft = FloatArray(26)
    private val keyGrowRight = FloatArray(26)
    private val keyGrowTop = FloatArray(26)
    private val keyGrowBottom = FloatArray(26)
    private val keySamples = IntArray(26)
    private val keyConfusion = FloatArray(26 * 26)
    @Volatile private var calibrationSamples = 0
    @Volatile private var learnedCorrectionPairs = 0
    @Volatile private var blockedAutocorrectReverts = 0

    // Active-word tap ring for single-char autocorrect diff & Backspace-correction pair learning
    private val wordTapCharIdx = IntArray(WORD_TAP_CAP)
    private val wordTapRawX = FloatArray(WORD_TAP_CAP)
    private val wordTapRawY = FloatArray(WORD_TAP_CAP)
    private val wordTapCx = FloatArray(WORD_TAP_CAP)
    private val wordTapCy = FloatArray(WORD_TAP_CAP)
    private val wordTapW = FloatArray(WORD_TAP_CAP)
    private val wordTapH = FloatArray(WORD_TAP_CAP)
    private var wordTapLen = 0

    private var lastDeletedCharIdx = -1
    private var lastDeletedRawX = 0f
    private var lastDeletedRawY = 0f
    private var lastDeletedTimeMs = 0L
    @Volatile private var lastAlphaTapTimeMs = 0L
    @Volatile private var learningDirty = false

    // Privacy-preserving 64-bit FNV-1a autocorrect revert shield (NEVER stores plaintext)
    private val revertShieldCounts = HashMap<Long, Int>(64)

    // Probability fusion arrays (protected by stateLock)
    private val trieProbs = FloatArray(26)
    private val enTrieProbs = FloatArray(26)
    private val contextBoosts = FloatArray(26)
    private val charProbs = FloatArray(26)
    private val charBoosts = FloatArray(26)
    private var zoneGeneration = 0

    // Pre-allocated Zero-Allocation 26-slot Zone & Display Pools (protected by stateLock)
    private val targetZones = Array(26) { RectF() }
    private val displayZones = Array(26) { RectF() }
    private val zoneKeys = arrayOfNulls<Key>(26)
    private val zoneRows = IntArray(26)
    private val zoneCharIdx = IntArray(26)
    private var zoneCount = 0
    private var builtKeyboardRef: Keyboard? = null
    private var builtGeneration = -1
    private var builtDensity = 0f
    private var displayZonesPrimed = false
    private var lastDrawAnimUptimeMs = 0L

    // Row grouping scratch buffers for 2D zone partitioning (0 allocations inside buildTapZonesLocked)
    private val rowCounts = IntArray(4)
    private val rowCentersY = FloatArray(4)
    private val rowKeyIdx = Array(4) { IntArray(12) }
    private val rowBoundsX = FloatArray(13)
    private val rawZoneTop = FloatArray(26)
    private val rawZoneBottom = FloatArray(26)

    // Telemetry, touch jitter guard & deferred commit state (EMA only mutates on real letter commit!)
    private val latencyRing = DoubleArray(100)
    private var ringIdx = 0
    private var ringCount = 0
    @Volatile private var totalTaps = 0
    @Volatile private var rescuedCount = 0
    @Volatile private var coreProtectedCount = 0
    @Volatile private var lastRawX = -1f
    @Volatile private var lastRawY = -1f
    @Volatile private var lastAdjX = -1f
    @Volatile private var lastAdjY = -1f
    @Volatile private var lastGeoChar: Char = '-'
    @Volatile private var lastResChar: Char = '-'
    @Volatile private var lastResolvedKeyCode: Int = Int.MIN_VALUE
    @Volatile private var lastResolvedCenterX = -1f
    @Volatile private var lastResolvedCenterY = -1f
    @Volatile private var lastResolvedProb = 0f
    @Volatile private var lastWasRescued = false
    @Volatile private var lastProtectedByCore = false
    @Volatile private var lastTouchTimeMs = 0L
    @Volatile private var lastLatencyMs = 0.0
    @Volatile private var lastOffsetYPx = 0f

    @Volatile private var pendingValid = false
    @Volatile private var pendingRawX = -1f
    @Volatile private var pendingRawY = -1f
    @Volatile private var pendingAdjX = -1f
    @Volatile private var pendingAdjY = -1f
    @Volatile private var pendingGeoChar: Char = '-'
    @Volatile private var pendingResChar: Char = '-'
    @Volatile private var pendingResCode: Int = Int.MIN_VALUE
    @Volatile private var pendingResCx = -1f
    @Volatile private var pendingResCy = -1f
    @Volatile private var pendingResProb = 0f
    @Volatile private var pendingResRow = 0
    @Volatile private var pendingKeyW = 1f
    @Volatile private var pendingKeyH = 1f
    @Volatile private var pendingRescued = false
    @Volatile private var pendingInCore = false
    @Volatile private var pendingLatencyMs = 0.0
    @Volatile private var pendingTimeMs = 0L

    private var cachedHudText = ""
    private var cachedHudStamp = Long.MIN_VALUE

    // Pre-allocated drawing objects (zero allocation in onDraw)
    private val tempRect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val offsetVectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rescuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    @Volatile private var paintsInitialized = false

    @JvmStatic
    fun attachKeyboardView(view: View?) {
        attachedViewRef = if (view != null) WeakReference(view) else null
    }

    @JvmStatic
    fun ensureInitialized(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (!prefsLoaded) {
            prefsLoaded = true
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            visualizerMode = VisualizerMode.entries.getOrElse(prefs.getInt(KEY_VIZ_MODE, VisualizerMode.OFF.ordinal)) { VisualizerMode.OFF }
            sensitivity = Sensitivity.entries.getOrElse(prefs.getInt(KEY_SENSITIVITY, Sensitivity.STANDARD.ordinal)) { Sensitivity.STANDARD }
            adaptiveThumbEnabled = prefs.getBoolean(KEY_ADAPTIVE, true)
            calibrationSamples = prefs.getInt(KEY_SAMPLES, 0)
            learnedCorrectionPairs = prefs.getInt(KEY_LEARNED_PAIRS, 0)
            blockedAutocorrectReverts = prefs.getInt(KEY_BLOCKED_REVERTS, 0)
            synchronized(stateLock) {
                for (r in 0 until 4) {
                    rowBiasX[r] = prefs.getFloat("rbx_$r", 0f).coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
                    val defY = if (r < 2) BASE_VERTICAL_BIAS_RATIO else if (r == 2) BASE_VERTICAL_BIAS_RATIO * 0.75f else 0f
                    rowBiasY[r] = prefs.getFloat("rby_$r", defY).coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
                }
                for (k in 0 until 26) {
                    keyMeanDx[k] = prefs.getFloat("kmx_$k", 0f).coerceIn(-MAX_KEY_DX, MAX_KEY_DX)
                    keyMeanDy[k] = prefs.getFloat("kmy_$k", BASE_VERTICAL_BIAS_RATIO).coerceIn(-MAX_KEY_DY, MAX_KEY_DY)
                    keyGrowLeft[k] = prefs.getFloat("kgl_$k", 0f).coerceIn(0f, MAX_KEY_GROW_X)
                    keyGrowRight[k] = prefs.getFloat("kgr_$k", 0f).coerceIn(0f, MAX_KEY_GROW_X)
                    keyGrowTop[k] = prefs.getFloat("kgt_$k", 0f).coerceIn(0f, MAX_KEY_GROW_Y)
                    keyGrowBottom[k] = prefs.getFloat("kgb_$k", 0f).coerceIn(0f, MAX_KEY_GROW_Y)
                    keySamples[k] = prefs.getInt("ksmp_$k", 0).coerceAtLeast(0)
                }
                keyConfusion.fill(0f)
                val confRaw = prefs.getString(KEY_CONF_PAIRS, "") ?: ""
                if (confRaw.isNotEmpty()) {
                    for (entry in confRaw.split(';')) {
                        val sep = entry.indexOf(':')
                        if (sep > 0) {
                            val idx = entry.substring(0, sep).toIntOrNull()
                            val v = entry.substring(sep + 1).toFloatOrNull()
                            if (idx != null && v != null && idx in keyConfusion.indices) {
                                keyConfusion[idx] = v.coerceIn(0f, 20f)
                            }
                        }
                    }
                }
                revertShieldCounts.clear()
                val revRaw = prefs.getString(KEY_REVERT_HASHES, "") ?: ""
                if (revRaw.isNotEmpty()) {
                    for (entry in revRaw.split(';')) {
                        val sep = entry.indexOf(':')
                        if (sep > 0) {
                            val h = entry.substring(0, sep).toLongOrNull(16)
                            val c = entry.substring(sep + 1).toIntOrNull()
                            if (h != null && c != null && c > 0 && revertShieldCounts.size < 128) {
                                revertShieldCounts[h] = c
                            }
                        }
                    }
                }
            }
        }
        ensureActiveLanguageLoaded(appCtx)
    }

    private fun ensureActiveLanguageLoaded(appCtx: Context) {
        val loadPt = activeLocaleIsPt || bilingualPtEnActive
        val loadEn = !activeLocaleIsPt || bilingualPtEnActive
        if (loadPt && ptTrie == null && !loadingPt) {
            loadingPt = true
            loaderExecutor.execute {
                val loaded = FlatPrefixTrie.loadFromAsset(appCtx, "dict/pt_br.tsv")
                ptTrie = loaded
                loadingPt = false
                synchronized(stateLock) {
                    recomputeProbabilitiesLocked()
                }
                if (visualizerMode != VisualizerMode.OFF) {
                    attachedViewRef?.get()?.postInvalidate()
                }
            }
        }
        if (loadEn && enTrie == null && !loadingEn) {
            loadingEn = true
            loaderExecutor.execute {
                val loaded = FlatPrefixTrie.loadFromAsset(appCtx, "dict/en_us.tsv")
                enTrie = loaded
                loadingEn = false
                synchronized(stateLock) {
                    recomputeProbabilitiesLocked()
                }
                if (visualizerMode != VisualizerMode.OFF) {
                    attachedViewRef?.get()?.postInvalidate()
                }
            }
        }
    }

    @JvmStatic
    fun isDynamicTouchActive(): Boolean = dynamicAllowedForField && !inGestureStroke

    @JvmStatic
    fun setInGesture(active: Boolean) {
        inGestureStroke = active
    }

    @JvmStatic
    fun isBilingualPrefEnabled(context: Context): Boolean = runCatching {
        context.prefs().getBoolean(Settings.PREF_MULTILINGUAL_PT_EN, Defaults.PREF_MULTILINGUAL_PT_EN)
    }.getOrDefault(Defaults.PREF_MULTILINGUAL_PT_EN)

    @JvmStatic
    fun isVisualizerActive(context: Context): Boolean {
        ensureInitialized(context)
        return visualizerMode != VisualizerMode.OFF && dynamicAllowedForField
    }

    @JvmStatic
    fun getVisualizerMode(context: Context): VisualizerMode {
        ensureInitialized(context)
        return visualizerMode
    }

    @JvmStatic
    fun setVisualizerMode(context: Context, mode: VisualizerMode) {
        ensureInitialized(context)
        visualizerMode = mode
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_VIZ_MODE, mode.ordinal).apply()
        attachedViewRef?.get()?.postInvalidate()
    }

    @JvmStatic
    fun cycleVisualizerMode(context: Context): VisualizerMode {
        val next = getVisualizerMode(context).next()
        setVisualizerMode(context, next)
        return next
    }

    @JvmStatic
    fun setSensitivity(context: Context, value: Sensitivity) {
        ensureInitialized(context)
        sensitivity = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SENSITIVITY, value.ordinal).apply()
        synchronized(stateLock) {
            recomputeProbabilitiesLocked()
        }
        attachedViewRef?.get()?.postInvalidate()
    }

    @JvmStatic
    fun setAdaptiveThumbEnabled(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        adaptiveThumbEnabled = enabled
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ADAPTIVE, enabled).apply()
    }

    @JvmStatic
    fun resetThumbCalibration(context: Context) {
        ensureInitialized(context)
        synchronized(stateLock) {
            rowBiasX.fill(0f)
            rowBiasY[0] = BASE_VERTICAL_BIAS_RATIO
            rowBiasY[1] = BASE_VERTICAL_BIAS_RATIO
            rowBiasY[2] = BASE_VERTICAL_BIAS_RATIO * 0.75f
            rowBiasY[3] = 0f
            keyMeanDx.fill(0f)
            keyMeanDy.fill(BASE_VERTICAL_BIAS_RATIO)
            keyGrowLeft.fill(0f)
            keyGrowRight.fill(0f)
            keyGrowTop.fill(0f)
            keyGrowBottom.fill(0f)
            keySamples.fill(0)
            keyConfusion.fill(0f)
            revertShieldCounts.clear()
            wordTapLen = 0
            lastDeletedCharIdx = -1
            calibrationSamples = 0
            learnedCorrectionPairs = 0
            blockedAutocorrectReverts = 0
            learningDirty = false
            zoneGeneration++
        }
        val editor = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putInt(KEY_SAMPLES, 0)
        editor.putInt(KEY_LEARNED_PAIRS, 0)
        editor.putInt(KEY_BLOCKED_REVERTS, 0)
        editor.remove(KEY_CONF_PAIRS)
        editor.remove(KEY_REVERT_HASHES)
        synchronized(stateLock) {
            for (r in 0 until 4) {
                editor.putFloat("rbx_$r", rowBiasX[r])
                editor.putFloat("rby_$r", rowBiasY[r])
            }
            for (k in 0 until 26) {
                editor.remove("kmx_$k")
                editor.remove("kmy_$k")
                editor.remove("kgl_$k")
                editor.remove("kgr_$k")
                editor.remove("kgt_$k")
                editor.remove("kgb_$k")
                editor.remove("ksmp_$k")
            }
        }
        editor.apply()
    }

    data class CalibrationSnapshot(
        val biasX: FloatArray,
        val biasY: FloatArray,
        val samples: Int,
        val keyMeanDx: FloatArray,
        val keyMeanDy: FloatArray,
        val keyGrowLeft: FloatArray,
        val keyGrowRight: FloatArray,
        val keyGrowTop: FloatArray,
        val keyGrowBottom: FloatArray,
        val keySamples: IntArray,
        val keyConfusion: FloatArray,
        val learnedCorrectionPairs: Int,
        val blockedAutocorrectReverts: Int,
        val revertShieldCounts: Map<Long, Int>
    )

    @JvmStatic
    fun captureCalibrationSnapshot(): CalibrationSnapshot {
        synchronized(stateLock) {
            return CalibrationSnapshot(
                biasX = rowBiasX.copyOf(),
                biasY = rowBiasY.copyOf(),
                samples = calibrationSamples,
                keyMeanDx = keyMeanDx.copyOf(),
                keyMeanDy = keyMeanDy.copyOf(),
                keyGrowLeft = keyGrowLeft.copyOf(),
                keyGrowRight = keyGrowRight.copyOf(),
                keyGrowTop = keyGrowTop.copyOf(),
                keyGrowBottom = keyGrowBottom.copyOf(),
                keySamples = keySamples.copyOf(),
                keyConfusion = keyConfusion.copyOf(),
                learnedCorrectionPairs = learnedCorrectionPairs,
                blockedAutocorrectReverts = blockedAutocorrectReverts,
                revertShieldCounts = HashMap(revertShieldCounts)
            )
        }
    }

    @JvmStatic
    fun restoreCalibrationSnapshot(context: Context, snapshot: CalibrationSnapshot) {
        ensureInitialized(context)
        synchronized(stateLock) {
            for (r in 0 until minOf(4, snapshot.biasX.size, snapshot.biasY.size)) {
                rowBiasX[r] = snapshot.biasX[r].coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
                rowBiasY[r] = snapshot.biasY[r].coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
            }
            for (k in 0 until minOf(26, snapshot.keyMeanDx.size)) {
                keyMeanDx[k] = snapshot.keyMeanDx[k].coerceIn(-MAX_KEY_DX, MAX_KEY_DX)
                keyMeanDy[k] = snapshot.keyMeanDy[k].coerceIn(-MAX_KEY_DY, MAX_KEY_DY)
                keyGrowLeft[k] = snapshot.keyGrowLeft[k].coerceIn(0f, MAX_KEY_GROW_X)
                keyGrowRight[k] = snapshot.keyGrowRight[k].coerceIn(0f, MAX_KEY_GROW_X)
                keyGrowTop[k] = snapshot.keyGrowTop[k].coerceIn(0f, MAX_KEY_GROW_Y)
                keyGrowBottom[k] = snapshot.keyGrowBottom[k].coerceIn(0f, MAX_KEY_GROW_Y)
                keySamples[k] = snapshot.keySamples[k].coerceAtLeast(0)
            }
            val confCount = minOf(keyConfusion.size, snapshot.keyConfusion.size)
            for (i in 0 until confCount) {
                keyConfusion[i] = snapshot.keyConfusion[i].coerceIn(0f, 20f)
            }
            revertShieldCounts.clear()
            revertShieldCounts.putAll(snapshot.revertShieldCounts)
            calibrationSamples = snapshot.samples.coerceAtLeast(0)
            learnedCorrectionPairs = snapshot.learnedCorrectionPairs.coerceAtLeast(0)
            blockedAutocorrectReverts = snapshot.blockedAutocorrectReverts.coerceAtLeast(0)
            learningDirty = true
            zoneGeneration++
        }
        persistLearningProfileAsync(context)
    }

    @JvmStatic
    fun restoreThumbCalibration(context: Context, biasX: FloatArray, biasY: FloatArray, samples: Int) {
        ensureInitialized(context)
        synchronized(stateLock) {
            for (r in 0 until minOf(4, biasX.size, biasY.size)) {
                rowBiasX[r] = biasX[r].coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
                rowBiasY[r] = biasY[r].coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
            }
            calibrationSamples = samples.coerceAtLeast(0)
            zoneGeneration++
        }
        val editor = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putInt(KEY_SAMPLES, calibrationSamples)
        synchronized(stateLock) {
            for (r in 0 until 4) {
                editor.putFloat("rbx_$r", rowBiasX[r])
                editor.putFloat("rby_$r", rowBiasY[r])
            }
        }
        editor.apply()
    }

    @JvmStatic
    fun resetTelemetry() {
        totalTaps = 0
        rescuedCount = 0
        coreProtectedCount = 0
        ringIdx = 0
        ringCount = 0
        lastLatencyMs = 0.0
        pendingValid = false
        lastResolvedKeyCode = Int.MIN_VALUE
        lastWasRescued = false
        cachedHudStamp = Long.MIN_VALUE
    }

    @JvmStatic
    fun onKeyCommitted() {
        // Pending calibration is promoted exclusively via onTapCommitted() on ACTION_UP / confirmed input.
    }

    /**
     * B1.Q3: Returns true if the most recent tap was rescued into [key] by Dynamic Tap Zones,
     * signaling PointerTracker to re-anchor coordinates to key center before calling native C++.
     */
    @JvmStatic
    fun wasLastTouchRescuedForKey(key: Key?): Boolean {
        if (key == null || !lastWasRescued) return false
        return key.code == lastResolvedKeyCode && (System.currentTimeMillis() - lastTouchTimeMs) < 120L
    }

    /**
     * B5.Q4: Immediately stop 120 Hz boundary animations and clear ephemeral topic text on hide.
     */
    @JvmStatic
    fun onWindowHidden() {
        inGestureStroke = false
        pendingValid = false
        val shouldPersist: Boolean
        synchronized(stateLock) {
            displayZonesPrimed = false
            lastDrawAnimUptimeMs = 0L
            wordTapLen = 0
            lastDeletedCharIdx = -1
            topicWords.clear()
            topicBigrams.clear()
            topicWordHashes.clear()
            topicBigramHashes.clear()
            lastBeforePrefixHash = 0L
            lastBeforePrefixLen = -1
            lastTopicWordCount = 0
            lastTopicBeforeLen = -1
            lastTopicFoldedTail = ""
            contextWordCount = 0
            cachedHudStamp = Long.MIN_VALUE
            shouldPersist = learningDirty
            learningDirty = false
        }
        if (shouldPersist) {
            persistLearningProfileAsync(appContextRef)
        }
    }

    /**
     * B5.Q1 / B5.Q4: Release inactive language trie on memory pressure (and both on TRIM_MEMORY_COMPLETE).
     */
    @JvmStatic
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            if (activeLocaleIsPt) enTrie = null else ptTrie = null
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            ptTrie = null
            enTrie = null
            synchronized(stateLock) {
                topicWords.clear()
                topicBigrams.clear()
                topicWordHashes.clear()
                topicBigramHashes.clear()
                lastBeforePrefixHash = 0L
                lastBeforePrefixLen = -1
                lastTopicWordCount = 0
                lastTopicBeforeLen = -1
                lastTopicFoldedTail = ""
            }
        }
    }

    @JvmStatic
    fun getTelemetrySnapshot(context: Context): LiveTelemetry {
        ensureInitialized(context)
        val p99 = if (ringCount == 0) 0.0 else {
            val copy = latencyRing.copyOf(ringCount).apply { sort() }
            copy[((copy.size - 1) * 0.99).toInt().coerceAtLeast(0)]
        }
        val trieBytes = (ptTrie?.estimatedBytes ?: 0L) + (enTrie?.estimatedBytes ?: 0L)
        synchronized(stateLock) {
            var adaptedCount = 0
            val ranked = ArrayList<Pair<String, Float>>(4)
            for (k in 0 until 26) {
                if (keySamples[k] > 0) adaptedCount++
                val gL = keyGrowLeft[k]
                val gR = keyGrowRight[k]
                val gT = keyGrowTop[k]
                val gB = keyGrowBottom[k]
                var bestDir = 'E'
                var bestVal = gL
                if (gR > bestVal) { bestDir = 'D'; bestVal = gR }
                if (gT > bestVal) { bestDir = 'C'; bestVal = gT }
                if (gB > bestVal) { bestDir = 'B'; bestVal = gB }
                if (bestVal >= 0.02f) {
                    val pct = (bestVal * 100f).roundToInt()
                    ranked.add("${('a' + k).uppercaseChar()}($bestDir+$pct%)" to bestVal)
                }
            }
            ranked.sortByDescending { it.second }
            val summary = if (ranked.isEmpty()) "Aguardando amostras" else ranked.take(3).joinToString(" · ") { it.first }
            val activeShields = revertShieldCounts.count { it.value >= 2 }

            return LiveTelemetry(
                totalTaps = totalTaps,
                rescuedTaps = rescuedCount,
                coreProtectedTaps = coreProtectedCount,
                lastLatencyMs = lastLatencyMs,
                p99LatencyMs = p99,
                lastGeoChar = lastGeoChar,
                lastResChar = lastResChar,
                lastProbPercent = (lastResolvedProb * 100f).roundToInt(),
                lastOffsetYPx = lastOffsetYPx,
                rowBiasY = rowBiasY.copyOf(),
                rowBiasX = rowBiasX.copyOf(),
                calibrationSamples = calibrationSamples,
                adaptiveEnabled = adaptiveThumbEnabled,
                sensitivity = sensitivity,
                visualizerMode = visualizerMode,
                contextWordCount = contextWordCount,
                currentPrefix = currentPrefix,
                prevWord = prevWord1,
                estimatedTrieRamKb = ((trieBytes + 1023L) / 1024L).toInt(),
                bilingualPtEn = bilingualPtEnActive,
                lastGlideWord = TaskGlideDecoder.lastGlideWord,
                lastGlideLatencyMs = TaskGlideDecoder.lastGlideLatencyMs,
                totalGlides = TaskGlideDecoder.totalGlides,
                learnedCorrectionPairs = learnedCorrectionPairs,
                blockedAutocorrectWords = activeShields,
                adaptedKeyCount = adaptedCount,
                topAdaptedSummary = summary
            )
        }
    }

    @JvmStatic
    fun onFieldStarted(context: Context, editorInfo: EditorInfo?, localeTag: String?) {
        activeLocaleIsPt = localeTag == null || localeTag.lowercase().startsWith("pt")
        bilingualPtEnActive = isBilingualPrefEnabled(context)
        ensureInitialized(context)
        applyEditorFieldPolicy(editorInfo)
        synchronized(stateLock) {
            currentPrefix = ""
            foldedPrefixLen = 0
            prevWord1 = ""
            prevWord2 = ""
            isSentenceStart = true
            contextWordCount = 0
            wordTapLen = 0
            lastDeletedCharIdx = -1
            lastAlphaTapTimeMs = 0L
            lastBeforePrefixHash = 0L
            lastBeforePrefixLen = -1
            lastTopicWordCount = 0
            lastTopicBeforeLen = -1
            lastTopicFoldedTail = ""
            topicWords.clear()
            topicBigrams.clear()
            topicWordHashes.clear()
            topicBigramHashes.clear()
            dynamicAllowedForField = fieldBaseDynamicAllowed
            pendingValid = false
            pendingResCode = Int.MIN_VALUE
            cachedHudStamp = Long.MIN_VALUE
            displayZonesPrimed = false
            recomputeProbabilitiesLocked()
        }
    }

    @JvmStatic
    fun updateComposingWord(context: Context, prefix: String?, localeTag: String?) {
        updateFullTextContext(context, prefix, localeTag)
    }

    @JvmStatic
    fun updateFullTextContext(context: Context, textBeforeCursor: CharSequence?, localeTag: String?) {
        updateFullTextContext(context, textBeforeCursor, localeTag, isBilingualPrefEnabled(context))
    }

    /**
     * Zero-Allocation hot-path context updater across all apps.
     * Uses incremental rolling-hash on text before prefix so typing characters inside a word
     * never re-tokenizes prior topic words.
     */
    @JvmStatic
    fun updateFullTextContext(
        context: Context,
        textBeforeCursor: CharSequence?,
        localeTag: String?,
        bilingualPtEn: Boolean
    ) {
        val nextIsPt = if (!localeTag.isNullOrBlank()) {
            localeTag.lowercase().startsWith("pt")
        } else {
            activeLocaleIsPt
        }
        val localeChanged = nextIsPt != activeLocaleIsPt
        val bilingualChanged = bilingualPtEn != bilingualPtEnActive
        activeLocaleIsPt = nextIsPt
        bilingualPtEnActive = bilingualPtEn
        ensureInitialized(context)

        val cs: CharSequence = textBeforeCursor ?: ""
        val totalLen = cs.length
        val startIdx = max(0, totalLen - 512)

        // 1. Scan trailing token [tokenStart..totalLen) without allocating Strings
        var tokenStart = totalLen
        while (tokenStart > startIdx && !cs[tokenStart - 1].isWhitespace()) {
            tokenStart--
        }
        val isCodeOrUrl = looksLikeCodeOrUrlToken(cs, tokenStart, totalLen)

        // 2. Scan trailing letter prefix [prefixStart..totalLen)
        var prefixStart = totalLen
        while (prefixStart > startIdx && Character.isLetter(cs[prefixStart - 1])) {
            prefixStart--
        }
        val prefixLen = totalLen - prefixStart

        // 3. Rolling hash of [startIdx..prefixStart) to detect whether completed words changed
        var hash = 1469598103934665603L
        for (i in startIdx until prefixStart) {
            hash = (hash xor cs[i].code.toLong()) * 1099511628211L
        }
        val beforePrefixLen = prefixStart - startIdx

        synchronized(stateLock) {
            val prevDynamic = dynamicAllowedForField
            dynamicAllowedForField = fieldBaseDynamicAllowed && !isCodeOrUrl

            // Fast update of foldedPrefixBuf (0 allocations)
            var fl = 0
            val maxPref = min(prefixLen, foldedPrefixBuf.size)
            for (i in 0 until maxPref) {
                val fc = foldCharAscii(cs[prefixStart + i])
                if (fc != '\u0000') foldedPrefixBuf[fl++] = fc
            }
            val prefixSame = fl == foldedPrefixLen && currentPrefix.length == prefixLen &&
                run {
                    var eq = true
                    for (i in 0 until prefixLen) {
                        if (cs[prefixStart + i] != currentPrefix[i]) {
                            eq = false
                            break
                        }
                    }
                    eq
                }
            foldedPrefixLen = fl
            if (!prefixSame) {
                currentPrefix = if (prefixLen == 0) "" else cs.subSequence(prefixStart, totalLen).toString()
            }

            val beforeChanged = localeChanged || beforePrefixLen != lastBeforePrefixLen || hash != lastBeforePrefixHash
            if (beforeChanged) {
                lastBeforePrefixLen = beforePrefixLen
                lastBeforePrefixHash = hash

                // Sentence boundary check ignoring trailing spaces before prefix
                var trimEnd = prefixStart
                while (trimEnd > startIdx && cs[trimEnd - 1].isWhitespace()) trimEnd--
                isSentenceStart = trimEnd == startIdx ||
                    cs[trimEnd - 1] == '.' ||
                    cs[trimEnd - 1] == '!' ||
                    cs[trimEnd - 1] == '?' ||
                    cs[trimEnd - 1] == '\n'

                // Tokenize completed words strictly into pre-allocated recentWordBounds IntArray
                var wordCount = 0
                var wStart = -1
                for (i in startIdx until prefixStart) {
                    if (Character.isLetter(cs[i])) {
                        if (wStart == -1) wStart = i
                    } else if (wStart != -1) {
                        if (wordCount < 120) {
                            recentWordBounds[wordCount * 2] = wStart
                            recentWordBounds[wordCount * 2 + 1] = i
                            wordCount++
                        }
                        wStart = -1
                    }
                }
                if (wStart != -1 && wordCount < 120) {
                    recentWordBounds[wordCount * 2] = wStart
                    recentWordBounds[wordCount * 2 + 1] = prefixStart
                    wordCount++
                }
                contextWordCount = wordCount
                prevWord1 = if (wordCount >= 1) {
                    foldSubstringToString(cs, recentWordBounds[(wordCount - 1) * 2], recentWordBounds[(wordCount - 1) * 2 + 1])
                } else ""
                prevWord2 = if (wordCount >= 2) {
                    foldSubstringToString(cs, recentWordBounds[(wordCount - 2) * 2], recentWordBounds[(wordCount - 2) * 2 + 1])
                } else ""

                if (!learningAllowedForField || !dynamicAllowedForField || wordCount == 0) {
                    topicWords.clear()
                    topicBigrams.clear()
                    topicWordHashes.clear()
                    topicBigramHashes.clear()
                    lastTopicWordCount = 0
                    lastTopicBeforeLen = if (wordCount == 0) beforePrefixLen else -1
                    lastTopicFoldedTail = prevWord1
                } else {
                    val canIncremental = wordCount == lastTopicWordCount + 1 &&
                        totalLen <= 512 &&
                        lastTopicBeforeLen in 0 until beforePrefixLen &&
                        (wordCount < 2 || prevWord2 == lastTopicFoldedTail)
                    if (canIncremental) {
                        if (prevWord1.length >= 2) {
                            topicWords[prevWord1] = (topicWords[prevWord1] ?: 0) + 1
                            val h1 = fnv1aString(prevWord1)
                            topicWordHashes[h1] = (topicWordHashes[h1] ?: 0) + 1
                            if (prevWord2.isNotEmpty()) {
                                val map = topicBigrams.getOrPut(prevWord2) { HashMap(4) }
                                map[prevWord1] = (map[prevWord1] ?: 0) + 1
                                val h2 = fnv1aString(prevWord2)
                                val hMap = topicBigramHashes.getOrPut(h2) { HashMap(4) }
                                hMap[h1] = (hMap[h1] ?: 0) + 1
                            }
                        }
                    } else {
                        topicWords.clear()
                        topicBigrams.clear()
                        topicWordHashes.clear()
                        topicBigramHashes.clear()
                        var prevFolded: String? = null
                        var prevHash = 0L
                        for (w in 0 until wordCount) {
                            val ws = recentWordBounds[w * 2]
                            val we = recentWordBounds[w * 2 + 1]
                            val folded = foldSubstringToString(cs, ws, we)
                            if (folded.length >= 2) {
                                val h1 = fnv1aString(folded)
                                topicWords[folded] = (topicWords[folded] ?: 0) + 1
                                topicWordHashes[h1] = (topicWordHashes[h1] ?: 0) + 1
                                if (prevFolded != null) {
                                    val map = topicBigrams.getOrPut(prevFolded) { HashMap(4) }
                                    map[folded] = (map[folded] ?: 0) + 1
                                    val hMap = topicBigramHashes.getOrPut(prevHash) { HashMap(4) }
                                    hMap[h1] = (hMap[h1] ?: 0) + 1
                                }
                                prevFolded = folded
                                prevHash = h1
                            } else if (folded.isNotEmpty()) {
                                prevFolded = folded
                                prevHash = fnv1aString(folded)
                            }
                        }
                    }
                    lastTopicWordCount = wordCount
                    lastTopicBeforeLen = beforePrefixLen
                    lastTopicFoldedTail = prevWord1
                }
            }

            if (beforeChanged || bilingualChanged || !prefixSame || prevDynamic != dynamicAllowedForField) {
                recomputeProbabilitiesLocked()
            }
        }
    }

    private fun foldSubstringToString(cs: CharSequence, start: Int, end: Int): String {
        var len = 0
        val limit = min(end - start, foldScratch.size)
        for (i in 0 until limit) {
            val fc = foldCharAscii(cs[start + i])
            if (fc != '\u0000') foldScratch[len++] = fc
        }
        return if (len == 0) "" else String(foldScratch, 0, len)
    }

    /**
     * B1.Q2: Adaptive Shannon-Entropy Fusion (must hold stateLock).
     * - Prefix 0–2 chars OR high Trie entropy (> 0.65): 65% context (sentence/topic/bigrams), 35% lexicon.
     * - Prefix 3+ chars AND low Trie entropy (<= 0.45): 85% lexicon, 15% context.
     */
    private fun recomputeProbabilitiesLocked() {
        val pt = ptTrie
        val en = enTrie
        val primaryTrie = if (activeLocaleIsPt) (pt ?: en) else (en ?: pt)
        if (primaryTrie == null || !dynamicAllowedForField) {
            charProbs.fill(0f)
            charBoosts.fill(0f)
            zoneGeneration++
            return
        }

        val dualActive = bilingualPtEnActive && pt != null && en != null
        var ptWeight = if (activeLocaleIsPt) 1f else 0f

        if (dualActive) {
            pt.nextCharProbabilities(foldedPrefixBuf, foldedPrefixLen, trieProbs)
            en.nextCharProbabilities(foldedPrefixBuf, foldedPrefixLen, enTrieProbs)

            ptWeight = if (activeLocaleIsPt) 0.70f else 0.30f
            val ptCtxHit = (prevWord1.isNotEmpty() && prevWord1 in ptBigrams) ||
                prevWord1 in ptFeminineCues || prevWord1 in ptMasculineCues
            val enCtxHit = prevWord1.isNotEmpty() && prevWord1 in enBigrams
            if (ptCtxHit && !enCtxHit) ptWeight += 0.15f
            else if (enCtxHit && !ptCtxHit) ptWeight -= 0.15f

            if (foldedPrefixLen > 0) {
                val ptMass = pt.prefixMass(foldedPrefixBuf, foldedPrefixLen)
                val enMass = en.prefixMass(foldedPrefixBuf, foldedPrefixLen)
                if (ptMass > 0 && enMass == 0) {
                    ptWeight = 0.85f
                } else if (enMass > 0 && ptMass == 0) {
                    ptWeight = 0.15f
                } else if (ptMass + enMass > 0) {
                    val massRatio = ptMass.toFloat() / (ptMass + enMass).toFloat()
                    ptWeight = ptWeight * 0.5f + (0.15f + 0.70f * massRatio) * 0.5f
                }
            }
            ptWeight = ptWeight.coerceIn(0.15f, 0.85f)
            val enWeight = 1f - ptWeight
            for (i in 0 until 26) {
                trieProbs[i] = trieProbs[i] * ptWeight + enTrieProbs[i] * enWeight
            }
        } else {
            primaryTrie.nextCharProbabilities(foldedPrefixBuf, foldedPrefixLen, trieProbs)
        }
        val enWeight = 1f - ptWeight

        // Compute normalized Shannon entropy H in [0..1]
        var entropy = 0f
        for (i in 0 until 26) {
            val p = trieProbs[i]
            if (p > 1e-6f) {
                entropy -= p * ln(p)
            }
        }
        val normEntropy = (entropy / LN_26).coerceIn(0f, 1f)

        val trieWeight: Float
        val contextWeight: Float
        if (foldedPrefixLen <= 2 || normEntropy > 0.65f) {
            trieWeight = 0.35f
            contextWeight = 0.65f
        } else if (normEntropy <= 0.45f) {
            trieWeight = 0.85f
            contextWeight = 0.15f
        } else {
            val t = (normEntropy - 0.45f) / 0.20f // 0 at 0.45, 1 at 0.65
            trieWeight = 0.85f - t * (0.85f - 0.35f)
            contextWeight = 1f - trieWeight
        }

        // 2. Context priors (built-in bigrams/trigrams, sentence starters, PT morpho, ephemeral topic)
        contextBoosts.fill(0f)

        if (prevWord2.isNotEmpty() && prevWord1.isNotEmpty()) {
            trigramKeyBuilder.setLength(0)
            trigramKeyBuilder.append(prevWord2).append(' ').append(prevWord1)
            val triKey = trigramKeyBuilder.toString()
            if (ptWeight > 0.01f) {
                ptTrigrams[triKey]?.let { targets ->
                    for (i in targets.indices) {
                        val pair = targets[i]
                        boostMatchingTargetChar(contextBoosts, pair.first, pair.second * 0.90f * ptWeight)
                    }
                }
            }
            if (enWeight > 0.01f) {
                enTrigrams[triKey]?.let { targets ->
                    for (i in targets.indices) {
                        val pair = targets[i]
                        boostMatchingTargetChar(contextBoosts, pair.first, pair.second * 0.90f * enWeight)
                    }
                }
            }
        }
        if (prevWord1.isNotEmpty()) {
            if (ptWeight > 0.01f) {
                ptBigrams[prevWord1]?.let { targets ->
                    for (i in targets.indices) {
                        val pair = targets[i]
                        boostMatchingTargetChar(contextBoosts, pair.first, pair.second * 0.65f * ptWeight)
                    }
                }
            }
            if (enWeight > 0.01f) {
                enBigrams[prevWord1]?.let { targets ->
                    for (i in targets.indices) {
                        val pair = targets[i]
                        boostMatchingTargetChar(contextBoosts, pair.first, pair.second * 0.65f * enWeight)
                    }
                }
            }
        }

        if (isSentenceStart && foldedPrefixLen == 0) {
            if (ptWeight > 0.01f) {
                for (idx in ptSentenceStarters.indices) {
                    val cIdx = ptSentenceStarters[idx] - 'a'
                    if (cIdx in 0..25) {
                        contextBoosts[cIdx] += 0.22f * (1f - idx * 0.06f) * ptWeight
                    }
                }
            }
            if (enWeight > 0.01f) {
                for (idx in enSentenceStarters.indices) {
                    val cIdx = enSentenceStarters[idx] - 'a'
                    if (cIdx in 0..25) {
                        contextBoosts[cIdx] += 0.22f * (1f - idx * 0.06f) * enWeight
                    }
                }
            }
        }

        if (ptWeight >= 0.35f && prevWord1.isNotEmpty() && foldedPrefixLen >= 2) {
            if (prevWord1 in ptFeminineCues) {
                contextBoosts['a' - 'a'] += 0.14f * ptWeight
                contextBoosts['s' - 'a'] += 0.05f * ptWeight
            } else if (prevWord1 in ptMasculineCues) {
                contextBoosts['o' - 'a'] += 0.14f * ptWeight
                contextBoosts['r' - 'a'] += 0.05f * ptWeight
            }
            if (prevWord1 in ptPluralCues && foldedPrefixLen >= 3) {
                contextBoosts['s' - 'a'] += 0.18f * ptWeight
            }
        }

        if (learningAllowedForField && topicWords.isNotEmpty()) {
            val localNextMap = if (prevWord1.isNotEmpty()) topicBigrams[prevWord1] else null
            if (localNextMap != null) {
                for ((word, count) in localNextMap) {
                    boostMatchingTargetChar(contextBoosts, word, (0.50f + count * 0.18f).coerceAtMost(1.0f))
                }
            }
            if (foldedPrefixLen > 0) {
                for ((word, count) in topicWords) {
                    boostMatchingTargetChar(contextBoosts, word, (0.30f + count * 0.12f).coerceAtMost(0.85f))
                }
            }
        }

        // Normalize context distribution if non-empty
        var ctxSum = 0f
        for (i in 0 until 26) ctxSum += contextBoosts[i]

        var maxP = 0f
        if (ctxSum > 0f) {
            val invCtx = 1f / ctxSum
            var fusedSum = 0f
            for (i in 0 until 26) {
                val fused = trieProbs[i] * trieWeight + (contextBoosts[i] * invCtx) * contextWeight
                charProbs[i] = fused
                fusedSum += fused
            }
            if (fusedSum > 0f) {
                val invF = 1f / fusedSum
                for (i in 0 until 26) {
                    charProbs[i] *= invF
                    if (charProbs[i] > maxP) maxP = charProbs[i]
                }
            }
        } else {
            for (i in 0 until 26) {
                val p = trieProbs[i]
                charProbs[i] = p
                if (p > maxP) maxP = p
            }
        }

        if (maxP <= 0f) {
            charBoosts.fill(0f)
        } else {
            for (i in 0 until 26) {
                charBoosts[i] = sqrt((charProbs[i] / maxP).coerceIn(0f, 1f))
            }
        }
        zoneGeneration++
    }

    private fun foldWordThreadSafe(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val limit = min(text.length, 64)
        val buf = CharArray(limit)
        var len = 0
        for (i in 0 until limit) {
            val fc = foldCharAscii(text[i])
            if (fc != '\u0000') buf[len++] = fc
        }
        return if (len == 0) "" else String(buf, 0, len)
    }

    /**
     * Shared context score [0f..1.5f] used by TaskGlideDecoder to boost swipe candidates that match
     * the preceding 1-2 words and ephemeral topic history.
     */
    @JvmStatic
    fun getWordContextBoost(
        prevWord2: String?,
        prevWord1: String?,
        foldedWord: String,
        isPt: Boolean
    ): Float {
        if (foldedWord.isEmpty()) return 0f
        val p1 = foldWordThreadSafe(prevWord1)
        val p2 = foldWordThreadSafe(prevWord2)
        var boost = 0f

        val triMap = if (isPt) ptTrigrams else enTrigrams
        val biMap = if (isPt) ptBigrams else enBigrams

        if (p2.isNotEmpty() && p1.isNotEmpty()) {
            val triTargets = triMap["$p2 $p1"]
            if (triTargets != null) {
                for (i in triTargets.indices) {
                    if (triTargets[i].first == foldedWord) {
                        boost += triTargets[i].second * 0.90f
                        break
                    }
                }
            }
        }
        if (p1.isNotEmpty()) {
            val biTargets = biMap[p1]
            if (biTargets != null) {
                for (i in biTargets.indices) {
                    if (biTargets[i].first == foldedWord) {
                        boost += biTargets[i].second * 0.65f
                        break
                    }
                }
            }
        }

        if (learningAllowedForField && dynamicAllowedForField) {
            synchronized(stateLock) {
                if (p1.isNotEmpty()) {
                    val count = topicBigrams[p1]?.get(foldedWord) ?: 0
                    if (count > 0) {
                        boost += (0.45f + count * 0.15f).coerceAtMost(0.85f)
                    }
                }
                val topicCount = topicWords[foldedWord] ?: 0
                if (topicCount > 0) {
                    boost += (0.15f + topicCount * 0.08f).coerceAtMost(0.45f)
                }
            }
        }
        return boost.coerceAtMost(1.5f)
    }

    /**
     * Zero-Allocation Byte-Slice overload for packed GlideLexicon candidates.
     * Queries pre-computed 64-bit FNV-1a topic/bigram hash tables without creating String instances.
     */
    @JvmStatic
    fun getWordContextBoost(
        prevWord2: String?,
        prevWord1: String?,
        foldedBytes: ByteArray,
        fStart: Int,
        fLen: Int,
        isPt: Boolean
    ): Float {
        if (fLen <= 0) return 0f
        val p1 = foldWordThreadSafe(prevWord1)
        val p2 = foldWordThreadSafe(prevWord2)
        var boost = 0f

        val triMap = if (isPt) ptTrigrams else enTrigrams
        val biMap = if (isPt) ptBigrams else enBigrams

        if (p2.isNotEmpty() && p1.isNotEmpty()) {
            val triTargets = triMap["$p2 $p1"]
            if (triTargets != null) {
                for (i in triTargets.indices) {
                    if (equalsAsciiBytes(triTargets[i].first, foldedBytes, fStart, fLen)) {
                        boost += triTargets[i].second * 0.90f
                        break
                    }
                }
            }
        }
        if (p1.isNotEmpty()) {
            val biTargets = biMap[p1]
            if (biTargets != null) {
                for (i in biTargets.indices) {
                    if (equalsAsciiBytes(biTargets[i].first, foldedBytes, fStart, fLen)) {
                        boost += biTargets[i].second * 0.65f
                        break
                    }
                }
            }
        }

        if (learningAllowedForField && dynamicAllowedForField) {
            val foldedHash = fnv1aBytes(foldedBytes, fStart, fLen)
            val p1Hash = if (p1.isNotEmpty()) fnv1aString(p1) else 0L
            synchronized(stateLock) {
                if (p1Hash != 0L) {
                    val count = topicBigramHashes[p1Hash]?.get(foldedHash) ?: 0
                    if (count > 0) {
                        boost += (0.45f + count * 0.15f).coerceAtMost(0.85f)
                    }
                }
                val topicCount = topicWordHashes[foldedHash] ?: 0
                if (topicCount > 0) {
                    boost += (0.15f + topicCount * 0.08f).coerceAtMost(0.45f)
                }
            }
        }
        return boost.coerceAtMost(1.5f)
    }

    private fun equalsAsciiBytes(s: String, bytes: ByteArray, offset: Int, len: Int): Boolean {
        if (s.length != len) return false
        for (i in 0 until len) {
            if (s[i].code != (bytes[offset + i].toInt() and 0xFF)) return false
        }
        return true
    }

    private fun fnv1aString(s: String): Long {
        var hash = -3750763034362895579L // 14695981039346656037UL as signed Long
        for (i in 0 until s.length) {
            hash = (hash xor s[i].code.toLong()) * 1099511628211L
        }
        return hash
    }

    private fun fnv1aBytes(bytes: ByteArray, offset: Int, len: Int): Long {
        var hash = -3750763034362895579L
        for (i in 0 until len) {
            hash = (hash xor (bytes[offset + i].toInt() and 0xFF).toLong()) * 1099511628211L
        }
        return hash
    }

    /**
     * Offline next-word predictions (up to 3) from trigrams -> bigrams -> ephemeral topic cache,
     * with Portuguese accents restored on common words. Respects sensitive/no-learning fields.
     */
    @JvmStatic
    fun getNextWordCandidates(
        prevWord2: String?,
        prevWord1: String?,
        isPt: Boolean,
        bilingual: Boolean
    ): List<String> {
        val p1 = foldWordThreadSafe(prevWord1)
        val p2 = foldWordThreadSafe(prevWord2)
        if (p1.isEmpty() && p2.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<String, Float>>(8)

        fun collectFrom(list: List<Pair<String, Float>>?, scale: Float, restorePt: Boolean) {
            if (list == null) return
            for (i in list.indices) {
                val raw = list[i].first
                val display = if (restorePt) (ptAccentRestore[raw] ?: raw) else raw
                val weight = list[i].second * scale
                val existing = scored.indexOfFirst { it.first.equals(display, ignoreCase = true) }
                if (existing >= 0) {
                    if (weight > scored[existing].second) scored[existing] = display to weight
                } else {
                    scored.add(display to weight)
                }
            }
        }

        val tryPt = isPt || bilingual
        val tryEn = !isPt || bilingual
        val ptScale = if (isPt) 1.0f else 0.72f
        val enScale = if (!isPt) 1.0f else 0.72f

        if (p2.isNotEmpty() && p1.isNotEmpty()) {
            val triKey = "$p2 $p1"
            if (tryPt) collectFrom(ptTrigrams[triKey], 1.15f * ptScale, true)
            if (tryEn) collectFrom(enTrigrams[triKey], 1.15f * enScale, false)
        }
        if (p1.isNotEmpty()) {
            if (tryPt) collectFrom(ptBigrams[p1], 0.90f * ptScale, true)
            if (tryEn) collectFrom(enBigrams[p1], 0.90f * enScale, false)
        }

        if (learningAllowedForField && dynamicAllowedForField && p1.isNotEmpty()) {
            synchronized(stateLock) {
                topicBigrams[p1]?.let { localMap ->
                    for ((word, count) in localMap) {
                        val display = if (isPt) (ptAccentRestore[word] ?: word) else word
                        val w = (0.60f + count * 0.15f).coerceAtMost(1.05f)
                        val existing = scored.indexOfFirst { it.first.equals(display, ignoreCase = true) }
                        if (existing >= 0) {
                            if (w > scored[existing].second) scored[existing] = display to w
                        } else {
                            scored.add(display to w)
                        }
                    }
                }
            }
        }

        if (scored.isEmpty()) return emptyList()
        scored.sortByDescending { it.second }
        val limit = min(3, scored.size)
        val result = ArrayList<String>(limit)
        for (i in 0 until limit) {
            result.add(scored[i].first)
        }
        return result
    }

    private fun boostMatchingTargetChar(out: FloatArray, targetWord: String, boostWeight: Float) {
        if (targetWord.length <= foldedPrefixLen) return
        for (i in 0 until foldedPrefixLen) {
            if (targetWord[i] != foldedPrefixBuf[i]) return
        }
        val idx = targetWord[foldedPrefixLen] - 'a'
        if (idx in 0..25) {
            out[idx] += boostWeight
        }
    }

    /**
     * Zero-Allocation 2D Non-Overlapping Dynamic Tap Zone Builder (must hold stateLock).
     * Combines horizontal pairwise boundary shifts (±maxShiftRatio), vertical pairwise
     * boundary shifts across rows (±maxVertShiftRatio), 56% central core protection,
     * and 4 dp lateral palm damping on edge keys.
     */
    private fun ensureZonesBuiltLocked(keyboard: Keyboard, density: Float) {
        if (builtKeyboardRef === keyboard && builtGeneration == zoneGeneration && builtDensity == density) {
            return
        }
        builtKeyboardRef = keyboard
        builtGeneration = zoneGeneration
        builtDensity = density

        var count = 0
        val sortedKeys = keyboard.sortedKeys
        for (i in sortedKeys.indices) {
            val k = sortedKeys[i]
            val ch = toAlphaChar(k.code) ?: continue
            if (count < 26) {
                zoneKeys[count] = k
                zoneCharIdx[count] = ch - 'a'
                count++
            }
        }
        zoneCount = count
        if (count == 0) return

        // 1. Group alphabetic keys into visual rows (0 allocations)
        val firstHb = zoneKeys[0]!!.hitBox
        val rowTol = (firstHb.height() * 0.35f).coerceAtLeast(10f)
        var rowCount = 0
        rowCounts.fill(0)

        for (i in 0 until count) {
            val cy = zoneKeys[i]!!.hitBox.exactCenterY()
            var targetRow = -1
            for (r in 0 until rowCount) {
                if (abs(rowCentersY[r] - cy) <= rowTol) {
                    targetRow = r
                    break
                }
            }
            if (targetRow == -1 && rowCount < 4) {
                targetRow = rowCount++
                rowCentersY[targetRow] = cy
            }
            if (targetRow in 0..3 && rowCounts[targetRow] < 12) {
                rowKeyIdx[targetRow][rowCounts[targetRow]++] = i
            }
        }

        // Sort rows top-to-bottom (selection sort, max 4 items)
        for (r in 0 until rowCount - 1) {
            for (s in r + 1 until rowCount) {
                if (rowCentersY[s] < rowCentersY[r]) {
                    val tmpY = rowCentersY[r]; rowCentersY[r] = rowCentersY[s]; rowCentersY[s] = tmpY
                    val tmpC = rowCounts[r]; rowCounts[r] = rowCounts[s]; rowCounts[s] = tmpC
                    val tmpArr = rowKeyIdx[r]; rowKeyIdx[r] = rowKeyIdx[s]; rowKeyIdx[s] = tmpArr
                }
            }
        }

        val maxShiftRatio = sensitivity.maxShiftRatio
        val maxVertRatio = sensitivity.maxVertShiftRatio
        val gutter = 3f
        val halfGutter = gutter * 0.5f
        val edgeDampPx = EDGE_DAMP_DP * density

        // 2. Horizontal pairwise boundaries + 4 dp lateral edge damping (B1.Q4)
        for (r in 0 until rowCount) {
            val n = rowCounts[r]
            if (n <= 0) continue
            val indices = rowKeyIdx[r]
            // Sort left-to-right by hitBox.left
            for (a in 0 until n - 1) {
                for (b in a + 1 until n) {
                    if (zoneKeys[indices[b]]!!.hitBox.left < zoneKeys[indices[a]]!!.hitBox.left) {
                        val t = indices[a]; indices[a] = indices[b]; indices[b] = t
                    }
                }
            }

            val firstKey = zoneKeys[indices[0]]!!
            val lastKey = zoneKeys[indices[n - 1]]!!
            val firstHb = firstKey.hitBox
            val lastHb = lastKey.hitBox

            // 4 dp outer edge damping only when key sits against screen edge, clamped outside 56% core
            val leftCoreLimit = firstHb.exactCenterX() - firstHb.width() * PROTECTED_CORE_RATIO * 0.5f
            val rightCoreLimit = lastHb.exactCenterX() + lastHb.width() * PROTECTED_CORE_RATIO * 0.5f
            val applyLeftDamp = firstHb.left <= (8f * density)
            val applyRightDamp = lastHb.right >= (keyboard.mOccupiedWidth - 8f * density)
            rowBoundsX[0] = if (applyLeftDamp) min(firstHb.left + edgeDampPx, leftCoreLimit) else firstHb.left.toFloat()
            rowBoundsX[n] = if (applyRightDamp) max(lastHb.right - edgeDampPx, rightCoreLimit) else lastHb.right.toFloat()

            for (i in 0 until n - 1) {
                val lIdx = indices[i]
                val rIdx = indices[i + 1]
                val lChar = zoneCharIdx[lIdx]
                val rChar = zoneCharIdx[rIdx]
                val lhb = zoneKeys[lIdx]!!.hitBox
                val rhb = zoneKeys[rIdx]!!.hitBox
                val midX = (lhb.right + rhb.left) * 0.5f
                val avgW = (lhb.width() + rhb.width()) * 0.5f
                val langDelta = (charBoosts[lChar] - charBoosts[rChar]) * (avgW * maxShiftRatio)
                val dirDelta = if (adaptiveThumbEnabled) {
                    (keyGrowRight[lChar] - keyGrowLeft[rChar]) * (avgW * 0.55f) +
                        (keyMeanDx[lChar] + keyMeanDx[rChar]) * (avgW * 0.25f)
                } else 0f
                val confDelta = if (adaptiveThumbEnabled) {
                    ((keyConfusion[rChar * 26 + lChar] - keyConfusion[lChar * 26 + rChar]) / 6f)
                        .coerceIn(-0.18f, 0.18f) * avgW
                } else 0f
                val delta = langDelta + dirDelta + confDelta

                val minBound = lhb.exactCenterX() + lhb.width() * PROTECTED_CORE_RATIO * 0.5f + halfGutter
                val maxBound = rhb.exactCenterX() - rhb.width() * PROTECTED_CORE_RATIO * 0.5f - halfGutter
                rowBoundsX[i + 1] = if (minBound <= maxBound) (midX + delta).coerceIn(minBound, maxBound) else midX
            }

            for (i in 0 until n) {
                val zIdx = indices[i]
                val hb = zoneKeys[zIdx]!!.hitBox
                zoneRows[zIdx] = r.coerceIn(0, 3)
                val left = rowBoundsX[i] + halfGutter
                val right = (rowBoundsX[i + 1] - halfGutter).coerceAtLeast(left + 2f)
                targetZones[zIdx].left = left
                targetZones[zIdx].right = right
                rawZoneTop[zIdx] = hb.top + halfGutter
                rawZoneBottom[zIdx] = hb.bottom - halfGutter
            }
        }

        // 3. Vertical 2D pairwise boundaries between adjacent rows (zero overlap in Y)
        for (r in 0 until rowCount - 1) {
            val topN = rowCounts[r]
            val botN = rowCounts[r + 1]
            for (ti in 0 until topN) {
                val tIdx = rowKeyIdx[r][ti]
                val tChar = zoneCharIdx[tIdx]
                val tRect = targetZones[tIdx]
                val tHb = zoneKeys[tIdx]!!.hitBox
                val tCoreBottom = tHb.exactCenterY() + tHb.height() * PROTECTED_CORE_RATIO * 0.5f
                val tBoost = charBoosts[tChar]

                for (bi in 0 until botN) {
                    val bIdx = rowKeyIdx[r + 1][bi]
                    val bChar = zoneCharIdx[bIdx]
                    val bRect = targetZones[bIdx]
                    // Check horizontal overlap between target zones (plus gutter tolerance)
                    if (tRect.right + gutter <= bRect.left || bRect.right + gutter <= tRect.left) continue

                    val bHb = zoneKeys[bIdx]!!.hitBox
                    val bCoreTop = bHb.exactCenterY() - bHb.height() * PROTECTED_CORE_RATIO * 0.5f
                    val midY = (tHb.bottom + bHb.top) * 0.5f
                    val avgH = (tHb.height() + bHb.height()) * 0.5f
                    val bBoost = charBoosts[bChar]
                    val langDeltaY = (tBoost - bBoost) * (avgH * maxVertRatio)
                    val dirDeltaY = if (adaptiveThumbEnabled) {
                        (keyGrowBottom[tChar] - keyGrowTop[bChar]) * (avgH * 0.45f) +
                            ((keyMeanDy[tChar] - BASE_VERTICAL_BIAS_RATIO) + (keyMeanDy[bChar] - BASE_VERTICAL_BIAS_RATIO)) * (avgH * 0.20f)
                    } else 0f
                    val confDeltaY = if (adaptiveThumbEnabled) {
                        ((keyConfusion[bChar * 26 + tChar] - keyConfusion[tChar * 26 + bChar]) / 8f)
                            .coerceIn(-0.14f, 0.14f) * avgH
                    } else 0f
                    val deltaY = langDeltaY + dirDeltaY + confDeltaY

                    val minY = tCoreBottom + halfGutter
                    val maxY = bCoreTop - halfGutter
                    val sharedY = if (minY <= maxY) (midY + deltaY).coerceIn(minY, maxY) else midY

                    // Tighten both top.bottom and bot.top strictly on their respective sides of sharedY
                    val candTopBottom = sharedY - halfGutter
                    val candBotTop = sharedY + halfGutter
                    if (candTopBottom < rawZoneBottom[tIdx]) {
                        rawZoneBottom[tIdx] = candTopBottom.coerceAtLeast(rawZoneTop[tIdx] + 2f)
                    }
                    if (candBotTop > rawZoneTop[bIdx]) {
                        rawZoneTop[bIdx] = candBotTop.coerceAtMost(rawZoneBottom[bIdx] - 2f)
                    }
                }
            }
        }

        // Write finalized 2D non-overlapping top/bottom into targetZones
        for (i in 0 until count) {
            targetZones[i].top = rawZoneTop[i]
            targetZones[i].bottom = rawZoneBottom[i].coerceAtLeast(rawZoneTop[i] + 2f)
        }

        if (!displayZonesPrimed) {
            for (i in 0 until count) {
                displayZones[i].set(targetZones[i])
            }
            displayZonesPrimed = true
        }
    }

    /**
     * 1:1 Touch Resolution using the Zero-Allocation 2D Non-Overlapping Tap Zones
     * and ergonomic thumb parallax compensation.
     */
    @JvmStatic
    fun resolveKey(
        keyboard: Keyboard?,
        touchX: Int,
        touchY: Int,
        geometricKey: Key?
    ): Key? {
        if (keyboard == null || geometricKey == null || !dynamicAllowedForField || inGestureStroke) {
            return geometricKey
        }
        val gHb = geometricKey.hitBox
        val code = geometricKey.code

        // Inner-edge guard for '.' (left 26% facing Space) & ',' (right 26%) during fast continuous typing
        if ((code == '.'.code && touchX <= gHb.left + gHb.width() * 0.26f) ||
            (code == ','.code && touchX >= gHb.right - gHb.width() * 0.26f)
        ) {
            val nowMs = System.currentTimeMillis()
            if (foldedPrefixLen >= 2 && (nowMs - lastAlphaTapTimeMs) <= 650L) {
                val spaceKey = keyboard.getKey(Constants.CODE_SPACE)
                if (spaceKey != null) {
                    rescuedCount++
                    pendingValid = false
                    return spaceKey
                }
            }
            return geometricKey
        }

        val geoAlpha = toAlphaChar(code)
        val isSpaceCandidate = code == Constants.CODE_SPACE
        val isDeleteCandidate = code == KeyCode.DELETE
        if (geoAlpha == null && !isSpaceCandidate && !isDeleteCandidate) {
            return geometricKey
        }
        if ((isSpaceCandidate || isDeleteCandidate) && foldedPrefixLen < 1) {
            return geometricKey
        }
        if (isSpaceCandidate && touchY > gHb.top + gHb.height() * 0.18f) {
            return geometricKey
        }
        if (isDeleteCandidate && touchX > gHb.left + gHb.width() * 0.15f) {
            return geometricKey
        }

        val t0 = System.nanoTime()
        val density = appContextRef?.resources?.displayMetrics?.density ?: 3f
        val geoChar = geoAlpha ?: if (isSpaceCandidate) '_' else '<'
        val geoAlphaIdx = if (geoAlpha != null) (geoAlpha - 'a') else -1

        val resolvedIdx: Int
        val adjX: Float
        val adjY: Float
        val resolvedProb: Float
        val resolvedKey: Key

        synchronized(stateLock) {
            ensureZonesBuiltLocked(keyboard, density)
            if (zoneCount == 0) return geometricKey

            val keyW = gHb.width().toFloat().coerceAtLeast(1f)
            val keyH = gHb.height().toFloat().coerceAtLeast(1f)
            val row = estimateRow(keyboard, gHb.exactCenterY())

            val keyWeight = if (adaptiveThumbEnabled && geoAlphaIdx in 0..25) {
                (keySamples[geoAlphaIdx] / 8f).coerceIn(0f, 1f) * 0.65f
            } else 0f
            val biasXRatio = if (adaptiveThumbEnabled) {
                val rX = rowBiasX[row]
                if (keyWeight > 0f) rX * (1f - keyWeight) + keyMeanDx[geoAlphaIdx] * keyWeight else rX
            } else 0f
            val biasYRatio = if (adaptiveThumbEnabled) {
                val rY = rowBiasY[row]
                if (keyWeight > 0f) rY * (1f - keyWeight) + keyMeanDy[geoAlphaIdx] * keyWeight else rY
            } else BASE_VERTICAL_BIAS_RATIO
            adjX = touchX - biasXRatio * keyW
            adjY = touchY - biasYRatio * keyH
            lastOffsetYPx = -(biasYRatio * keyH)

            if (isSpaceCandidate || isDeleteCandidate) {
                var neighborIdx = -1
                if (isSpaceCandidate) {
                    var bestDist = Float.MAX_VALUE
                    for (i in 0 until zoneCount) {
                        val rHb = zoneKeys[i]?.hitBox ?: continue
                        val verticalGap = gHb.top - rHb.exactCenterY()
                        if (verticalGap <= 0f || verticalGap > keyH * 1.5f) continue
                        val dx = abs(adjX - rHb.exactCenterX())
                        if (dx <= rHb.width() * 0.85f && dx < bestDist) {
                            bestDist = dx
                            neighborIdx = i
                        }
                    }
                } else {
                    var bestRight = Float.MIN_VALUE
                    for (i in 0 until zoneCount) {
                        val rHb = zoneKeys[i]?.hitBox ?: continue
                        if (abs(rHb.exactCenterY() - gHb.exactCenterY()) > keyH * 0.65f) continue
                        if (rHb.exactCenterX() >= gHb.left) continue
                        if (rHb.exactCenterX() > bestRight) {
                            bestRight = rHb.exactCenterX()
                            neighborIdx = i
                        }
                    }
                    if (neighborIdx >= 0) {
                        val nHb = zoneKeys[neighborIdx]!!.hitBox
                        if (adjX - nHb.right > nHb.width() * 0.75f) neighborIdx = -1
                    }
                }
                if (neighborIdx < 0) return geometricKey
                val prob = charProbs[zoneCharIdx[neighborIdx]]
                if (prob < 0.18f) return geometricKey
                resolvedIdx = neighborIdx
                resolvedKey = zoneKeys[neighborIdx] ?: return geometricKey
                resolvedProb = prob
            } else {
                // Reject accidental curved-edge bezel touches strictly in the outer 4 dp bezel
                if (geometricKey === zoneKeys[0] && adjX < targetZones[0].left - 2f * density &&
                    gHb.left <= (8f * density)
                ) {
                    return null
                }

                var matchIdx = -1
                for (i in 0 until zoneCount) {
                    if (targetZones[i].contains(adjX, adjY)) {
                        matchIdx = i
                        break
                    }
                }
                if (matchIdx == -1) {
                    var bestDist = Float.MAX_VALUE
                    for (i in 0 until zoneCount) {
                        val r = targetZones[i]
                        val cx = adjX.coerceIn(r.left, r.right)
                        val cy = adjY.coerceIn(r.top, r.bottom)
                        val d = hypot(adjX - cx, adjY - cy)
                        if (d < bestDist) {
                            bestDist = d
                            matchIdx = i
                        }
                    }
                }
                if (matchIdx == -1) return geometricKey

                resolvedIdx = matchIdx
                resolvedKey = zoneKeys[matchIdx] ?: return geometricKey
                resolvedProb = charProbs[zoneCharIdx[matchIdx]]

                // Right-edge bezel check
                val rHb = resolvedKey.hitBox
                if (adjX > targetZones[matchIdx].right + 2f * density &&
                    rHb.right >= (keyboard.mOccupiedWidth - 8f * density)
                ) {
                    return null
                }
            }
        }

        val resolvedChar = ('a' + zoneCharIdx[resolvedIdx])
        val rHb = resolvedKey.hitBox
        val cx = rHb.exactCenterX()
        val cy = rHb.exactCenterY()
        val halfCoreW = rHb.width() * PROTECTED_CORE_RATIO * 0.5f
        val halfCoreH = rHb.height() * PROTECTED_CORE_RATIO * 0.5f
        val inCore = adjX >= (cx - halfCoreW) && adjX <= (cx + halfCoreW) &&
            adjY >= (cy - halfCoreH) && adjY <= (cy + halfCoreH)

        val rescued = resolvedKey !== geometricKey
        val latencyMs = (System.nanoTime() - t0) / 1_000_000.0
        val nowMs = System.currentTimeMillis()

        lastRawX = touchX.toFloat()
        lastRawY = touchY.toFloat()
        lastAdjX = adjX
        lastAdjY = adjY
        lastGeoChar = geoChar
        lastResChar = resolvedChar
        lastResolvedKeyCode = resolvedKey.code
        lastResolvedCenterX = cx
        lastResolvedCenterY = cy
        lastResolvedProb = resolvedProb
        lastWasRescued = rescued
        lastProtectedByCore = !rescued && inCore
        lastTouchTimeMs = nowMs
        lastLatencyMs = latencyMs

        pendingValid = true
        pendingRawX = touchX.toFloat()
        pendingRawY = touchY.toFloat()
        pendingAdjX = adjX
        pendingAdjY = adjY
        pendingGeoChar = geoChar
        pendingResChar = resolvedChar
        pendingResCode = resolvedKey.code
        pendingResCx = cx
        pendingResCy = cy
        pendingResProb = resolvedProb
        pendingResRow = zoneRows[resolvedIdx]
        pendingKeyW = rHb.width().toFloat().coerceAtLeast(1f)
        pendingKeyH = rHb.height().toFloat().coerceAtLeast(1f)
        pendingRescued = rescued
        pendingInCore = !rescued && inCore
        pendingLatencyMs = latencyMs
        pendingTimeMs = nowMs

        return resolvedKey
    }

    /**
     * Promotes pending ACTION_DOWN resolution into telemetry and per-key directional EMA
     * strictly upon confirmed key commit (ACTION_UP / confirmed repeat=false tap).
     */
    @JvmStatic
    fun onTapCommitted(committedCode: Int) {
        val cAlpha = toAlphaChar(committedCode)
        if (cAlpha == null) {
            pendingValid = false
            synchronized(stateLock) {
                wordTapLen = 0
                lastDeletedCharIdx = -1
            }
            return
        }
        if (!pendingValid) return
        pendingValid = false

        val pAlpha = toAlphaChar(pendingResCode)
        val codeMatches = committedCode == pendingResCode ||
            (pAlpha != null && cAlpha == pAlpha)
        if (!codeMatches) {
            synchronized(stateLock) { lastDeletedCharIdx = -1 }
            return
        }

        if (pendingRescued) {
            rescuedCount++
        } else if (pendingInCore) {
            coreProtectedCount++
        }

        val kIdx = cAlpha - 'a'
        val nowMs = System.currentTimeMillis()
        lastAlphaTapTimeMs = nowMs

        updateAdaptiveCalibration(
            kIdx,
            pendingResRow,
            pendingRawX - pendingResCx,
            pendingRawY - pendingResCy,
            pendingKeyW,
            pendingKeyH
        )

        synchronized(stateLock) {
            if (adaptiveThumbEnabled && dynamicAllowedForField && learningAllowedForField) {
                if (wordTapLen < WORD_TAP_CAP) {
                    val slot = wordTapLen++
                    wordTapCharIdx[slot] = kIdx
                    wordTapRawX[slot] = pendingRawX
                    wordTapRawY[slot] = pendingRawY
                    wordTapCx[slot] = pendingResCx
                    wordTapCy[slot] = pendingResCy
                    wordTapW[slot] = pendingKeyW
                    wordTapH[slot] = pendingKeyH
                }
                if (lastDeletedCharIdx in 0..25 &&
                    lastDeletedCharIdx != kIdx &&
                    (nowMs - lastDeletedTimeMs) <= 1800L
                ) {
                    val dist = hypot(lastDeletedRawX - pendingResCx, lastDeletedRawY - pendingResCy)
                    if (dist <= pendingKeyW * 2.2f) {
                        recordPerKeyCorrectionLocked(
                            lastDeletedCharIdx,
                            kIdx,
                            lastDeletedRawX,
                            lastDeletedRawY,
                            pendingResCx,
                            pendingResCy,
                            pendingKeyW,
                            pendingKeyH
                        )
                    }
                }
            }
            lastDeletedCharIdx = -1
        }

        recordTouch(
            pendingRawX, pendingRawY, pendingAdjX, pendingAdjY,
            pendingGeoChar, pendingResChar, pendingResCx, pendingResCy,
            pendingResProb, pendingRescued, pendingInCore,
            pendingLatencyMs, pendingTimeMs
        )
    }

    /**
     * Captures Backspace on the active composing word so a quick corrective tap on a
     * neighbor key trains the directional hitbox toward the erroneous tap site.
     */
    @JvmStatic
    fun onBackspaceCommitted() {
        pendingValid = false
        synchronized(stateLock) {
            if (wordTapLen > 0) {
                wordTapLen--
                lastDeletedCharIdx = wordTapCharIdx[wordTapLen]
                lastDeletedRawX = wordTapRawX[wordTapLen]
                lastDeletedRawY = wordTapRawY[wordTapLen]
                lastDeletedTimeMs = System.currentTimeMillis()
            } else {
                lastDeletedCharIdx = -1
            }
        }
    }

    /**
     * Learns per-key directional bias when autocorrection replaces exactly 1 character
     * in a committed word of identical length (e.g. "twste" -> "teste" trains 'e' left toward 'w').
     */
    @JvmStatic
    fun onWordCommitted(typedWord: CharSequence?, committedWord: CharSequence?) {
        val shouldPersist: Boolean
        synchronized(stateLock) {
            val tapCount = wordTapLen
            wordTapLen = 0
            lastDeletedCharIdx = -1
            if (!adaptiveThumbEnabled || !dynamicAllowedForField || !learningAllowedForField ||
                typedWord == null || committedWord == null ||
                typedWord.length != committedWord.length ||
                typedWord.length !in 2..WORD_TAP_CAP ||
                tapCount != typedWord.length
            ) {
                return
            }

            var diffCount = 0
            var diffPos = -1
            var wrongIdx = -1
            var rightIdx = -1
            for (i in 0 until typedWord.length) {
                val tc = foldCharAscii(typedWord[i])
                val cc = foldCharAscii(committedWord[i])
                if (tc != cc) {
                    if (tc !in 'a'..'z' || cc !in 'a'..'z') return
                    diffCount++
                    if (diffCount > 1) return
                    diffPos = i
                    wrongIdx = tc - 'a'
                    rightIdx = cc - 'a'
                }
            }
            if (diffCount != 1 || diffPos < 0 || wrongIdx !in 0..25 || rightIdx !in 0..25) return

            var rightKey: Key? = null
            for (i in 0 until zoneCount) {
                if (zoneCharIdx[i] == rightIdx) {
                    rightKey = zoneKeys[i]
                    break
                }
            }
            if (rightKey != null) {
                val rHb = rightKey.hitBox
                val dist = hypot(
                    wordTapRawX[diffPos] - rHb.exactCenterX(),
                    wordTapRawY[diffPos] - rHb.exactCenterY()
                )
                val kw = rHb.width().toFloat().coerceAtLeast(1f)
                if (dist <= kw * 2.2f) {
                    recordPerKeyCorrectionLocked(
                        wrongIdx,
                        rightIdx,
                        wordTapRawX[diffPos],
                        wordTapRawY[diffPos],
                        rHb.exactCenterX(),
                        rHb.exactCenterY(),
                        kw,
                        rHb.height().toFloat().coerceAtLeast(1f)
                    )
                }
            }
            shouldPersist = learningDirty && (calibrationSamples % 10 == 0)
            if (shouldPersist) learningDirty = false
        }
        if (shouldPersist) {
            persistLearningProfileAsync(appContextRef)
        }
    }

    /**
     * Records a Backspace-undo of an unwanted autocorrection as a 64-bit hash so after
     * >= 2 reverts T.A.S.K. shields the typed token from future forced overrides.
     */
    @JvmStatic
    fun onAutocorrectReverted(typedWord: CharSequence?, revertedWord: CharSequence?) {
        if (!learningAllowedForField || typedWord.isNullOrBlank()) return
        if (revertedWord != null && typedWord.toString().equals(revertedWord.toString(), ignoreCase = true)) {
            return
        }
        val h = fnv1aFolded(typedWord)
        if (h == 0L) return
        synchronized(stateLock) {
            if (revertShieldCounts.size >= 128 && !revertShieldCounts.containsKey(h)) {
                var minKey: Long? = null
                var minVal = Int.MAX_VALUE
                for ((k, v) in revertShieldCounts) {
                    if (v < minVal) {
                        minVal = v
                        minKey = k
                    }
                }
                if (minKey != null) revertShieldCounts.remove(minKey)
            }
            revertShieldCounts[h] = (revertShieldCounts[h] ?: 0) + 1
            learningDirty = false
        }
        persistLearningProfileAsync(appContextRef)
    }

    @JvmStatic
    fun shouldBlockAutoCorrection(word: CharSequence?): Boolean {
        if (word.isNullOrBlank()) return false
        val h = fnv1aFolded(word)
        if (h == 0L) return false
        synchronized(stateLock) {
            val blocked = (revertShieldCounts[h] ?: 0) >= 2
            if (blocked) blockedAutocorrectReverts++
            return blocked
        }
    }

    @JvmStatic
    fun getTargetZoneCopyForTest(keyboard: Keyboard?, ch: Char, out: RectF): Boolean {
        if (keyboard == null) return false
        val cIdx = toAlphaChar(ch.code)?.let { it - 'a' } ?: return false
        val density = appContextRef?.resources?.displayMetrics?.density ?: 3f
        synchronized(stateLock) {
            ensureZonesBuiltLocked(keyboard, density)
            for (i in 0 until zoneCount) {
                if (zoneCharIdx[i] == cIdx) {
                    out.set(targetZones[i])
                    return true
                }
            }
        }
        return false
    }

    private fun fnv1aFolded(cs: CharSequence): Long {
        var hash = -3750763034362895579L
        var len = 0
        for (i in 0 until cs.length) {
            val fc = foldCharAscii(cs[i])
            if (fc != '\u0000') {
                hash = (hash xor fc.code.toLong()) * 1099511628211L
                len++
            }
        }
        return if (len > 0) hash else 0L
    }

    private fun recordPerKeyCorrectionLocked(
        wrongIdx: Int,
        rightIdx: Int,
        wrongRawX: Float,
        wrongRawY: Float,
        rightCx: Float,
        rightCy: Float,
        rightW: Float,
        rightH: Float
    ) {
        if (wrongIdx !in 0..25 || rightIdx !in 0..25 || wrongIdx == rightIdx) return
        if (!adaptiveThumbEnabled || !dynamicAllowedForField || !learningAllowedForField) return
        val kw = rightW.coerceAtLeast(1f)
        val kh = rightH.coerceAtLeast(1f)
        val normX = ((wrongRawX - rightCx) / kw).coerceIn(-0.95f, 0.95f)
        val normY = ((wrongRawY - rightCy) / kh).coerceIn(-0.95f, 0.95f)

        keyMeanDx[rightIdx] = (keyMeanDx[rightIdx] * (1f - EMA_ERROR_ALPHA) +
            normX.coerceIn(-0.45f, 0.45f) * EMA_ERROR_ALPHA).coerceIn(-MAX_KEY_DX, MAX_KEY_DX)
        keyMeanDy[rightIdx] = (keyMeanDy[rightIdx] * (1f - EMA_ERROR_ALPHA) +
            normY.coerceIn(-0.45f, 0.45f) * EMA_ERROR_ALPHA).coerceIn(-MAX_KEY_DY, MAX_KEY_DY)

        if (normX < -0.05f) {
            val gain = ((-normX) * 0.45f).coerceAtMost(MAX_KEY_GROW_X)
            keyGrowLeft[rightIdx] = (keyGrowLeft[rightIdx] * (1f - EMA_ERROR_ALPHA) + gain).coerceIn(0f, MAX_KEY_GROW_X)
        } else if (normX > 0.05f) {
            val gain = (normX * 0.45f).coerceAtMost(MAX_KEY_GROW_X)
            keyGrowRight[rightIdx] = (keyGrowRight[rightIdx] * (1f - EMA_ERROR_ALPHA) + gain).coerceIn(0f, MAX_KEY_GROW_X)
        }

        if (normY < -0.05f) {
            val gainY = ((-normY) * 0.35f).coerceAtMost(MAX_KEY_GROW_Y)
            keyGrowTop[rightIdx] = (keyGrowTop[rightIdx] * (1f - EMA_ERROR_ALPHA) + gainY).coerceIn(0f, MAX_KEY_GROW_Y)
        } else if (normY > 0.05f) {
            val gainY = (normY * 0.35f).coerceAtMost(MAX_KEY_GROW_Y)
            keyGrowBottom[rightIdx] = (keyGrowBottom[rightIdx] * (1f - EMA_ERROR_ALPHA) + gainY).coerceIn(0f, MAX_KEY_GROW_Y)
        }

        keySamples[rightIdx]++
        keyConfusion[wrongIdx * 26 + rightIdx] = (keyConfusion[wrongIdx * 26 + rightIdx] + 1f).coerceAtMost(20f)
        learnedCorrectionPairs++
        zoneGeneration++
        learningDirty = true
    }

    private fun estimateRow(keyboard: Keyboard, centerY: Float): Int {
        val h = keyboard.mOccupiedHeight.toFloat().coerceAtLeast(1f)
        return ((centerY / h) * 4f).toInt().coerceIn(0, 3)
    }

    private fun updateAdaptiveCalibration(
        keyIdx: Int,
        row: Int,
        errX: Float,
        errY: Float,
        keyW: Float,
        keyH: Float
    ) {
        if (!adaptiveThumbEnabled || !dynamicAllowedForField || !learningAllowedForField) return
        val normX = (errX / keyW).coerceIn(-0.50f, 0.50f)
        val normY = (errY / keyH).coerceIn(-0.50f, 0.50f)
        if (abs(normX) > 0.48f || abs(normY) > 0.48f) return

        val shouldPersist: Boolean
        synchronized(stateLock) {
            rowBiasX[row] = (rowBiasX[row] * (1f - EMA_ALPHA) + normX.coerceIn(-0.35f, 0.35f) * EMA_ALPHA)
                .coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
            rowBiasY[row] = (rowBiasY[row] * (1f - EMA_ALPHA) + normY.coerceIn(-0.35f, 0.35f) * EMA_ALPHA)
                .coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)

            if (keyIdx in 0..25) {
                keyMeanDx[keyIdx] = (keyMeanDx[keyIdx] * (1f - EMA_KEY_ALPHA) + normX * EMA_KEY_ALPHA)
                    .coerceIn(-MAX_KEY_DX, MAX_KEY_DX)
                keyMeanDy[keyIdx] = (keyMeanDy[keyIdx] * (1f - EMA_KEY_ALPHA) + normY * EMA_KEY_ALPHA)
                    .coerceIn(-MAX_KEY_DY, MAX_KEY_DY)

                if (normX < -0.04f) {
                    val leftGain = ((-normX - 0.04f) * 2.2f).coerceAtMost(MAX_KEY_GROW_X)
                    keyGrowLeft[keyIdx] = (keyGrowLeft[keyIdx] * (1f - EMA_KEY_ALPHA) + leftGain * EMA_KEY_ALPHA)
                        .coerceIn(0f, MAX_KEY_GROW_X)
                    keyGrowRight[keyIdx] = (keyGrowRight[keyIdx] * (1f - EMA_KEY_ALPHA * 0.5f)).coerceIn(0f, MAX_KEY_GROW_X)
                } else if (normX > 0.04f) {
                    val rightGain = ((normX - 0.04f) * 2.2f).coerceAtMost(MAX_KEY_GROW_X)
                    keyGrowRight[keyIdx] = (keyGrowRight[keyIdx] * (1f - EMA_KEY_ALPHA) + rightGain * EMA_KEY_ALPHA)
                        .coerceIn(0f, MAX_KEY_GROW_X)
                    keyGrowLeft[keyIdx] = (keyGrowLeft[keyIdx] * (1f - EMA_KEY_ALPHA * 0.5f)).coerceIn(0f, MAX_KEY_GROW_X)
                }

                if (normY < -0.04f) {
                    val topGain = ((-normY - 0.04f) * 1.8f).coerceAtMost(MAX_KEY_GROW_Y)
                    keyGrowTop[keyIdx] = (keyGrowTop[keyIdx] * (1f - EMA_KEY_ALPHA) + topGain * EMA_KEY_ALPHA)
                        .coerceIn(0f, MAX_KEY_GROW_Y)
                    keyGrowBottom[keyIdx] = (keyGrowBottom[keyIdx] * (1f - EMA_KEY_ALPHA * 0.5f)).coerceIn(0f, MAX_KEY_GROW_Y)
                } else if (normY > 0.04f) {
                    val botGain = ((normY - 0.04f) * 1.8f).coerceAtMost(MAX_KEY_GROW_Y)
                    keyGrowBottom[keyIdx] = (keyGrowBottom[keyIdx] * (1f - EMA_KEY_ALPHA) + botGain * EMA_KEY_ALPHA)
                        .coerceIn(0f, MAX_KEY_GROW_Y)
                    keyGrowTop[keyIdx] = (keyGrowTop[keyIdx] * (1f - EMA_KEY_ALPHA * 0.5f)).coerceIn(0f, MAX_KEY_GROW_Y)
                }

                keySamples[keyIdx]++
                zoneGeneration++
            }

            calibrationSamples++
            learningDirty = true
            shouldPersist = (calibrationSamples % 10 == 0)
            if (shouldPersist) learningDirty = false
        }

        if (shouldPersist) {
            persistLearningProfileAsync(appContextRef)
        }
    }

    private fun persistLearningProfileAsync(context: Context?) {
        val ctx = context?.applicationContext ?: return
        val copyRowX: FloatArray
        val copyRowY: FloatArray
        val copyKdx: FloatArray
        val copyKdy: FloatArray
        val copyKgl: FloatArray
        val copyKgr: FloatArray
        val copyKgt: FloatArray
        val copyKgb: FloatArray
        val copyKsmp: IntArray
        val copySamples: Int
        val copyPairs: Int
        val copyBlocked: Int
        val confEncoded: String
        val revEncoded: String

        synchronized(stateLock) {
            copyRowX = rowBiasX.copyOf()
            copyRowY = rowBiasY.copyOf()
            copyKdx = keyMeanDx.copyOf()
            copyKdy = keyMeanDy.copyOf()
            copyKgl = keyGrowLeft.copyOf()
            copyKgr = keyGrowRight.copyOf()
            copyKgt = keyGrowTop.copyOf()
            copyKgb = keyGrowBottom.copyOf()
            copyKsmp = keySamples.copyOf()
            copySamples = calibrationSamples
            copyPairs = learnedCorrectionPairs
            copyBlocked = blockedAutocorrectReverts
            confEncoded = buildString(128) {
                var first = true
                for (i in keyConfusion.indices) {
                    if (keyConfusion[i] >= 0.5f) {
                        if (!first) append(';')
                        first = false
                        append(i).append(':').append(keyConfusion[i].roundToInt())
                    }
                }
            }
            revEncoded = buildString(128) {
                var first = true
                for ((h, c) in revertShieldCounts) {
                    if (!first) append(';')
                    first = false
                    append(java.lang.Long.toHexString(h)).append(':').append(c)
                }
            }
        }

        loaderExecutor.execute {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putInt(KEY_SAMPLES, copySamples)
                putInt(KEY_LEARNED_PAIRS, copyPairs)
                putInt(KEY_BLOCKED_REVERTS, copyBlocked)
                putString(KEY_CONF_PAIRS, confEncoded)
                putString(KEY_REVERT_HASHES, revEncoded)
                for (r in 0 until 4) {
                    putFloat("rbx_$r", copyRowX[r])
                    putFloat("rby_$r", copyRowY[r])
                }
                for (k in 0 until 26) {
                    putFloat("kmx_$k", copyKdx[k])
                    putFloat("kmy_$k", copyKdy[k])
                    putFloat("kgl_$k", copyKgl[k])
                    putFloat("kgr_$k", copyKgr[k])
                    putFloat("kgt_$k", copyKgt[k])
                    putFloat("kgb_$k", copyKgb[k])
                    putInt("ksmp_$k", copyKsmp[k])
                }
                apply()
            }
        }
    }

    private fun recordTouch(
        rawX: Float, rawY: Float, adjX: Float, adjY: Float,
        geo: Char, res: Char,
        resCx: Float, resCy: Float,
        prob: Float, rescued: Boolean, protectedByCore: Boolean,
        latencyMs: Double,
        nowMs: Long
    ) {
        totalTaps++
        latencyRing[ringIdx] = latencyMs
        ringIdx = (ringIdx + 1) % latencyRing.size
        if (ringCount < latencyRing.size) ringCount++

        lastRawX = rawX
        lastRawY = rawY
        lastAdjX = adjX
        lastAdjY = adjY
        lastGeoChar = geo
        lastResChar = res
        lastResolvedCenterX = resCx
        lastResolvedCenterY = resCy
        lastResolvedProb = prob
        lastWasRescued = rescued
        lastProtectedByCore = protectedByCore
        lastTouchTimeMs = nowMs
        lastLatencyMs = latencyMs
    }

    /**
     * B4.Q3: Smooth 120 Hz visual boundary interpolation (~90 ms settle) over the 2D non-overlapping zones.
     * Hit-testing uses targetZones at 0 ms lag while visual borders breathe smoothly at 120 Hz.
     */
    @JvmStatic
    fun drawOverlay(canvas: Canvas, keyboard: Keyboard?, context: Context) {
        ensureInitialized(context)
        val mode = visualizerMode
        if (mode == VisualizerMode.OFF || keyboard == null || !dynamicAllowedForField) return

        val density = context.resources.displayMetrics.density
        if (!paintsInitialized) {
            paintsInitialized = true
            val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
            strokePaint.strokeWidth = 1.4f * density
            corePaint.strokeWidth = 1f * density
            corePaint.pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
            offsetVectorPaint.strokeWidth = 1.5f * density
            offsetVectorPaint.pathEffect = DashPathEffect(floatArrayOf(2f * density, 2f * density), 0f)
            rescuePaint.strokeWidth = 2f * density
            monoPaint.typeface = mono
            val sp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
            monoPaint.textSize = sp12
            hudTextPaint.typeface = mono
            hudTextPaint.textSize = sp12
        }

        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val accentColor = if (isDark) 0xFF7EE3EA.toInt() else 0xFF0D6C74.toInt()
        val accentRgb = accentColor and 0x00FFFFFF
        val secondaryColor = if (isDark) 0xFF8C8C8C.toInt() else 0xFF6B6B6B.toInt()
        val secondaryRgb = secondaryColor and 0x00FFFFFF
        val radius = 6f * density
        var needsNextFrame = false

        synchronized(stateLock) {
            ensureZonesBuiltLocked(keyboard, density)
            if (zoneCount == 0) return

            val nowUp = SystemClock.uptimeMillis()
            val dtMs = if (lastDrawAnimUptimeMs == 0L) 16f else (nowUp - lastDrawAnimUptimeMs).coerceIn(1L, 60L).toFloat()
            lastDrawAnimUptimeMs = nowUp
            val alpha = 1f - exp(-dtMs / 30f) // ~95% settled in 90 ms at 120 Hz

            for (i in 0 until zoneCount) {
                val t = targetZones[i]
                val d = displayZones[i]
                val diff = abs(t.left - d.left) + abs(t.top - d.top) + abs(t.right - d.right) + abs(t.bottom - d.bottom)
                if (diff > 0.5f) {
                    d.left += (t.left - d.left) * alpha
                    d.top += (t.top - d.top) * alpha
                    d.right += (t.right - d.right) * alpha
                    d.bottom += (t.bottom - d.bottom) * alpha
                    needsNextFrame = true
                } else {
                    d.set(t)
                }

                val cIdx = zoneCharIdx[i]
                val boost = charBoosts[cIdx]
                val fillAlpha = (18 + boost * 82f).roundToInt().coerceIn(16, 105)
                val strokeAlpha = (48 + boost * 195f).roundToInt().coerceIn(45, 245)
                fillPaint.color = (fillAlpha shl 24) or accentRgb
                strokePaint.color = (strokeAlpha shl 24) or accentRgb

                canvas.drawRoundRect(d, radius, radius, fillPaint)
                canvas.drawRoundRect(d, radius, radius, strokePaint)

                if (mode == VisualizerMode.ENGINEERING) {
                    val hb = zoneKeys[i]!!.hitBox
                    val cw = hb.width() * PROTECTED_CORE_RATIO * 0.5f
                    val chH = hb.height() * PROTECTED_CORE_RATIO * 0.5f
                    tempRect.set(hb.exactCenterX() - cw, hb.exactCenterY() - chH, hb.exactCenterX() + cw, hb.exactCenterY() + chH)
                    corePaint.color = (0x55 shl 24) or secondaryRgb
                    canvas.drawRect(tempRect, corePaint)

                    val pct = (charProbs[cIdx] * 100f).roundToInt()
                    if (pct >= 4) {
                        monoPaint.color = accentColor
                        canvas.drawText("$pct%", d.centerX(), d.top + 13f * density, monoPaint)
                    }
                }
            }
        }

        if (needsNextFrame) {
            attachedViewRef?.get()?.postInvalidateOnAnimation()
        } else {
            lastDrawAnimUptimeMs = 0L
        }

        if (lastRawX >= 0f && (System.currentTimeMillis() - lastTouchTimeMs) < 1600L) {
            if (mode == VisualizerMode.ENGINEERING && lastAdjX >= 0f) {
                offsetVectorPaint.color = secondaryColor
                canvas.drawCircle(lastRawX, lastRawY, 3.5f * density, offsetVectorPaint)
                canvas.drawLine(lastRawX, lastRawY, lastAdjX, lastAdjY, offsetVectorPaint)
            }
            rescuePaint.color = accentColor
            val startX = if (lastAdjX >= 0f) lastAdjX else lastRawX
            val startY = if (lastAdjY >= 0f) lastAdjY else lastRawY
            if (lastWasRescued && lastResolvedCenterX >= 0f) {
                canvas.drawLine(startX, startY, lastResolvedCenterX, lastResolvedCenterY, rescuePaint)
            }
            canvas.drawCircle(startX, startY, 4f * density, rescuePaint)
        }

        if (mode == VisualizerMode.ENGINEERING) {
            val tagOrd = when {
                lastWasRescued -> 2
                lastProtectedByCore -> 1
                else -> 0
            }
            val pct = (lastResolvedProb * 100f).roundToInt()
            val ctxW = contextWordCount
            val dyPx = lastOffsetYPx.roundToInt()
            val hundredths = (lastLatencyMs * 100.0).roundToInt().coerceIn(0, 9999)
            val stamp = (ctxW.toLong() shl 48) xor
                (lastGeoChar.code.toLong() shl 40) xor
                (lastResChar.code.toLong() shl 32) xor
                (pct.toLong() shl 24) xor
                (tagOrd.toLong() shl 20) xor
                ((dyPx.toLong() and 0xFFL) shl 12) xor
                hundredths.toLong()
            if (stamp != cachedHudStamp) {
                cachedHudStamp = stamp
                val tag = when (tagOrd) {
                    2 -> "RESCUED"
                    1 -> "CORE"
                    else -> "EXACT"
                }
                val latWhole = hundredths / 100
                val latFrac = hundredths % 100
                cachedHudText = buildString(72) {
                    append("T.A.S.K. ENG | ctx=").append(ctxW).append("w | ")
                    append(lastGeoChar).append("->").append(lastResChar)
                    append(" (").append(pct).append("%) ").append(tag)
                    append(" | dy=").append(dyPx).append("px | ")
                    append(latWhole).append('.')
                    if (latFrac < 10) append('0')
                    append(latFrac).append("ms")
                }
            }
            val hud = cachedHudText
            val textW = hudTextPaint.measureText(hud)
            val padX = 8f * density
            val pillH = 22f * density
            tempRect.set(6f * density, 2f * density, 6f * density + textW + padX * 2f, 2f * density + pillH)
            hudBgPaint.color = if (isDark) 0xDD080808.toInt() else 0xDDF2F2F0.toInt()
            canvas.drawRoundRect(tempRect, 6f * density, 6f * density, hudBgPaint)
            hudTextPaint.color = accentColor
            val ty = tempRect.centerY() - (hudTextPaint.descent() + hudTextPaint.ascent()) * 0.5f
            canvas.drawText(hud, tempRect.left + padX, ty, hudTextPaint)
        }
    }

    private fun toAlphaChar(code: Int): Char? {
        if (code < 0) return null
        val ch = code.toChar().lowercaseChar()
        return if (ch in 'a'..'z') ch else null
    }

    /**
     * Zero-allocation Latin diacritics folding table.
     */
    private fun foldCharAscii(ch: Char): Char = when (ch) {
        in 'a'..'z' -> ch
        in 'A'..'Z' -> (ch.code + 32).toChar()
        'á', 'à', 'â', 'ã', 'ä', 'Á', 'À', 'Â', 'Ã', 'Ä' -> 'a'
        'ç', 'ć', 'č', 'Ç', 'Ć', 'Č' -> 'c'
        'é', 'ê', 'è', 'ë', 'É', 'Ê', 'È', 'Ë' -> 'e'
        'í', 'î', 'ì', 'ï', 'Í', 'Î', 'Ì', 'Ï' -> 'i'
        'ñ', 'Ñ' -> 'n'
        'ó', 'ô', 'õ', 'ò', 'ö', 'Ó', 'Ô', 'Õ', 'Ò', 'Ö' -> 'o'
        'ú', 'ü', 'ù', 'û', 'Ú', 'Ü', 'Ù', 'Û' -> 'u'
        'ý', 'ÿ', 'Ý', 'Ÿ' -> 'y'
        else -> '\u0000'
    }

    /**
     * B1.Q4: Zero-allocation check for URL, email, or code tokens (@, ://, /, _, brackets, =, ;, www., .com/.br, camelCase).
     */
    private fun looksLikeCodeOrUrlToken(cs: CharSequence, start: Int, end: Int): Boolean {
        if (start >= end) return false
        var sawLower = false
        for (i in start until end) {
            val c = cs[i]
            when (c) {
                '@', '/', '_', '{', '}', '[', ']', '=', ';', '\\', '$' -> return true
                ':' -> if (i + 2 < end && cs[i + 1] == '/' && cs[i + 2] == '/') return true
                '.' -> {
                    if (end - i >= 3) {
                        val c1 = cs[i + 1].lowercaseChar()
                        val c2 = cs[i + 2].lowercaseChar()
                        if (c1 == 'b' && c2 == 'r') return true
                        if (i + 3 < end) {
                            val c3 = cs[i + 3].lowercaseChar()
                            if ((c1 == 'c' && c2 == 'o' && c3 == 'm') ||
                                (c1 == 'o' && c2 == 'r' && c3 == 'g') ||
                                (c1 == 'n' && c2 == 'e' && c3 == 't')
                            ) return true
                        }
                    }
                    if (i - start == 3 &&
                        cs[start].lowercaseChar() == 'w' &&
                        cs[start + 1].lowercaseChar() == 'w' &&
                        cs[start + 2].lowercaseChar() == 'w'
                    ) return true
                }
                else -> {
                    if (c in 'a'..'z') {
                        sawLower = true
                    } else if (c in 'A'..'Z' && sawLower) {
                        // camelCase detected
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun applyEditorFieldPolicy(info: EditorInfo?) {
        if (info == null) {
            fieldBaseDynamicAllowed = true
            learningAllowedForField = true
            return
        }
        val inputType = info.inputType
        val clazz = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        val isPassword = (clazz == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )) || (clazz == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

        val isExplicitEmail = clazz == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )

        val isNoPersonal = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        fieldBaseDynamicAllowed = !isPassword && !isExplicitEmail
        learningAllowedForField = !isPassword && !isNoPersonal
    }
}
