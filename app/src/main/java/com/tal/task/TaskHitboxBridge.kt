package com.tal.task

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.res.ResourcesCompat
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * T.A.S.K. v2.1.0 — Apple/iPhone-style 2D Bayesian Touch-Target Engine,
 * Ergonomic Thumb Vertical Parallax Compensation, Adaptive Per-Row Calibration,
 * and Real-Time Prediction Visualizer (OFF / PRED / ENG).
 *
 * Guarantees (D7 / D8 / D10 / D11):
 * - 2D Gaussian spatial likelihood combined with offline S23 PT-BR/EN-US language prior.
 * - Ergonomic thumb vertical offset + per-row EMA calibration (stores ONLY float offsets, never text).
 * - Central 56% protected core is 100% inviolable (never hijacked even at 0% probability).
 * - Expansion capped at <= +40%.
 * - Automatically disabled (zero calibration writes, static hitboxes) in Password/URI/Email/Incognito fields.
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

    enum class Sensitivity(val label: String, val gamma: Float, val maxExpansion: Float) {
        SOFT("Suave", gamma = 0.28f, maxExpansion = 0.24f),
        STANDARD("Padrão", gamma = 0.45f, maxExpansion = 0.34f),
        AGGRESSIVE("Agressivo", gamma = 0.60f, maxExpansion = 0.40f)
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
        val visualizerMode: VisualizerMode
    )

    private const val PREFS_NAME = "task_hitbox_prefs"
    private const val KEY_VIZ_MODE = "viz_mode"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_ADAPTIVE = "adaptive_thumb"
    private const val KEY_SAMPLES = "cal_samples"

    private const val HARD_MAX_EXPANSION = 0.40f
    private const val PROTECTED_CORE_RATIO = 0.56f
    private const val SIGMA_X = 0.58f
    private const val SIGMA_Y = 0.62f
    private const val LAPLACE_EPS = 0.002f
    private const val BASE_VERTICAL_BIAS_RATIO = 0.06f // iPhone thumb parallax: capacitive centroid sits ~6% below visual target
    private const val MAX_ADAPTIVE_BIAS_RATIO = 0.14f
    private const val EMA_ALPHA = 0.04f

    private class TrieNode {
        val children = arrayOfNulls<TrieNode>(26)
        var subtreeMass: Long = 0L
    }

    private class FastPrefixModel {
        private val root = TrieNode()
        private val charBigrams = Array(27) { FloatArray(26) }
        private val charTotals = FloatArray(27)
        private val combiningMarks = Regex("\\p{InCombiningDiacriticalMarks}+")

        fun insert(word: String, freq: Int) {
            val folded = fold(word)
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

        fun nextCharProbabilities(prefix: String, out: FloatArray) {
            out.fill(0f)
            val folded = fold(prefix)
            var node: TrieNode? = root
            for (ch in folded) {
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

            val lastIdx = if (folded.isEmpty()) 26 else (folded.last() - 'a').coerceIn(0, 25)
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

        private fun fold(input: String): String {
            if (input.isEmpty()) return ""
            val nfd = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
            val clean = combiningMarks.replace(nfd, "")
            val sb = StringBuilder(clean.length)
            for (ch in clean) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }
    }

    private val loaderExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var loadingStarted = false
    @Volatile private var prefsLoaded = false
    @Volatile private var appContextRef: Context? = null
    @Volatile private var ptModel: FastPrefixModel? = null
    @Volatile private var enModel: FastPrefixModel? = null

    @Volatile private var visualizerMode: VisualizerMode = VisualizerMode.OVERLAY
    @Volatile private var sensitivity: Sensitivity = Sensitivity.STANDARD
    @Volatile private var adaptiveThumbEnabled: Boolean = true
    @Volatile private var activeLocaleIsPt = true
    @Volatile private var currentPrefix = ""
    @Volatile private var dynamicAllowedForField = true

    // Per-row adaptive thumb offset (normalized as fraction of key width/height, NEVER stores text)
    private val rowBiasX = FloatArray(4)
    private val rowBiasY = floatArrayOf(BASE_VERTICAL_BIAS_RATIO, BASE_VERTICAL_BIAS_RATIO, BASE_VERTICAL_BIAS_RATIO * 0.75f, 0f)
    @Volatile private var calibrationSamples = 0

    // Live probabilities & expansion factors for 'a'..'z'
    private val charProbs = FloatArray(26)
    private val charExpansions = FloatArray(26)

    // Telemetry state
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
            for (r in 0 until 4) {
                rowBiasX[r] = prefs.getFloat("rbx_$r", 0f).coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
                val defY = if (r < 2) BASE_VERTICAL_BIAS_RATIO else if (r == 2) BASE_VERTICAL_BIAS_RATIO * 0.75f else 0f
                rowBiasY[r] = prefs.getFloat("rby_$r", defY).coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
            }
        }
        if (!loadingStarted) {
            loadingStarted = true
            loaderExecutor.execute {
                ptModel = loadTsvModel(appCtx, "dict/pt_br.tsv")
                enModel = loadTsvModel(appCtx, "dict/en_us.tsv")
                recomputeProbabilities()
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
        recomputeProbabilities()
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
        rowBiasX.fill(0f)
        rowBiasY[0] = BASE_VERTICAL_BIAS_RATIO
        rowBiasY[1] = BASE_VERTICAL_BIAS_RATIO
        rowBiasY[2] = BASE_VERTICAL_BIAS_RATIO * 0.75f
        rowBiasY[3] = 0f
        calibrationSamples = 0
        val editor = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putInt(KEY_SAMPLES, 0)
        for (r in 0 until 4) {
            editor.putFloat("rbx_$r", rowBiasX[r])
            editor.putFloat("rby_$r", rowBiasY[r])
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
    fun getTelemetrySnapshot(context: Context): LiveTelemetry {
        ensureInitialized(context)
        val p99 = if (ringCount == 0) 0.0 else {
            val copy = latencyRing.copyOf(ringCount).apply { sort() }
            copy[((copy.size - 1) * 0.99).toInt().coerceAtLeast(0)]
        }
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
            visualizerMode = visualizerMode
        )
    }

    @JvmStatic
    fun onFieldStarted(context: Context, editorInfo: EditorInfo?, localeTag: String?) {
        ensureInitialized(context)
        activeLocaleIsPt = localeTag == null || localeTag.lowercase().startsWith("pt")
        dynamicAllowedForField = isDynamicAllowed(editorInfo)
        currentPrefix = ""
        recomputeProbabilities()
    }

    @JvmStatic
    fun updateComposingWord(context: Context, prefix: String?, localeTag: String?) {
        ensureInitialized(context)
        if (!localeTag.isNullOrBlank()) {
            activeLocaleIsPt = localeTag.lowercase().startsWith("pt")
        }
        val clean = prefix.orEmpty()
        if (clean != currentPrefix) {
            currentPrefix = clean
            recomputeProbabilities()
        }
    }

    private fun recomputeProbabilities() {
        val model = if (activeLocaleIsPt) ptModel else enModel
        if (model == null || !dynamicAllowedForField) {
            charProbs.fill(0f)
            charExpansions.fill(0f)
            return
        }
        model.nextCharProbabilities(currentPrefix, charProbs)
        var maxP = 0f
        for (i in 0 until 26) if (charProbs[i] > maxP) maxP = charProbs[i]
        if (maxP <= 0f) {
            charExpansions.fill(0f)
            return
        }
        val cap = sensitivity.maxExpansion.coerceAtMost(HARD_MAX_EXPANSION)
        for (i in 0 until 26) {
            val norm = sqrt((charProbs[i] / maxP).coerceIn(0f, 1f))
            charExpansions[i] = (norm * cap).coerceIn(0f, HARD_MAX_EXPANSION)
        }
    }

    /**
     * Apple/iPhone-style 2D Bayesian Touch Resolution with Ergonomic Thumb Vertical Offset
     * and D8 56% Protected Core Guarantee.
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

        val hb = geometricKey.hitBox
        val keyW = hb.width().toFloat().coerceAtLeast(1f)
        val keyH = hb.height().toFloat().coerceAtLeast(1f)
        val row = estimateRow(keyboard, hb.exactCenterY())

        // 1. Apply iPhone ergonomic thumb vertical parallax compensation + adaptive per-row bias
        val biasXRatio = if (adaptiveThumbEnabled) rowBiasX[row] else 0f
        val biasYRatio = if (adaptiveThumbEnabled) rowBiasY[row] else BASE_VERTICAL_BIAS_RATIO
        val offsetXPx = biasXRatio * keyW
        val offsetYPx = biasYRatio * keyH
        val adjX = touchX - offsetXPx
        val adjY = touchY - offsetYPx
        lastOffsetYPx = -offsetYPx

        // Re-check geometric key at ergonomically compensated coordinates
        var effectiveGeoKey: Key = geometricKey
        var effectiveGeoChar: Char = geoChar
        if (!hb.contains(adjX.roundToInt(), adjY.roundToInt())) {
            for (cand in keyboard.getNearestKeys(adjX.roundToInt(), adjY.roundToInt())) {
                val cch = toAlphaChar(cand.code) ?: continue
                if (cand.hitBox.contains(adjX.roundToInt(), adjY.roundToInt())) {
                    effectiveGeoKey = cand
                    effectiveGeoChar = cch
                    break
                }
            }
        }

        val effHb = effectiveGeoKey.hitBox
        val cx = effHb.exactCenterX()
        val cy = effHb.exactCenterY()
        val halfCoreW = effHb.width() * PROTECTED_CORE_RATIO * 0.5f
        val halfCoreH = effHb.height() * PROTECTED_CORE_RATIO * 0.5f

        // 2. D8 Protected Core Guarantee: central 56% of key is 100% inviolable
        val inCore = adjX >= (cx - halfCoreW) && adjX <= (cx + halfCoreW) &&
            adjY >= (cy - halfCoreH) && adjY <= (cy + halfCoreH)

        if (inCore) {
            updateAdaptiveCalibration(row, touchX - cx, touchY - cy, keyW, keyH)
            val rescuedByOffset = effectiveGeoKey !== geometricKey
            if (rescuedByOffset) rescuedCount++ else coreProtectedCount++
            recordTouch(
                touchX.toFloat(), touchY.toFloat(), adjX, adjY,
                geoChar, effectiveGeoChar, cx, cy,
                charProbs[effectiveGeoChar - 'a'],
                rescued = rescuedByOffset,
                protectedByCore = !rescuedByOffset,
                latencyMs = (System.nanoTime() - t0) / 1_000_000.0
            )
            return effectiveGeoKey
        }

        // 3. Outside protected core: 2D Bayesian Gaussian log-posterior = log P(Touch|Key) + gamma * log P(Key|ctx)
        val gamma = sensitivity.gamma
        val maxRadius = keyW * 1.45f
        var bestKey: Key = effectiveGeoKey
        var bestChar: Char = effectiveGeoChar
        var bestLogPosterior = Float.NEGATIVE_INFINITY

        for (candidate in keyboard.getNearestKeys(adjX.roundToInt(), adjY.roundToInt())) {
            val candChar = toAlphaChar(candidate.code) ?: continue
            val chb = candidate.hitBox
            val dx = adjX - chb.exactCenterX()
            val dy = adjY - chb.exactCenterY()
            if (hypot(dx, dy) > maxRadius) continue

            val nx = dx / (SIGMA_X * chb.width().toFloat().coerceAtLeast(1f))
            val ny = dy / (SIGMA_Y * chb.height().toFloat().coerceAtLeast(1f))
            val logSpatial = -0.5f * (nx * nx + ny * ny)
            val pLang = charProbs[candChar - 'a'].coerceAtLeast(0f) + LAPLACE_EPS
            val logPosterior = logSpatial + gamma * ln(pLang)

            if (logPosterior > bestLogPosterior) {
                bestLogPosterior = logPosterior
                bestKey = candidate
                bestChar = candChar
            }
        }

        val rescued = bestKey !== geometricKey
        if (rescued) rescuedCount++
        updateAdaptiveCalibration(
            row,
            touchX - bestKey.hitBox.exactCenterX(),
            touchY - bestKey.hitBox.exactCenterY(),
            keyW,
            keyH
        )
        recordTouch(
            touchX.toFloat(), touchY.toFloat(), adjX, adjY,
            geoChar, bestChar,
            bestKey.hitBox.exactCenterX(), bestKey.hitBox.exactCenterY(),
            charProbs[bestChar - 'a'],
            rescued = rescued,
            protectedByCore = false,
            latencyMs = (System.nanoTime() - t0) / 1_000_000.0
        )
        return bestKey
    }

    private fun estimateRow(keyboard: Keyboard, centerY: Float): Int {
        val h = keyboard.mOccupiedHeight.toFloat().coerceAtLeast(1f)
        return ((centerY / h) * 4f).toInt().coerceIn(0, 3)
    }

    private fun updateAdaptiveCalibration(row: Int, errX: Float, errY: Float, keyW: Float, keyH: Float) {
        if (!adaptiveThumbEnabled || !dynamicAllowedForField) return
        val normX = (errX / keyW).coerceIn(-0.4f, 0.4f)
        val normY = (errY / keyH).coerceIn(-0.4f, 0.4f)
        // Only adapt on plausible near-key taps (ignore wild slips)
        if (abs(normX) > 0.35f || abs(normY) > 0.35f) return

        rowBiasX[row] = (rowBiasX[row] * (1f - EMA_ALPHA) + normX * EMA_ALPHA)
            .coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
        rowBiasY[row] = (rowBiasY[row] * (1f - EMA_ALPHA) + normY * EMA_ALPHA)
            .coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
        calibrationSamples++

        // Persist every 15 taps to avoid disk churn
        if (calibrationSamples % 15 == 0) {
            appContextRef?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
                putInt(KEY_SAMPLES, calibrationSamples)
                for (r in 0 until 4) {
                    putFloat("rbx_$r", rowBiasX[r])
                    putFloat("rby_$r", rowBiasY[r])
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
        latencyMs: Double
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
        lastTouchTimeMs = System.currentTimeMillis()
        lastLatencyMs = latencyMs
    }

    /**
     * Hook called at the end of AOSP KeyboardView.onDraw(Canvas).
     * Draws Bayesian expanded hitboxes (PRED) + thumb parallax vector, core bounds, % & HUD (ENG).
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
            strokePaint.strokeWidth = 1.5f * density
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

        val keys = keyboard.sortedKeys
        for (i in keys.indices) {
            val key = keys[i]
            val ch = toAlphaChar(key.code) ?: continue
            val idx = ch - 'a'
            val exp = charExpansions[idx]
            if (exp <= 0.03f) continue

            val hb = key.hitBox
            val dx = hb.width() * exp * 0.45f
            val dy = hb.height() * exp * 0.35f
            tempRect.set(hb.left - dx, hb.top - dy, hb.right + dx, hb.bottom + dy)

            val fillAlpha = (exp * 150f).roundToInt().coerceIn(16, 72)
            val strokeAlpha = (exp * 460f).roundToInt().coerceIn(55, 225)
            fillPaint.color = (fillAlpha shl 24) or accentRgb
            strokePaint.color = (strokeAlpha shl 24) or accentRgb

            val radius = 8f * density
            canvas.drawRoundRect(tempRect, radius, radius, fillPaint)
            canvas.drawRoundRect(tempRect, radius, radius, strokePaint)

            if (mode == VisualizerMode.ENGINEERING) {
                val cw = hb.width() * PROTECTED_CORE_RATIO * 0.5f
                val chH = hb.height() * PROTECTED_CORE_RATIO * 0.5f
                tempRect.set(hb.exactCenterX() - cw, hb.exactCenterY() - chH, hb.exactCenterX() + cw, hb.exactCenterY() + chH)
                corePaint.color = (0x66 shl 24) or secondaryRgb
                canvas.drawRect(tempRect, corePaint)

                val pct = (charProbs[idx] * 100f).roundToInt()
                if (pct >= 4) {
                    monoPaint.color = accentColor
                    canvas.drawText("$pct%", hb.exactCenterX(), hb.top + 11f * density, monoPaint)
                }
            }
        }

        // Draw thumb parallax compensation vector (raw -> adjusted) & Bayesian rescue vector (fades after 1.6s)
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

        // Engineering HUD pill at top-left
        if (mode == VisualizerMode.ENGINEERING) {
            val tag = when {
                lastWasRescued -> "RESCUED"
                lastProtectedByCore -> "CORE"
                else -> "EXACT"
            }
            val pct = (lastResolvedProb * 100f).roundToInt()
            val hud = "T.A.S.K. ENG | $lastGeoChar->$lastResChar ($pct%) $tag | dy=${lastOffsetYPx.roundToInt()}px | saved=$rescuedCount | ${"%.2f".format(lastLatencyMs)}ms"
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

    private fun isDynamicAllowed(info: EditorInfo?): Boolean {
        if (info == null) return true
        val inputType = info.inputType
        val clazz = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = (clazz == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )) || (clazz == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val isUriOrEmail = clazz == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
        val isNoPersonal = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return !isPassword && !isUriOrEmail && !isNoPersonal
    }
}
