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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * T.A.S.K. v2.2.0 — Apple/iPhone-style Non-Overlapping Dynamic Tap Zones,
 * Ergonomic Thumb Vertical Parallax Compensation, Adaptive Per-Row Calibration,
 * and 1:1 WYSIWYG Prediction Visualizer (OFF / PRED / ENG).
 *
 * Key property:
 * Adjacent keys share a single dynamic boundary that shifts proportionally to
 * (boost_left - boost_right), clamped outside each key's 56% protected core.
 * - ZERO overlap between tap zones (horizontally or vertically).
 * - ZERO cumulative drift across the row (every key stays anchored on its visual label).
 * - 100% WYSIWYG: the exact non-overlapping RectF drawn in PRED/ENG is the exact
 *   hit zone used by resolveKey().
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
        val visualizerMode: VisualizerMode
    )

    private class TapZoneEntry(
        val key: Key,
        val charIdx: Int,
        val row: Int,
        val rect: RectF
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

    // Live probabilities & normalized boost [0..1] for 'a'..'z'
    private val charProbs = FloatArray(26)
    private val charBoosts = FloatArray(26)
    @Volatile private var zoneGeneration = 0

    // Cached non-overlapping tap zones for the current keyboard + generation
    @Volatile private var cachedKeyboardRef: Keyboard? = null
    @Volatile private var cachedZoneGen: Int = -1
    @Volatile private var cachedZones: List<TapZoneEntry> = emptyList()

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
    fun restoreThumbCalibration(context: Context, biasX: FloatArray, biasY: FloatArray, samples: Int) {
        ensureInitialized(context)
        for (r in 0 until minOf(4, biasX.size, biasY.size)) {
            rowBiasX[r] = biasX[r].coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
            rowBiasY[r] = biasY[r].coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
        }
        calibrationSamples = samples.coerceAtLeast(0)
        val editor = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putInt(KEY_SAMPLES, calibrationSamples)
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
            charBoosts.fill(0f)
            zoneGeneration++
            return
        }
        model.nextCharProbabilities(currentPrefix, charProbs)
        var maxP = 0f
        for (i in 0 until 26) if (charProbs[i] > maxP) maxP = charProbs[i]
        if (maxP <= 0f) {
            charBoosts.fill(0f)
            zoneGeneration++
            return
        }
        for (i in 0 until 26) {
            // Smooth square-root boost in [0..1] for crisp separation of likely next letters
            charBoosts[i] = sqrt((charProbs[i] / maxP).coerceIn(0f, 1f))
        }
        zoneGeneration++
    }

    /**
     * Computes strictly NON-OVERLAPPING adaptive tap zones for all alphabetic keys on [keyboard].
     *
     * How it guarantees ZERO overlap (like Apple's Dynamic Tap Zones):
     * 1. Keys are grouped into visual rows by Y-center and sorted left-to-right.
     * 2. Between every adjacent pair (key[i], key[i+1]) in a row, the shared vertical boundary
     *    shifts from the geometric midpoint by (boost[i] - boost[i+1]) * maxShiftX, strictly
     *    clamped so it NEVER enters either key's central 56% protected core.
     *    Then zone[i].right = boundary - gutter/2 and zone[i+1].left = boundary + gutter/2.
     * 3. Vertically, each row's shared horizontal divider stays cleanly separated with gutter/2
     *    so zones never climb over keys in the row above or below.
     */
    private fun getOrBuildTapZones(keyboard: Keyboard): List<TapZoneEntry> {
        val gen = zoneGeneration
        if (cachedKeyboardRef === keyboard && cachedZoneGen == gen) {
            return cachedZones
        }

        val alphaKeys = ArrayList<Key>(26)
        for (k in keyboard.sortedKeys) {
            if (toAlphaChar(k.code) != null) alphaKeys.add(k)
        }
        if (alphaKeys.isEmpty()) {
            cachedKeyboardRef = keyboard
            cachedZoneGen = gen
            cachedZones = emptyList()
            return emptyList()
        }

        // Group alphabetic keys into visual rows (tolerance = 35% of first key height)
        val rowTolerance = (alphaKeys[0].hitBox.height() * 0.35f).coerceAtLeast(10f)
        val rows = ArrayList<MutableList<Key>>(4)
        for (k in alphaKeys) {
            val cy = k.hitBox.exactCenterY()
            val existing = rows.firstOrNull { abs(it[0].hitBox.exactCenterY() - cy) <= rowTolerance }
            if (existing != null) existing.add(k) else rows.add(mutableListOf(k))
        }
        rows.sortBy { it[0].hitBox.exactCenterY() }

        val maxShiftRatio = sensitivity.maxShiftRatio
        val gutter = 3f // 3 px crisp gap between non-overlapping tap zones
        val halfGutter = gutter * 0.5f
        val result = ArrayList<TapZoneEntry>(alphaKeys.size)

        for (rowIdx in rows.indices) {
            val rowKeys = rows[rowIdx]
            rowKeys.sortBy { it.hitBox.left }
            val n = rowKeys.size
            if (n == 0) continue

            // Compute shared non-overlapping vertical boundaries between adjacent keys in this row
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
                val delta = (charBoosts[lIdx] - charBoosts[rIdx]) * (avgW * maxShiftRatio)

                // Clamp strictly outside BOTH keys' 56% protected central cores (D8)
                val minBound = lhb.exactCenterX() + lhb.width() * PROTECTED_CORE_RATIO * 0.5f + halfGutter
                val maxBound = rhb.exactCenterX() - rhb.width() * PROTECTED_CORE_RATIO * 0.5f - halfGutter
                boundaries[i + 1] = if (minBound <= maxBound) {
                    (midX + delta).coerceIn(minBound, maxBound)
                } else {
                    midX
                }
            }

            // Build strictly non-overlapping RectF for every key in this row
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

        cachedKeyboardRef = keyboard
        cachedZoneGen = gen
        cachedZones = result
        return result
    }

    /**
     * 1:1 WYSIWYG Apple/iPhone-style Touch Resolution using the exact same non-overlapping
     * adaptive Tap Zones rendered on screen, combined with ergonomic thumb parallax offset.
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

        val zones = getOrBuildTapZones(keyboard)
        if (zones.isEmpty()) return geometricKey

        val hb = geometricKey.hitBox
        val keyW = hb.width().toFloat().coerceAtLeast(1f)
        val keyH = hb.height().toFloat().coerceAtLeast(1f)
        val row = estimateRow(keyboard, hb.exactCenterY())

        // 1. Apply iPhone ergonomic thumb vertical parallax compensation + adaptive per-row bias
        val biasXRatio = if (adaptiveThumbEnabled) rowBiasX[row] else 0f
        val biasYRatio = if (adaptiveThumbEnabled) rowBiasY[row] else BASE_VERTICAL_BIAS_RATIO
        val adjX = touchX - biasXRatio * keyW
        val adjY = touchY - biasYRatio * keyH
        lastOffsetYPx = -(biasYRatio * keyH)

        // 2. Hit-test the exact non-overlapping dynamic tap zones
        var matched: TapZoneEntry? = null
        for (i in zones.indices) {
            val z = zones[i]
            if (z.rect.contains(adjX, adjY)) {
                matched = z
                break
            }
        }
        // If touch landed inside the 3px gutter between zones, pick nearest zone boundary
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
        if (rescued) rescuedCount++ else if (inCore) coreProtectedCount++

        updateAdaptiveCalibration(resolvedEntry.row, touchX - cx, touchY - cy, keyW, keyH)
        recordTouch(
            touchX.toFloat(), touchY.toFloat(), adjX, adjY,
            geoChar, resolvedChar, cx, cy,
            charProbs[resolvedEntry.charIdx],
            rescued = rescued,
            protectedByCore = !rescued && inCore,
            latencyMs = (System.nanoTime() - t0) / 1_000_000.0
        )
        return resolvedKey
    }

    private fun estimateRow(keyboard: Keyboard, centerY: Float): Int {
        val h = keyboard.mOccupiedHeight.toFloat().coerceAtLeast(1f)
        return ((centerY / h) * 4f).toInt().coerceIn(0, 3)
    }

    private fun updateAdaptiveCalibration(row: Int, errX: Float, errY: Float, keyW: Float, keyH: Float) {
        if (!adaptiveThumbEnabled || !dynamicAllowedForField) return
        val normX = (errX / keyW).coerceIn(-0.4f, 0.4f)
        val normY = (errY / keyH).coerceIn(-0.4f, 0.4f)
        if (abs(normX) > 0.35f || abs(normY) > 0.35f) return

        rowBiasX[row] = (rowBiasX[row] * (1f - EMA_ALPHA) + normX * EMA_ALPHA)
            .coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
        rowBiasY[row] = (rowBiasY[row] * (1f - EMA_ALPHA) + normY * EMA_ALPHA)
            .coerceIn(-MAX_ADAPTIVE_BIAS_RATIO, MAX_ADAPTIVE_BIAS_RATIO)
        calibrationSamples++

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
     * Draws the strictly NON-OVERLAPPING Dynamic Tap Zones (1:1 WYSIWYG with resolveKey).
     * Wide probable zones glow in T.A.L. Accent (Ice #7EE3EA dark / Deep Teal #0D6C74 light),
     * while narrowed neighboring zones shrink cleanly without ever overlapping.
     */
    @JvmStatic
    fun drawOverlay(canvas: Canvas, keyboard: Keyboard?, context: Context) {
        ensureInitialized(context)
        val mode = visualizerMode
        if (mode == VisualizerMode.OFF || keyboard == null || !dynamicAllowedForField) return

        val zones = getOrBuildTapZones(keyboard)
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
            val boost = charBoosts[z.charIdx]

            // Draw EVERY alphabetic key's partitioned non-overlapping tap zone so the user
            // sees probable keys widen and adjacent improbable keys narrow side-by-side!
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

                val pct = (charProbs[z.charIdx] * 100f).roundToInt()
                if (pct >= 4) {
                    monoPaint.color = accentColor
                    canvas.drawText("$pct%", z.rect.centerX(), z.rect.top + 11f * density, monoPaint)
                }
            }
        }

        // Draw thumb parallax compensation vector (raw -> adjusted) & rescue vector (fades after 1.6s)
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
