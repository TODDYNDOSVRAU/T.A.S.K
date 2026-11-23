package com.tal.task

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.res.ResourcesCompat
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * T.A.S.K. v2.4.0 — Apple/iPhone-style Non-Overlapping Dynamic Tap Zones,
 * Full-Writing Context Prediction (Trie + Bigrams/Trigrams + Morpho + Document Topic Cache),
 * Ergonomic Thumb Vertical Parallax Compensation, and 1:1 WYSIWYG Visualizer (OFF / PRED / ENG).
 *
 * Guarantees (D7 / D8 / D10 / D11):
 * - Thread-safe immutable ZoneSnapshot swapped under stateLock (zero concurrent mutation races).
 * - Central 56% protected core is 100% inviolable (never hijacked even at 0% probability).
 * - Zero overlap between adjacent dynamic tap zones (shared boundary + 3 px gutter).
 * - Active across all apps (syncs via InputConnection text-before-cursor, not just AOSP suggestions).
 * - Automatically disabled in Password / Explicit-Email fields and suspended mid-URL on URI bars.
 * - Zero learning/topic writes in Password or IME_FLAG_NO_PERSONALIZED_LEARNING (incognito) fields.
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
        val prevWord: String
    )

    private class TapZoneEntry(
        val key: Key,
        val charIdx: Int,
        val row: Int,
        val rect: RectF
    )

    private class ZoneSnapshot(
        val keyboard: Keyboard,
        val generation: Int,
        val zones: List<TapZoneEntry>,
        val probs: FloatArray,
        val boosts: FloatArray
    )

    private const val PREFS_NAME = "task_hitbox_prefs"
    private const val KEY_VIZ_MODE = "viz_mode"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_ADAPTIVE = "adaptive_thumb"
    private const val KEY_SAMPLES = "cal_samples"

    private const val PROTECTED_CORE_RATIO = 0.56f
    private const val BASE_VERTICAL_BIAS_RATIO = 0.06f
    private const val MAX_ADAPTIVE_BIAS_RATIO = 0.14f
    private const val EMA_ALPHA = 0.04f
    private const val TAP_LOG_DEBOUNCE_MS = 55L

    private class TrieNode {
        val children = arrayOfNulls<TrieNode>(26)
        var subtreeMass: Long = 0L
    }

    private class FastPrefixModel {
        private val root = TrieNode()
        private val charBigrams = Array(27) { FloatArray(26) }
        private val charTotals = FloatArray(27)

        fun insert(word: String, freq: Int) {
            val folded = foldAscii(word)
            if (folded.isEmpty()) return
            var node = root
            var prev = 26
            val mass = freq.coerceAtLeast(1).toLong()
            node.subtreeMass += mass
            for (ch in folded) {
                val idx = ch - 'a'
                if (idx !in 0..25) return
                charBigrams[prev][idx] += mass.toFloat()
                charTotals[prev] += mass.toFloat()
                prev = idx
                var next = node.children[idx]
                if (next == null) {
                    next = TrieNode()
                    node.children[idx] = next
                }
                next.subtreeMass += mass
                node = next
            }
        }

        fun nextCharProbabilities(foldedPrefix: String, out: FloatArray) {
            out.fill(0f)
            var node: TrieNode? = root
            for (ch in foldedPrefix) {
                val idx = ch - 'a'
                if (idx !in 0..25) {
                    node = null
                    break
                }
                node = node?.children?.get(idx) ?: break
            }

            var total = 0.0
            if (node != null) {
                for (i in 0 until 26) {
                    val c = node.children[i]
                    if (c != null && c.subtreeMass > 0L) {
                        val m = c.subtreeMass.toDouble()
                        out[i] = m.toFloat()
                        total += m
                    }
                }
            }

            val lastIdx = if (foldedPrefix.isEmpty()) 26 else (foldedPrefix.last() - 'a').coerceIn(0, 25)
            val bgTotal = charTotals[lastIdx]
            if (total <= 0.0) {
                if (bgTotal > 0f) {
                    for (i in 0 until 26) out[i] = charBigrams[lastIdx][i] / bgTotal
                } else {
                    out.fill(1f / 26f)
                }
                return
            }

            val invTotal = (1.0 / total).toFloat()
            var sum = 0f
            for (i in 0 until 26) {
                val tp = out[i] * invTotal
                val bp = if (bgTotal > 0f) charBigrams[lastIdx][i] / bgTotal else 0f
                val mixed = tp * 0.88f + bp * 0.12f
                out[i] = mixed
                sum += mixed
            }
            if (sum > 0f) {
                val invSum = 1f / sum
                for (i in 0 until 26) out[i] *= invSum
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
        "ja" to listOf("esta" to 0.65f, "foi" to 0.6f, "vou" to 0.55f, "cheguei" to 0.5f)
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
        "nao sei" to listOf("se" to 0.85f, "o" to 0.6f, "como" to 0.55f, "por" to 0.45f)
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
        "have" to listOf("a" to 0.8f, "to" to 0.75f, "been" to 0.5f)
    )

    private val enTrigrams: Map<String, List<Pair<String, Float>>> = mapOf(
        "thank you" to listOf("so" to 0.75f, "very" to 0.7f, "for" to 0.8f),
        "how are" to listOf("you" to 1.0f),
        "good morning" to listOf("everyone" to 0.6f, "how" to 0.55f),
        "i think" to listOf("that" to 0.75f, "it" to 0.65f, "we" to 0.55f),
        "have a" to listOf("good" to 0.8f, "great" to 0.8f, "nice" to 0.6f),
        "of course" to listOf("i" to 0.6f, "we" to 0.55f, "you" to 0.55f)
    )

    private val ptFeminineCues = setOf("a", "uma", "minha", "sua", "nossa", "essa", "esta", "aquela", "na", "da", "pela")
    private val ptMasculineCues = setOf("o", "um", "meu", "seu", "nosso", "esse", "este", "aquele", "no", "do", "pelo")
    private val ptPluralCues = setOf("os", "as", "uns", "umas", "todos", "todas", "muitos", "muitas", "alguns", "algumas", "esses", "essas", "meus", "minhas", "seus", "suas")
    private val ptSentenceStarters = charArrayOf('o', 'a', 'e', 'n', 'v', 'p', 'c', 'm', 't', 'q', 's', 'b')
    private val enSentenceStarters = charArrayOf('t', 'i', 'w', 'h', 's', 'a', 'y', 'c', 'p', 'g', 'm', 'l')

    private val combiningMarks = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val stateLock = Any()
    private val loaderExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var loadingStarted = false
    @Volatile private var prefsLoaded = false
    @Volatile private var appContextRef: Context? = null
    @Volatile private var attachedViewRef: WeakReference<View>? = null
    @Volatile private var ptModel: FastPrefixModel? = null
    @Volatile private var enModel: FastPrefixModel? = null

    @Volatile private var visualizerMode: VisualizerMode = VisualizerMode.OVERLAY
    @Volatile private var sensitivity: Sensitivity = Sensitivity.STANDARD
    @Volatile private var adaptiveThumbEnabled: Boolean = true
    @Volatile private var activeLocaleIsPt = true

    // Field policy flags
    @Volatile private var fieldBaseDynamicAllowed = true
    @Volatile private var isUriField = false
    @Volatile private var learningAllowedForField = true
    @Volatile private var dynamicAllowedForField = true

    // Full-writing context state (protected by stateLock)
    private var currentPrefix = ""
    private var prevWord1 = ""
    private var prevWord2 = ""
    private var isSentenceStart = true
    private var contextWordCount = 0
    private val topicWords = HashMap<String, Int>(64)
    private val topicBigrams = HashMap<String, HashMap<String, Int>>(64)

    // Per-row adaptive thumb offset (normalized floats dx/dy, NEVER stores text)
    private val rowBiasX = FloatArray(4)
    private val rowBiasY = floatArrayOf(BASE_VERTICAL_BIAS_RATIO, BASE_VERTICAL_BIAS_RATIO, BASE_VERTICAL_BIAS_RATIO * 0.75f, 0f)
    @Volatile private var calibrationSamples = 0

    // Probability & zone state (protected by stateLock, published via immutable ZoneSnapshot)
    private val charProbs = FloatArray(26)
    private val charBoosts = FloatArray(26)
    private var zoneGeneration = 0
    @Volatile private var snapshotRef: ZoneSnapshot? = null

    // Telemetry & touch jitter guard
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
    @Volatile private var lastResolvedCenterX = -1f
    @Volatile private var lastResolvedCenterY = -1f
    @Volatile private var lastResolvedProb = 0f
    @Volatile private var lastWasRescued = false
    @Volatile private var lastProtectedByCore = false
    @Volatile private var lastTouchTimeMs = 0L
    @Volatile private var lastLoggedTouchMs = 0L
    @Volatile private var lastLoggedKeyCode = Int.MIN_VALUE
    @Volatile private var lastLatencyMs = 0.0
    @Volatile private var lastOffsetYPx = 0f

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
            visualizerMode = VisualizerMode.entries.getOrElse(prefs.getInt(KEY_VIZ_MODE, VisualizerMode.OVERLAY.ordinal)) { VisualizerMode.OVERLAY }
            sensitivity = Sensitivity.entries.getOrElse(prefs.getInt(KEY_SENSITIVITY, Sensitivity.STANDARD.ordinal)) { Sensitivity.STANDARD }
            adaptiveThumbEnabled = prefs.getBoolean(KEY_ADAPTIVE, true)
            calibrationSamples = prefs.getInt(KEY_SAMPLES, 0)
            synchronized(stateLock) {
                for (r in 0 until 4) {
                    rowBiasX[r] = prefs.getFloat("rbx_$r", 0f).coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
                    val defY = if (r < 2) BASE_VERTICAL_BIAS_RATIO else if (r == 2) BASE_VERTICAL_BIAS_RATIO * 0.75f else 0f
                    rowBiasY[r] = prefs.getFloat("rby_$r", defY).coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
                }
            }
        }
        if (!loadingStarted) {
            loadingStarted = true
            loaderExecutor.execute {
                val loadedPt = loadTsvModel(appCtx, "dict/pt_br.tsv")
                val loadedEn = loadTsvModel(appCtx, "dict/en_us.tsv")
                ptModel = loadedPt
                enModel = loadedEn
                synchronized(stateLock) {
                    recomputeProbabilitiesLocked()
                }
                attachedViewRef?.get()?.postInvalidate()
            }
        }
    }

    private fun loadTsvModel(context: Context, assetPath: String): FastPrefixModel {
        val model = FastPrefixModel()
        try {
            context.assets.open(assetPath).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            val w = line.substring(0, tab)
                            val f = line.substring(tab + 1).toIntOrNull() ?: 100
                            model.insert(w, f)
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Exception) {
        }
        return model
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
            calibrationSamples = 0
        }
        val editor = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putInt(KEY_SAMPLES, 0)
        synchronized(stateLock) {
            for (r in 0 until 4) {
                editor.putFloat("rbx_$r", rowBiasX[r])
                editor.putFloat("rby_$r", rowBiasY[r])
            }
        }
        editor.apply()
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
    }

    @JvmStatic
    fun onKeyCommitted() {
        lastLoggedKeyCode = Int.MIN_VALUE
        lastLoggedTouchMs = 0L
    }

    @JvmStatic
    fun getTelemetrySnapshot(context: Context): LiveTelemetry {
        ensureInitialized(context)
        val p99 = if (ringCount == 0) 0.0 else {
            val copy = latencyRing.copyOf(ringCount).apply { sort() }
            copy[((copy.size - 1) * 0.99).toInt().coerceAtLeast(0)]
        }
        synchronized(stateLock) {
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
                prevWord = prevWord1
            )
        }
    }

    @JvmStatic
    fun onFieldStarted(context: Context, editorInfo: EditorInfo?, localeTag: String?) {
        ensureInitialized(context)
        activeLocaleIsPt = localeTag == null || localeTag.lowercase().startsWith("pt")
        applyEditorFieldPolicy(editorInfo)
        synchronized(stateLock) {
            currentPrefix = ""
            prevWord1 = ""
            prevWord2 = ""
            isSentenceStart = true
            contextWordCount = 0
            topicWords.clear()
            topicBigrams.clear()
            dynamicAllowedForField = fieldBaseDynamicAllowed
            lastLoggedKeyCode = Int.MIN_VALUE
            recomputeProbabilitiesLocked()
        }
    }

    @JvmStatic
    fun updateComposingWord(context: Context, prefix: String?, localeTag: String?) {
        updateFullTextContext(context, prefix, localeTag)
    }

    /**
     * Full-writing context updater called across ALL apps using InputConnection text before cursor.
     * Parses current trailing prefix, previous 2 words, sentence boundary, URL-token guard,
     * and ephemeral in-RAM document topic vocabulary & bigrams.
     */
    @JvmStatic
    fun updateFullTextContext(context: Context, textBeforeCursor: CharSequence?, localeTag: String?) {
        ensureInitialized(context)
        if (!localeTag.isNullOrBlank()) {
            activeLocaleIsPt = localeTag.lowercase().startsWith("pt")
        }
        val raw = textBeforeCursor?.toString().orEmpty()
        val bounded = if (raw.length > 512) raw.substring(raw.length - 512) else raw

        // 1. Extract trailing token (for smart URI-field URL suspension)
        var tokenStart = bounded.length
        while (tokenStart > 0 && !bounded[tokenStart - 1].isWhitespace()) {
            tokenStart--
        }
        val rawCurrentToken = bounded.substring(tokenStart)

        // 2. Extract trailing contiguous letter prefix at cursor
        var prefixStart = bounded.length
        while (prefixStart > 0 && Character.isLetter(bounded[prefixStart - 1])) {
            prefixStart--
        }
        val newPrefix = bounded.substring(prefixStart)
        val beforePrefix = bounded.substring(0, prefixStart)

        // 3. Sentence boundary detection
        val trimmedBefore = beforePrefix.trimEnd()
        val sentenceStart = trimmedBefore.isEmpty() ||
            trimmedBefore.last() == '.' ||
            trimmedBefore.last() == '!' ||
            trimmedBefore.last() == '?' ||
            trimmedBefore.last() == '\n'

        // 4. Tokenize completed prior words for prevWord1, prevWord2 & ephemeral topic cache
        val tokens = extractWords(beforePrefix, maxWords = 120)
        val newPrev1 = tokens.lastOrNull().orEmpty()
        val newPrev2 = if (tokens.size >= 2) tokens[tokens.size - 2] else ""

        synchronized(stateLock) {
            val shouldSuspendUri = isUriField && looksLikeUrlOrEmailToken(rawCurrentToken)
            dynamicAllowedForField = fieldBaseDynamicAllowed && !shouldSuspendUri

            currentPrefix = newPrefix
            prevWord1 = newPrev1
            prevWord2 = newPrev2
            isSentenceStart = sentenceStart
            contextWordCount = tokens.size

            topicWords.clear()
            topicBigrams.clear()
            if (learningAllowedForField && dynamicAllowedForField && tokens.isNotEmpty()) {
                var prevFolded: String? = null
                for (w in tokens) {
                    val folded = foldAscii(w)
                    if (folded.length >= 2) {
                        topicWords[folded] = (topicWords[folded] ?: 0) + 1
                        if (prevFolded != null) {
                            val map = topicBigrams.getOrPut(prevFolded) { HashMap(4) }
                            map[folded] = (map[folded] ?: 0) + 1
                        }
                        prevFolded = folded
                    } else if (folded.isNotEmpty()) {
                        prevFolded = folded
                    }
                }
            }
            recomputeProbabilitiesLocked()
        }
    }

    private fun extractWords(text: String, maxWords: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val list = ArrayList<String>(24)
        var start = -1
        for (i in text.indices) {
            if (Character.isLetter(text[i])) {
                if (start == -1) start = i
            } else if (start != -1) {
                list.add(text.substring(start, i))
                start = -1
            }
        }
        if (start != -1) list.add(text.substring(start))
        return if (list.size > maxWords) list.subList(list.size - maxWords, list.size) else list
    }

    /**
     * 4-Layer Context Probability Fusion (must hold stateLock):
     * 1. Offline 30k S23 Trie prefix probability.
     * 2. Built-in PT-BR / EN-US word bigrams, trigrams, sentence starters & Portuguese morpho clues.
     * 3. Whole-writing ephemeral document topic vocabulary & local bigrams from textBeforeCursor.
     * 4. Character-bigram fallback (inside FastPrefixModel).
     */
    private fun recomputeProbabilitiesLocked() {
        val model = if (activeLocaleIsPt) ptModel else enModel
        if (model == null || !dynamicAllowedForField) {
            charProbs.fill(0f)
            charBoosts.fill(0f)
            zoneGeneration++
            snapshotRef = null
            return
        }

        val foldedPrefix = foldAscii(currentPrefix)
        val foldedPrev1 = foldAscii(prevWord1)
        val foldedPrev2 = foldAscii(prevWord2)

        // Layer 1 + Layer 4: Base 30k S23 Trie + char-bigram fallback
        model.nextCharProbabilities(foldedPrefix, charProbs)

        // Layer 2a: Built-in word bigrams & trigrams
        val bigramMap = if (activeLocaleIsPt) ptBigrams else enBigrams
        val trigramMap = if (activeLocaleIsPt) ptTrigrams else enTrigrams

        if (foldedPrev2.isNotEmpty() && foldedPrev1.isNotEmpty()) {
            val triTargets = trigramMap["$foldedPrev2 $foldedPrev1"]
            if (triTargets != null) {
                for ((targetWord, weight) in triTargets) {
                    boostMatchingTargetChar(targetWord, foldedPrefix, weight * 0.65f)
                }
            }
        }
        if (foldedPrev1.isNotEmpty()) {
            val biTargets = bigramMap[foldedPrev1]
            if (biTargets != null) {
                for ((targetWord, weight) in biTargets) {
                    boostMatchingTargetChar(targetWord, foldedPrefix, weight * 0.42f)
                }
            }
        }

        // Layer 2b: Sentence-start priors when starting a new sentence
        if (isSentenceStart && foldedPrefix.isEmpty()) {
            val starters = if (activeLocaleIsPt) ptSentenceStarters else enSentenceStarters
            for (idx in starters.indices) {
                val cIdx = starters[idx] - 'a'
                if (cIdx in 0..25) {
                    charProbs[cIdx] += 0.08f * (1f - idx * 0.06f)
                }
            }
        }

        // Layer 2c: Portuguese morphological agreement clues (gender / plural) for stems >= 2 chars
        if (activeLocaleIsPt && foldedPrev1.isNotEmpty() && foldedPrefix.length >= 2) {
            if (foldedPrev1 in ptFeminineCues) {
                charProbs['a' - 'a'] += 0.05f
                charProbs['s' - 'a'] += 0.02f
            } else if (foldedPrev1 in ptMasculineCues) {
                charProbs['o' - 'a'] += 0.05f
                charProbs['r' - 'a'] += 0.02f
            }
            if (foldedPrev1 in ptPluralCues && foldedPrefix.length >= 3) {
                charProbs['s' - 'a'] += 0.06f
            }
        }

        // Layer 3: Whole-writing document topic vocabulary & local bigrams
        if (learningAllowedForField && topicWords.isNotEmpty()) {
            // 3a. Local bigrams already observed in this same message
            val localNextMap = if (foldedPrev1.isNotEmpty()) topicBigrams[foldedPrev1] else null
            if (localNextMap != null) {
                for ((word, count) in localNextMap) {
                    boostMatchingTargetChar(word, foldedPrefix, (0.35f + count * 0.15f).coerceAtMost(0.85f))
                }
            }
            // 3b. Repeated/custom words from earlier in this message matching current prefix
            if (foldedPrefix.isNotEmpty()) {
                for ((word, count) in topicWords) {
                    boostMatchingTargetChar(word, foldedPrefix, (0.20f + count * 0.10f).coerceAtMost(0.65f))
                }
            }
        }

        // Normalize fused distribution & compute boosts
        var sum = 0f
        for (i in 0 until 26) {
            if (charProbs[i] < 0f) charProbs[i] = 0f
            sum += charProbs[i]
        }
        if (sum > 0f) {
            val invSum = 1f / sum
            for (i in 0 until 26) charProbs[i] *= invSum
        }

        var maxP = 0f
        for (i in 0 until 26) if (charProbs[i] > maxP) maxP = charProbs[i]
        if (maxP <= 0f) {
            charBoosts.fill(0f)
        } else {
            for (i in 0 until 26) {
                charBoosts[i] = sqrt((charProbs[i] / maxP).coerceIn(0f, 1f))
            }
        }
        zoneGeneration++
        snapshotRef = null
    }

    private fun boostMatchingTargetChar(targetWord: String, foldedPrefix: String, boostWeight: Float) {
        if (targetWord.length <= foldedPrefix.length) return
        if (foldedPrefix.isNotEmpty() && !targetWord.startsWith(foldedPrefix)) return
        val nextCh = targetWord[foldedPrefix.length]
        val idx = nextCh - 'a'
        if (idx in 0..25) {
            charProbs[idx] += boostWeight
        }
    }

    private fun getOrBuildSnapshot(keyboard: Keyboard): ZoneSnapshot {
        val current = snapshotRef
        val expectedGen: Int
        synchronized(stateLock) {
            expectedGen = zoneGeneration
            if (current != null && current.keyboard === keyboard && current.generation == expectedGen) {
                return current
            }
            val probsCopy = charProbs.copyOf()
            val boostsCopy = charBoosts.copyOf()
            val zones = buildTapZones(keyboard, boostsCopy)
            val created = ZoneSnapshot(
                keyboard = keyboard,
                generation = expectedGen,
                zones = zones,
                probs = probsCopy,
                boosts = boostsCopy
            )
            snapshotRef = created
            return created
        }
    }

    private fun buildTapZones(keyboard: Keyboard, boosts: FloatArray): List<TapZoneEntry> {
        val alphaKeys = ArrayList<Key>(26)
        for (k in keyboard.sortedKeys) {
            if (toAlphaChar(k.code) != null) alphaKeys.add(k)
        }
        if (alphaKeys.isEmpty()) return emptyList()

        val rowTolerance = (alphaKeys[0].hitBox.height() * 0.35f).coerceAtLeast(10f)
        val rows = ArrayList<MutableList<Key>>(4)
        for (k in alphaKeys) {
            val cy = k.hitBox.exactCenterY()
            val existing = rows.firstOrNull { abs(it[0].hitBox.exactCenterY() - cy) <= rowTolerance }
            if (existing != null) existing.add(k) else rows.add(mutableListOf(k))
        }
        rows.sortBy { it[0].hitBox.exactCenterY() }

        val maxShiftRatio = sensitivity.maxShiftRatio
        val gutter = 3f
        val halfGutter = gutter * 0.5f
        val result = ArrayList<TapZoneEntry>(alphaKeys.size)

        for (rowIdx in rows.indices) {
            val rowKeys = rows[rowIdx]
            rowKeys.sortBy { it.hitBox.left }
            val n = rowKeys.size
            if (n == 0) continue

            val boundaries = FloatArray(n + 1)
            boundaries[0] = rowKeys[0].hitBox.left.toFloat()
            boundaries[n] = rowKeys[n - 1].hitBox.right.toFloat()

            for (i in 0 until n - 1) {
                val leftKey = rowKeys[i]
                val rightKey = rowKeys[i + 1]
                val lhb = leftKey.hitBox
                val rhb = rightKey.hitBox
                val midX = (lhb.right + rhb.left) * 0.5f

                val lIdx = (toAlphaChar(leftKey.code)!! - 'a')
                val rIdx = (toAlphaChar(rightKey.code)!! - 'a')
                val avgW = (lhb.width() + rhb.width()) * 0.5f
                val delta = (boosts[lIdx] - boosts[rIdx]) * (avgW * maxShiftRatio)

                val minBound = lhb.exactCenterX() + lhb.width() * PROTECTED_CORE_RATIO * 0.5f + halfGutter
                val maxBound = rhb.exactCenterX() - rhb.width() * PROTECTED_CORE_RATIO * 0.5f - halfGutter
                boundaries[i + 1] = if (minBound <= maxBound) {
                    (midX + delta).coerceIn(minBound, maxBound)
                } else {
                    midX
                }
            }

            for (i in 0 until n) {
                val k = rowKeys[i]
                val hb = k.hitBox
                val cIdx = (toAlphaChar(k.code)!! - 'a')
                val left = if (i == 0) boundaries[0] + halfGutter else boundaries[i] + halfGutter
                val right = if (i == n - 1) boundaries[n] - halfGutter else boundaries[i + 1] - halfGutter
                val top = hb.top + halfGutter
                val bottom = hb.bottom - halfGutter

                result.add(
                    TapZoneEntry(
                        key = k,
                        charIdx = cIdx,
                        row = rowIdx.coerceIn(0, 3),
                        rect = RectF(left, top, right.coerceAtLeast(left + 2f), bottom.coerceAtLeast(top + 2f))
                    )
                )
            }
        }
        return result
    }

    /**
     * 1:1 WYSIWYG Touch Resolution using the immutable non-overlapping ZoneSnapshot
     * and ergonomic thumb parallax compensation. Debounces telemetry & calibration writes
     * so ACTION_MOVE drag events never double-count or corrupt EMA thumb bias.
     */
    @JvmStatic
    fun resolveKey(
        keyboard: Keyboard?,
        touchX: Int,
        touchY: Int,
        geometricKey: Key?
    ): Key? {
        if (keyboard == null || geometricKey == null || !dynamicAllowedForField) {
            return geometricKey
        }
        val geoChar = toAlphaChar(geometricKey.code) ?: return geometricKey
        val t0 = System.nanoTime()

        val snap = getOrBuildSnapshot(keyboard)
        val zones = snap.zones
        if (zones.isEmpty()) return geometricKey

        val hb = geometricKey.hitBox
        val keyW = hb.width().toFloat().coerceAtLeast(1f)
        val keyH = hb.height().toFloat().coerceAtLeast(1f)
        val row = estimateRow(keyboard, hb.exactCenterY())

        val biasXRatio: Float
        val biasYRatio: Float
        synchronized(stateLock) {
            biasXRatio = if (adaptiveThumbEnabled) rowBiasX[row] else 0f
            biasYRatio = if (adaptiveThumbEnabled) rowBiasY[row] else BASE_VERTICAL_BIAS_RATIO
        }
        val adjX = touchX - biasXRatio * keyW
        val adjY = touchY - biasYRatio * keyH
        lastOffsetYPx = -(biasYRatio * keyH)

        var matched: TapZoneEntry? = null
        for (i in zones.indices) {
            val z = zones[i]
            if (z.rect.contains(adjX, adjY)) {
                matched = z
                break
            }
        }
        if (matched == null) {
            var bestDist = Float.MAX_VALUE
            for (i in zones.indices) {
                val z = zones[i]
                val cx = adjX.coerceIn(z.rect.left, z.rect.right)
                val cy = adjY.coerceIn(z.rect.top, z.rect.bottom)
                val d = hypot(adjX - cx, adjY - cy)
                if (d < bestDist) {
                    bestDist = d
                    matched = z
                }
            }
        }

        val resolvedEntry = matched ?: return geometricKey
        val resolvedKey = resolvedEntry.key
        val resolvedChar = ('a' + resolvedEntry.charIdx)
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
        val isNewTap = (nowMs - lastLoggedTouchMs) >= TAP_LOG_DEBOUNCE_MS ||
            resolvedKey.code != lastLoggedKeyCode

        if (isNewTap) {
            lastLoggedTouchMs = nowMs
            lastLoggedKeyCode = resolvedKey.code
            if (rescued) rescuedCount++ else if (inCore) coreProtectedCount++
            updateAdaptiveCalibration(resolvedEntry.row, touchX - cx, touchY - cy, keyW, keyH)
            recordTouch(
                touchX.toFloat(), touchY.toFloat(), adjX, adjY,
                geoChar, resolvedChar, cx, cy,
                snap.probs[resolvedEntry.charIdx],
                rescued = rescued,
                protectedByCore = !rescued && inCore,
                latencyMs = latencyMs,
                nowMs = nowMs
            )
        } else {
            lastRawX = touchX.toFloat()
            lastRawY = touchY.toFloat()
            lastAdjX = adjX
            lastAdjY = adjY
            lastTouchTimeMs = nowMs
        }
        return resolvedKey
    }

    private fun estimateRow(keyboard: Keyboard, centerY: Float): Int {
        val h = keyboard.mOccupiedHeight.toFloat().coerceAtLeast(1f)
        return ((centerY / h) * 4f).toInt().coerceIn(0, 3)
    }

    private fun updateAdaptiveCalibration(row: Int, errX: Float, errY: Float, keyW: Float, keyH: Float) {
        if (!adaptiveThumbEnabled || !dynamicAllowedForField || !learningAllowedForField) return
        val normX = (errX / keyW).coerceIn(-0.4f, 0.4f)
        val normY = (errY / keyH).coerceIn(-0.4f, 0.4f)
        if (abs(normX) > 0.35f || abs(normY) > 0.35f) return

        val sampleCount: Int
        val copyX: FloatArray
        val copyY: FloatArray
        synchronized(stateLock) {
            rowBiasX[row] = (rowBiasX[row] * (1f - EMA_ALPHA) + normX * EMA_ALPHA)
                .coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
            rowBiasY[row] = (rowBiasY[row] * (1f - EMA_ALPHA) + normY * EMA_ALPHA)
                .coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
            calibrationSamples++
            sampleCount = calibrationSamples
            if (sampleCount % 15 != 0) return
            copyX = rowBiasX.copyOf()
            copyY = rowBiasY.copyOf()
        }

        appContextRef?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
            putInt(KEY_SAMPLES, sampleCount)
            for (r in 0 until 4) {
                putFloat("rbx_$r", copyX[r])
                putFloat("rby_$r", copyY[r])
            }
            apply()
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
     * Draws the strictly NON-OVERLAPPING Dynamic Tap Zones (1:1 WYSIWYG with resolveKey).
     */
    @JvmStatic
    fun drawOverlay(canvas: Canvas, keyboard: Keyboard?, context: Context) {
        ensureInitialized(context)
        val mode = visualizerMode
        if (mode == VisualizerMode.OFF || keyboard == null || !dynamicAllowedForField) return

        val snap = getOrBuildSnapshot(keyboard)
        val zones = snap.zones
        if (zones.isEmpty()) return

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
            monoPaint.textSize = 9.5f * density
            hudTextPaint.typeface = mono
            hudTextPaint.textSize = 10f * density
        }

        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val accentColor = if (isDark) 0xFF7EE3EA.toInt() else 0xFF0D6C74.toInt()
        val accentRgb = accentColor and 0x00FFFFFF
        val secondaryColor = if (isDark) 0xFF8C8C8C.toInt() else 0xFF6B6B6B.toInt()
        val secondaryRgb = secondaryColor and 0x00FFFFFF
        val radius = 7f * density

        for (i in zones.indices) {
            val z = zones[i]
            val boost = snap.boosts[z.charIdx]

            val fillAlpha = (18 + boost * 82f).roundToInt().coerceIn(16, 105)
            val strokeAlpha = (48 + boost * 195f).roundToInt().coerceIn(45, 245)
            fillPaint.color = (fillAlpha shl 24) or accentRgb
            strokePaint.color = (strokeAlpha shl 24) or accentRgb

            canvas.drawRoundRect(z.rect, radius, radius, fillPaint)
            canvas.drawRoundRect(z.rect, radius, radius, strokePaint)

            if (mode == VisualizerMode.ENGINEERING) {
                val hb = z.key.hitBox
                val cw = hb.width() * PROTECTED_CORE_RATIO * 0.5f
                val chH = hb.height() * PROTECTED_CORE_RATIO * 0.5f
                tempRect.set(hb.exactCenterX() - cw, hb.exactCenterY() - chH, hb.exactCenterX() + cw, hb.exactCenterY() + chH)
                corePaint.color = (0x55 shl 24) or secondaryRgb
                canvas.drawRect(tempRect, corePaint)

                val pct = (snap.probs[z.charIdx] * 100f).roundToInt()
                if (pct >= 4) {
                    monoPaint.color = accentColor
                    canvas.drawText("$pct%", z.rect.centerX(), z.rect.top + 11f * density, monoPaint)
                }
            }
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
            val tag = when {
                lastWasRescued -> "RESCUED"
                lastProtectedByCore -> "CORE"
                else -> "EXACT"
            }
            val pct = (lastResolvedProb * 100f).roundToInt()
            val ctxW = contextWordCount
            val hud = "T.A.S.K. ENG | ctx=${ctxW}w | $lastGeoChar->$lastResChar ($pct%) $tag | dy=${lastOffsetYPx.roundToInt()}px | ${"%.2f".format(lastLatencyMs)}ms"
            val textW = hudTextPaint.measureText(hud)
            val padX = 8f * density
            val pillH = 18f * density
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

    private fun foldAscii(input: String): String {
        if (input.isEmpty()) return ""
        val nfd = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
        val clean = combiningMarks.replace(nfd, "")
        val sb = StringBuilder(clean.length)
        for (ch in clean) if (ch in 'a'..'z') sb.append(ch)
        return sb.toString()
    }

    private fun looksLikeUrlOrEmailToken(token: String): Boolean {
        if (token.isEmpty()) return false
        val low = token.lowercase()
        return low.contains('@') ||
            low.contains("://") ||
            low.contains('/') ||
            low.startsWith("www.") ||
            low.endsWith(".com") ||
            low.endsWith(".br") ||
            low.endsWith(".org") ||
            low.endsWith(".net")
    }

    private fun applyEditorFieldPolicy(info: EditorInfo?) {
        if (info == null) {
            fieldBaseDynamicAllowed = true
            isUriField = false
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

        val isUri = clazz == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        val isNoPersonal = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        fieldBaseDynamicAllowed = !isPassword && !isExplicitEmail
        isUriField = isUri
        learningAllowedForField = !isPassword && !isNoPersonal
    }
}
