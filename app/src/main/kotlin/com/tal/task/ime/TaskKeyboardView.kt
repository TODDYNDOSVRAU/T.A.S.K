package com.tal.task.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.tal.task.R
import com.tal.task.data.ClipboardEntry
import com.tal.task.data.LocalClipboardStore
import com.tal.task.data.TaskConfig
import com.tal.task.data.TaskTelemetry
import com.tal.task.engine.DynamicHitboxEngine
import com.tal.task.engine.HitboxSnapshot
import com.tal.task.engine.KeyAction
import com.tal.task.engine.KeyRect
import com.tal.task.engine.KeyboardLayer
import com.tal.task.engine.KeyboardLayoutSpec
import com.tal.task.engine.Language
import com.tal.task.engine.LayoutKey
import com.tal.task.engine.TouchResolution
import com.tal.task.engine.TrieDictionary
import com.tal.task.engine.VisualizerMode
import com.tal.task.engine.WordSuggestion
import kotlin.math.abs
import kotlin.math.roundToInt

interface KeyboardHostCallbacks {
    fun onCommitText(text: String)
    fun onCommitWordSuggestion(word: String, replaceLength: Int)
    fun onBackspace()
    fun onEnter()
    fun onMoveCursor(delta: Int)
    fun onVisualizerModeChanged(mode: VisualizerMode)
    fun onLanguageSwitched(language: Language)
}

/**
 * High-performance low-allocation Canvas Keyboard View following the T.A.L. 2x Grid.
 * Supports real-time Overlay and Engineering prediction visualizers, long-press accents,
 * spacebar cursor glide, Emoji panel, and Local Clipboard panel.
 */
class TaskKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class ActivePanel { KEYBOARD, EMOJI, CLIPBOARD }

    var callbacks: KeyboardHostCallbacks? = null

    private val engine = DynamicHitboxEngine()
    private var dictionaries: Map<Language, TrieDictionary> = emptyMap()
    private var config: TaskConfig = TaskConfig()
    private var isDarkTheme: Boolean = true
    private var dynamicAllowedForField: Boolean = true
    private var incognitoField: Boolean = false

    private var layer: KeyboardLayer = KeyboardLayer.ALPHA_LOWER
    private var activePanel: ActivePanel = ActivePanel.KEYBOARD

    private var currentPrefix: String = ""
    private var previousWord: String? = null
    private var suggestions: List<WordSuggestion> = emptyList()
    private var layoutKeys: List<LayoutKey> = emptyList()
    private var snapshot: HitboxSnapshot? = null
    private var lastResolution: TouchResolution? = null
    private var lastResolutionTimeMs: Long = 0L

    // Touch & gesture state
    private var pressedKeyIndex: Int = -1
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var spaceDragging: Boolean = false
    private var lastSpaceDragX: Float = 0f
    private var lastShiftTapMs: Long = 0L
    private var lastSpaceTapMs: Long = 0L

    // Long-press accent popup state
    private val mainHandler = Handler(Looper.getMainLooper())
    private var accentPopupKey: LayoutKey? = null
    private var accentSelectedIndex: Int = 0
    private val longPressRunnable = Runnable {
        val idx = pressedKeyIndex
        if (idx in layoutKeys.indices && activePanel == ActivePanel.KEYBOARD && !spaceDragging) {
            val key = layoutKeys[idx]
            if (key.spec.accents.isNotEmpty()) {
                accentPopupKey = key
                accentSelectedIndex = 0
                triggerHaptic()
                invalidate()
            }
        }
    }

    // Backspace auto-repeat
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (pressedKeyIndex in layoutKeys.indices &&
                layoutKeys[pressedKeyIndex].spec.action == KeyAction.Backspace
            ) {
                callbacks?.onBackspace()
                triggerHaptic()
                mainHandler.postDelayed(this, 65L)
            }
        }
    }

    // Geometry constants (T.A.L. 2x Grid: 6, 12, 24, 48 dp)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val topBarHeightPx get() = dp(48f)
    private val engStripHeightPx get() = if (config.visualizerMode == VisualizerMode.ENGINEERING) dp(24f) else 0f
    private val keyboardAreaHeightPx get() = dp(if (config.showNumberRow) 276f else 234f)

    // Pre-allocated paints & rects (zero allocations inside onDraw)
    private val tempRect = RectF()
    private val popupRect = RectF()
    private val bgPaint = Paint()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val overlayFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val overlayStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val coreDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val rescueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(18f)
    }
    private val specialTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(13f)
    }
    private val suggestionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
    }
    private val engTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = sp(11f)
    }

    // Top bar hit rects
    private val suggestionRects = Array(3) { RectF() }
    private val vizToggleRect = RectF()
    private val clipToggleRect = RectF()
    private val langToggleRect = RectF()

    // Emoji catalog (D6)
    private val emojiList = listOf(
        "😀", "😁", "😂", "🤣", "😊", "😉", "😍", "🤔",
        "😎", "🔥", "👍", "👎", "👏", "🙌", "🤝", "🙏",
        "❤️", "✨", "⚡", "🎯", "🚀", "💡", "🛠️", "📱",
        "💻", "⌨️", "✅", "❌", "⚠️", "🔒", "🎉", "☕"
    )

    // Color palette state (updated via applyThemeColors)
    private var colorBg = 0xFF080808.toInt()
    private var colorTopBar = 0xFF141414.toInt()
    private var colorKey = 0xFF141414.toInt()
    private var colorSpecialKey = 0xFF1D1D1D.toInt()
    private var colorPressedKey = 0xFF2D2D2D.toInt()
    private var colorKeyBorder = 0xFF2D2D2D.toInt()
    private var colorText = 0xFFF2F2F0.toInt()
    private var colorSecondary = 0xFF8C8C8C.toInt()
    private var colorAccent = 0xFF7EE3EA.toInt()
    private var colorOnAccent = 0xFF080808.toInt()

    init {
        isClickable = true
        val inter = ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.SANS_SERIF
        val interMed = ResourcesCompat.getFont(context, R.font.inter_500) ?: inter
        val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        val grotesk = ResourcesCompat.getFont(context, R.font.space_grotesk_700) ?: Typeface.DEFAULT_BOLD
        keyTextPaint.typeface = inter
        specialTextPaint.typeface = grotesk
        suggestionTextPaint.typeface = interMed
        monoPaint.typeface = mono
        engTextPaint.typeface = mono
        applyThemeColors(dark = true)
    }

    fun bindDictionaries(dicts: Map<Language, TrieDictionary>) {
        dictionaries = dicts
        recomputePrediction()
    }

    fun applyConfig(newConfig: TaskConfig, darkTheme: Boolean) {
        val heightChanged = config.showNumberRow != newConfig.showNumberRow ||
            (config.visualizerMode == VisualizerMode.ENGINEERING) != (newConfig.visualizerMode == VisualizerMode.ENGINEERING)
        config = newConfig
        isDarkTheme = darkTheme
        applyThemeColors(darkTheme)
        if (heightChanged) requestLayout()
        rebuildLayout(width, height)
        recomputePrediction()
        invalidate()
    }

    fun updateFieldPolicy(dynamicAllowed: Boolean, incognito: Boolean) {
        dynamicAllowedForField = dynamicAllowed
        incognitoField = incognito
        recomputePrediction()
        invalidate()
    }

    fun updateComposingContext(prefix: String, prevWord: String?) {
        currentPrefix = prefix
        previousWord = prevWord
        recomputePrediction()
        invalidate()
    }

    private fun activeDictionary(): TrieDictionary? = dictionaries[config.language]

    private fun applyThemeColors(dark: Boolean) {
        if (dark) {
            colorBg = 0xFF080808.toInt()
            colorTopBar = 0xFF141414.toInt()
            colorKey = 0xFF141414.toInt()
            colorSpecialKey = 0xFF1D1D1D.toInt()
            colorPressedKey = 0xFF2D2D2D.toInt()
            colorKeyBorder = 0xFF2D2D2D.toInt()
            colorText = 0xFFF2F2F0.toInt()
            colorSecondary = 0xFF8C8C8C.toInt()
            colorAccent = 0xFF7EE3EA.toInt() // Ice (13.4:1 on #080808)
            colorOnAccent = 0xFF080808.toInt()
        } else {
            colorBg = 0xFFF2F2F0.toInt()
            colorTopBar = 0xFFE4E4E2.toInt()
            colorKey = 0xFFFFFFFF.toInt()
            colorSpecialKey = 0xFFE4E4E2.toInt()
            colorPressedKey = 0xFFD2D2CF.toInt()
            colorKeyBorder = 0xFFD2D2CF.toInt()
            colorText = 0xFF080808.toInt()
            colorSecondary = 0xFF6B6B6B.toInt()
            colorAccent = 0xFF0D6C74.toInt() // Deep Teal (5.5:1 on #F2F2F0)
            colorOnAccent = 0xFFF2F2F0.toInt()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val totalH = (topBarHeightPx + engStripHeightPx + keyboardAreaHeightPx).roundToInt()
        setMeasuredDimension(w, totalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout(w, h)
        recomputePrediction()
    }

    private fun rebuildLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        // Top bar geometry (48 dp height): [3 suggestions | CB | PT/EN | VIZ pill]
        val pad = dp(6f)
        val btnH = topBarHeightPx - pad * 2f
        val vizW = dp(64f)
        val smallBtnW = dp(38f)

        vizToggleRect.set(w - pad - vizW, pad, w - pad, pad + btnH)
        langToggleRect.set(vizToggleRect.left - pad - smallBtnW, pad, vizToggleRect.left - pad, pad + btnH)
        clipToggleRect.set(langToggleRect.left - pad - smallBtnW, pad, langToggleRect.left - pad, pad + btnH)

        val suggAreaRight = clipToggleRect.left - pad
        val suggW = ((suggAreaRight - pad) / 3f).coerceAtLeast(dp(40f))
        for (i in 0 until 3) {
            val left = pad + i * suggW
            suggestionRects[i].set(left, pad, left + suggW - dp(2f), pad + btnH)
        }

        // Keyboard rows positioned below top bar + optional engineering strip
        val kbTop = topBarHeightPx + engStripHeightPx
        val kbHeight = (h - kbTop).coerceAtLeast(dp(180f))
        val rows = KeyboardLayoutSpec.buildRows(
            layer = layer,
            language = config.language,
            showNumberRow = config.showNumberRow
        )
        val rawKeys = KeyboardLayoutSpec.layoutKeys(
            rows = rows,
            totalWidth = w.toFloat(),
            totalHeight = kbHeight,
            horizontalPadding = dp(6f),
            verticalPadding = dp(6f),
            keyGapX = dp(6f),
            keyGapY = dp(8f)
        )
        // Offset Y by kbTop
        layoutKeys = rawKeys.map { k ->
            k.copy(
                rect = KeyRect(
                    left = k.rect.left,
                    top = k.rect.top + kbTop,
                    right = k.rect.right,
                    bottom = k.rect.bottom + kbTop
                )
            )
        }
    }

    private fun recomputePrediction() {
        val dict = activeDictionary() ?: return
        val effectiveDynamic = dynamicAllowedForField &&
            (layer == KeyboardLayer.ALPHA_LOWER || layer == KeyboardLayer.ALPHA_UPPER || layer == KeyboardLayer.ALPHA_CAPS)
        snapshot = engine.computeSnapshot(
            keys = layoutKeys,
            dictionary = dict,
            prefix = currentPrefix,
            prevWord = previousWord,
            sensitivity = config.sensitivity,
            dynamicEnabled = effectiveDynamic
        )
        suggestions = if (incognitoField) {
            emptyList()
        } else {
            dict.suggestWords(currentPrefix, previousWord, limit = 3)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 1. Background
        bgPaint.color = colorBg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Top bar (Suggestions + Clipboard + Lang + 1-tap Visualizer toggle)
        drawTopBar(canvas)

        // 3. Optional Engineering telemetry strip
        if (config.visualizerMode == VisualizerMode.ENGINEERING) {
            drawEngineeringStrip(canvas)
        }

        // 4. Active panel (Keyboard, Emoji, or Clipboard)
        when (activePanel) {
            ActivePanel.KEYBOARD -> drawKeyboard(canvas)
            ActivePanel.EMOJI -> drawEmojiPanel(canvas)
            ActivePanel.CLIPBOARD -> drawClipboardPanel(canvas)
        }
    }

    private fun drawTopBar(canvas: Canvas) {
        bgPaint.color = colorTopBar
        canvas.drawRect(0f, 0f, width.toFloat(), topBarHeightPx, bgPaint)

        // 3 suggestion slots
        val textOffsetY = (suggestionTextPaint.descent() + suggestionTextPaint.ascent()) * 0.5f
        for (i in 0 until 3) {
            val rect = suggestionRects[i]
            val item = suggestions.getOrNull(i)
            if (item != null) {
                if (item.isAutoCorrect && config.autoCorrect) {
                    keyPaint.color = colorSpecialKey
                    canvas.drawRoundRect(rect, dp(6f), dp(6f), keyPaint)
                    suggestionTextPaint.color = colorAccent
                } else {
                    suggestionTextPaint.color = colorText
                }
                canvas.drawText(item.word, rect.centerX(), rect.centerY() - textOffsetY, suggestionTextPaint)
            }
        }

        // Clipboard button
        drawPillButton(
            canvas = canvas,
            rect = clipToggleRect,
            label = "CB",
            active = activePanel == ActivePanel.CLIPBOARD
        )

        // Language switch button
        drawPillButton(
            canvas = canvas,
            rect = langToggleRect,
            label = config.language.label,
            active = false
        )

        // 1-tap Visualizer toggle pill (OFF / OVERLAY / ENG)
        drawPillButton(
            canvas = canvas,
            rect = vizToggleRect,
            label = config.visualizerMode.label,
            active = config.visualizerMode != VisualizerMode.OFF
        )
    }

    private fun drawPillButton(canvas: Canvas, rect: RectF, label: String, active: Boolean) {
        keyPaint.color = if (active) colorAccent else colorSpecialKey
        canvas.drawRoundRect(rect, dp(6f), dp(6f), keyPaint)
        monoPaint.color = if (active) colorOnAccent else colorText
        val dy = (monoPaint.descent() + monoPaint.ascent()) * 0.5f
        canvas.drawText(label, rect.centerX(), rect.centerY() - dy, monoPaint)
    }

    private fun drawEngineeringStrip(canvas: Canvas) {
        val top = topBarHeightPx
        val bottom = top + engStripHeightPx
        bgPaint.color = colorBg
        canvas.drawRect(0f, top, width.toFloat(), bottom, bgPaint)

        val snap = snapshot
        val res = lastResolution
        val telemetry = TaskTelemetry.state.value
        val modeTag = if (!dynamicAllowedForField) "STATIC(FIELD)" else config.sensitivity.label.uppercase()
        val info = if (res != null) {
            val g = res.geometricChar ?: '-'
            val r = res.resolvedChar ?: '-'
            val tag = when {
                res.wasRescued -> "RESCUED"
                res.protectedByCore -> "CORE"
                else -> "EXACT"
            }
            val pct = (res.resolvedProbability * 100f).roundToInt()
            "ENG | $modeTag | $g->$r ($pct%) $tag | p99=${"%.2f".format(telemetry.p99TotalMs)}ms"
        } else {
            val comp = snap?.computeLatencyMs ?: 0.0
            "ENG | $modeTag | prefix=\"$currentPrefix\" | calc=${"%.2f".format(comp)}ms"
        }
        engTextPaint.color = colorAccent
        val dy = (engTextPaint.descent() + engTextPaint.ascent()) * 0.5f
        canvas.drawText(info, dp(12f), (top + bottom) * 0.5f - dy, engTextPaint)
    }

    private fun drawKeyboard(canvas: Canvas) {
        val snap = snapshot
        val viz = config.visualizerMode
        val radius = dp(6f)

        // Pass 1: Key surfaces & labels
        for (i in layoutKeys.indices) {
            val key = layoutKeys[i]
            val r = key.rect
            tempRect.set(r.left, r.top, r.right, r.bottom)

            val isPressed = i == pressedKeyIndex
            val isShiftActive = key.spec.action == KeyAction.Shift &&
                (layer == KeyboardLayer.ALPHA_UPPER || layer == KeyboardLayer.ALPHA_CAPS)

            keyPaint.color = when {
                isPressed -> colorPressedKey
                isShiftActive -> colorAccent
                key.spec.isSpecial -> colorSpecialKey
                else -> colorKey
            }
            canvas.drawRoundRect(tempRect, radius, radius, keyPaint)

            keyBorderPaint.color = colorKeyBorder
            canvas.drawRoundRect(tempRect, radius, radius, keyBorderPaint)

            val paint = if (key.spec.isSpecial || key.spec.action == KeyAction.Space) specialTextPaint else keyTextPaint
            paint.color = when {
                isShiftActive -> colorOnAccent
                key.spec.action == KeyAction.Space -> colorSecondary
                else -> colorText
            }
            val dy = (paint.descent() + paint.ascent()) * 0.5f
            canvas.drawText(key.spec.label, r.centerX, r.centerY - dy, paint)
        }

        // Pass 2: Real-time Dynamic Hitbox Visualizer (OVERLAY & ENGINEERING)
        if (viz != VisualizerMode.OFF && snap != null && snap.dynamicEnabled) {
            val baseRgb = colorAccent and 0x00FFFFFF
            for (state in snap.predictedStates) {
                if (state.expansionFactor <= 0.02f) continue
                val vr = state.visualRect
                tempRect.set(vr.left, vr.top, vr.right, vr.bottom)

                val fillAlpha = (state.expansionFactor * 165f).roundToInt().coerceIn(18, 82)
                val strokeAlpha = (state.expansionFactor * 480f).roundToInt().coerceIn(60, 235)
                overlayFillPaint.color = (fillAlpha shl 24) or baseRgb
                overlayStrokePaint.color = (strokeAlpha shl 24) or baseRgb

                canvas.drawRoundRect(tempRect, dp(8f), dp(8f), overlayFillPaint)
                canvas.drawRoundRect(tempRect, dp(8f), dp(8f), overlayStrokePaint)

                // Engineering mode: protected core box + tabular probability %
                if (viz == VisualizerMode.ENGINEERING) {
                    val kr = layoutKeys[state.keyIndex].rect
                    val cw = kr.width * DynamicHitboxEngine.PROTECTED_CORE_RATIO * 0.5f
                    val ch = kr.height * DynamicHitboxEngine.PROTECTED_CORE_RATIO * 0.5f
                    tempRect.set(kr.centerX - cw, kr.centerY - ch, kr.centerX + cw, kr.centerY + ch)
                    coreDashPaint.color = (0x66 shl 24) or (colorSecondary and 0x00FFFFFF)
                    canvas.drawRect(tempRect, coreDashPaint)

                    val pct = (state.probability * 100f).roundToInt()
                    if (pct >= 4) {
                        monoPaint.color = colorAccent
                        canvas.drawText("$pct%", kr.centerX, kr.top + dp(11f), monoPaint)
                    }
                }
            }

            // Draw last touch crosshair & rescue vector (fades after 1.8 s)
            val res = lastResolution
            if (res != null && (System.currentTimeMillis() - lastResolutionTimeMs) < 1800L &&
                res.resolvedKeyIndex in layoutKeys.indices
            ) {
                val target = layoutKeys[res.resolvedKeyIndex].rect
                rescueLinePaint.color = colorAccent
                if (res.wasRescued) {
                    canvas.drawLine(res.touchX, res.touchY, target.centerX, target.centerY, rescueLinePaint)
                }
                canvas.drawCircle(res.touchX, res.touchY, dp(4f), rescueLinePaint)
            }
        }

        // Pass 3: Long-press Accent Popup Bar
        accentPopupKey?.let { key ->
            drawAccentPopup(canvas, key)
        }
    }

    private fun drawAccentPopup(canvas: Canvas, key: LayoutKey) {
        val accents = key.spec.accents
        if (accents.isEmpty()) return
        val cellW = dp(42f)
        val cellH = dp(48f)
        val totalW = cellW * accents.size
        val left = (key.rect.centerX - totalW * 0.5f).coerceIn(dp(6f), (width - totalW - dp(6f)).coerceAtLeast(dp(6f)))
        val bottom = (key.rect.top - dp(6f)).coerceAtLeast(topBarHeightPx + dp(4f))
        val top = (bottom - cellH).coerceAtLeast(dp(4f))

        popupRect.set(left, top, left + totalW, bottom)
        keyPaint.color = colorSpecialKey
        canvas.drawRoundRect(popupRect, dp(10f), dp(10f), keyPaint)
        overlayStrokePaint.color = colorAccent
        canvas.drawRoundRect(popupRect, dp(10f), dp(10f), overlayStrokePaint)

        val dy = (keyTextPaint.descent() + keyTextPaint.ascent()) * 0.5f
        for (i in accents.indices) {
            val cx = left + i * cellW
            if (i == accentSelectedIndex) {
                tempRect.set(cx + dp(3f), top + dp(3f), cx + cellW - dp(3f), bottom - dp(3f))
                keyPaint.color = colorAccent
                canvas.drawRoundRect(tempRect, dp(6f), dp(6f), keyPaint)
                keyTextPaint.color = colorOnAccent
            } else {
                keyTextPaint.color = colorText
            }
            canvas.drawText(accents[i].toString(), cx + cellW * 0.5f, (top + bottom) * 0.5f - dy, keyTextPaint)
        }
    }

    private fun drawEmojiPanel(canvas: Canvas) {
        val top = topBarHeightPx + engStripHeightPx
        val areaH = height - top
        val cols = 8
        val rows = 4
        val cellW = width.toFloat() / cols
        val cellH = (areaH - dp(44f)) / rows
        val dy = (keyTextPaint.descent() + keyTextPaint.ascent()) * 0.5f

        for (i in emojiList.indices) {
            val r = i / cols
            val c = i % cols
            val cx = c * cellW + cellW * 0.5f
            val cy = top + r * cellH + cellH * 0.5f
            canvas.drawText(emojiList[i], cx, cy - dy, keyTextPaint)
        }

        // Bottom return bar
        tempRect.set(dp(12f), height - dp(40f), width - dp(12f), height - dp(6f))
        keyPaint.color = colorSpecialKey
        canvas.drawRoundRect(tempRect, dp(6f), dp(6f), keyPaint)
        specialTextPaint.color = colorAccent
        val sdy = (specialTextPaint.descent() + specialTextPaint.ascent()) * 0.5f
        canvas.drawText("ABC  ·  VOLTAR AO TECLADO", tempRect.centerX(), tempRect.centerY() - sdy, specialTextPaint)
    }

    private fun drawClipboardPanel(canvas: Canvas) {
        val top = topBarHeightPx + engStripHeightPx + dp(8f)
        val items: List<ClipboardEntry> = LocalClipboardStore.snapshot()
        val rowH = dp(40f)
        val dy = (suggestionTextPaint.descent() + suggestionTextPaint.ascent()) * 0.5f

        if (items.isEmpty()) {
            suggestionTextPaint.color = colorSecondary
            canvas.drawText("Clipboard local vazio (copie texto para aparecer aqui)", width * 0.5f, top + dp(60f), suggestionTextPaint)
        } else {
            for (i in 0 until minOf(items.size, 4)) {
                val entry = items[i]
                val y = top + i * (rowH + dp(6f))
                tempRect.set(dp(12f), y, width - dp(12f), y + rowH)
                keyPaint.color = colorKey
                canvas.drawRoundRect(tempRect, dp(6f), dp(6f), keyPaint)
                keyBorderPaint.color = if (entry.pinned) colorAccent else colorKeyBorder
                canvas.drawRoundRect(tempRect, dp(6f), dp(6f), keyBorderPaint)

                val preview = if (entry.text.length > 38) entry.text.take(38) + "…" else entry.text
                suggestionTextPaint.color = colorText
                canvas.drawText(preview, tempRect.centerX(), tempRect.centerY() - dy, suggestionTextPaint)
            }
        }

        // Bottom return + clear bar
        val halfW = (width - dp(30f)) * 0.5f
        tempRect.set(dp(12f), height - dp(40f), dp(12f) + halfW, height - dp(6f))
        keyPaint.color = colorSpecialKey
        canvas.drawRoundRect(tempRect, dp(6f), dp(6f), keyPaint)
        specialTextPaint.color = colorAccent
        val sdy = (specialTextPaint.descent() + specialTextPaint.ascent()) * 0.5f
        canvas.drawText("ABC", tempRect.centerX(), tempRect.centerY() - sdy, specialTextPaint)

        tempRect.set(dp(18f) + halfW, height - dp(40f), width - dp(12f), height - dp(6f))
        keyPaint.color = colorSpecialKey
        canvas.drawRoundRect(tempRect, dp(6f), dp(6f), keyPaint)
        specialTextPaint.color = colorSecondary
        canvas.drawText("LIMPAR", tempRect.centerX(), tempRect.centerY() - sdy, specialTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                spaceDragging = false
                accentPopupKey = null

                // Top bar clicks
                if (y <= topBarHeightPx) {
                    handleTopBarTap(x, y)
                    return true
                }

                // Alternate panels
                if (activePanel == ActivePanel.EMOJI) {
                    handleEmojiTap(x, y)
                    return true
                }
                if (activePanel == ActivePanel.CLIPBOARD) {
                    handleClipboardTap(x, y)
                    return true
                }

                // Resolve touch using DynamicHitboxEngine
                val snap = snapshot
                if (snap != null && layoutKeys.isNotEmpty()) {
                    val res = engine.resolveTouch(x, y, snap)
                    pressedKeyIndex = res.resolvedKeyIndex
                    lastResolution = res
                    lastResolutionTimeMs = System.currentTimeMillis()
                    TaskTelemetry.recordTouch(snap.computeLatencyMs, res)

                    if (pressedKeyIndex in layoutKeys.indices) {
                        val action = layoutKeys[pressedKeyIndex].spec.action
                        if (action == KeyAction.Space) {
                            lastSpaceDragX = x
                        } else if (action == KeyAction.Backspace) {
                            callbacks?.onBackspace()
                            triggerHaptic()
                            mainHandler.postDelayed(backspaceRepeatRunnable, 340L)
                        } else {
                            mainHandler.postDelayed(longPressRunnable, 380L)
                        }
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePanel != ActivePanel.KEYBOARD) return true

                // Accent popup selection drag
                val popupKey = accentPopupKey
                if (popupKey != null) {
                    val cellW = dp(42f)
                    val idx = ((x - popupRect.left) / cellW).toInt().coerceIn(0, popupKey.spec.accents.lastIndex)
                    if (idx != accentSelectedIndex) {
                        accentSelectedIndex = idx
                        triggerHaptic()
                        invalidate()
                    }
                    return true
                }

                // Spacebar cursor drag
                if (pressedKeyIndex in layoutKeys.indices &&
                    layoutKeys[pressedKeyIndex].spec.action == KeyAction.Space
                ) {
                    val dx = x - lastSpaceDragX
                    val stepPx = dp(18f)
                    if (abs(dx) >= stepPx) {
                        spaceDragging = true
                        val steps = (dx / stepPx).toInt()
                        callbacks?.onMoveCursor(steps)
                        lastSpaceDragX = x
                        triggerHaptic()
                    }
                    return true
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.removeCallbacks(backspaceRepeatRunnable)

                if (activePanel != ActivePanel.KEYBOARD) {
                    pressedKeyIndex = -1
                    return true
                }

                val popupKey = accentPopupKey
                if (popupKey != null) {
                    val chosen = popupKey.spec.accents.getOrNull(accentSelectedIndex)
                    accentPopupKey = null
                    pressedKeyIndex = -1
                    if (chosen != null && event.actionMasked == MotionEvent.ACTION_UP) {
                        callbacks?.onCommitText(chosen.toString())
                        if (layer == KeyboardLayer.ALPHA_UPPER) {
                            layer = KeyboardLayer.ALPHA_LOWER
                            rebuildLayout(width, height)
                            recomputePrediction()
                        }
                    }
                    invalidate()
                    return true
                }

                val idx = pressedKeyIndex
                pressedKeyIndex = -1
                if (event.actionMasked == MotionEvent.ACTION_UP && idx in layoutKeys.indices && !spaceDragging) {
                    val spec = layoutKeys[idx].spec
                    if (spec.action != KeyAction.Backspace) {
                        executeKeyAction(spec.action)
                    }
                }
                spaceDragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTopBarTap(x: Float, y: Float) {
        if (vizToggleRect.contains(x, y)) {
            triggerHaptic()
            val next = config.visualizerMode.next()
            callbacks?.onVisualizerModeChanged(next)
            return
        }
        if (langToggleRect.contains(x, y)) {
            triggerHaptic()
            val next = if (config.language == Language.PT_BR) Language.EN_US else Language.PT_BR
            callbacks?.onLanguageSwitched(next)
            return
        }
        if (clipToggleRect.contains(x, y)) {
            triggerHaptic()
            activePanel = if (activePanel == ActivePanel.CLIPBOARD) ActivePanel.KEYBOARD else ActivePanel.CLIPBOARD
            invalidate()
            return
        }
        for (i in 0 until 3) {
            if (suggestionRects[i].contains(x, y)) {
                val item = suggestions.getOrNull(i) ?: return
                triggerHaptic()
                callbacks?.onCommitWordSuggestion(item.word, currentPrefix.length)
                return
            }
        }
    }

    private fun handleEmojiTap(x: Float, y: Float) {
        if (y >= height - dp(44f)) {
            triggerHaptic()
            activePanel = ActivePanel.KEYBOARD
            invalidate()
            return
        }
        val top = topBarHeightPx + engStripHeightPx
        val areaH = height - top - dp(44f)
        val col = ((x / width) * 8).toInt().coerceIn(0, 7)
        val row = (((y - top) / areaH) * 4).toInt().coerceIn(0, 3)
        val idx = row * 8 + col
        emojiList.getOrNull(idx)?.let { emoji ->
            triggerHaptic()
            callbacks?.onCommitText(emoji)
        }
    }

    private fun handleClipboardTap(x: Float, y: Float) {
        if (y >= height - dp(44f)) {
            triggerHaptic()
            if (x < width * 0.5f) {
                activePanel = ActivePanel.KEYBOARD
            } else {
                LocalClipboardStore.clearUnpinned()
            }
            invalidate()
            return
        }
        val top = topBarHeightPx + engStripHeightPx + dp(8f)
        val rowStep = dp(46f)
        val idx = ((y - top) / rowStep).toInt()
        val items = LocalClipboardStore.snapshot()
        items.getOrNull(idx)?.let { entry ->
            triggerHaptic()
            callbacks?.onCommitText(entry.text)
            activePanel = ActivePanel.KEYBOARD
            invalidate()
        }
    }

    private fun executeKeyAction(action: KeyAction) {
        triggerHaptic()
        when (action) {
            is KeyAction.Character -> {
                callbacks?.onCommitText(action.char.toString())
                if (layer == KeyboardLayer.ALPHA_UPPER) {
                    layer = KeyboardLayer.ALPHA_LOWER
                    rebuildLayout(width, height)
                    recomputePrediction()
                }
            }

            KeyAction.Shift -> {
                val now = System.currentTimeMillis()
                layer = when {
                    layer == KeyboardLayer.ALPHA_LOWER -> KeyboardLayer.ALPHA_UPPER
                    layer == KeyboardLayer.ALPHA_UPPER && (now - lastShiftTapMs) < 350L -> KeyboardLayer.ALPHA_CAPS
                    else -> KeyboardLayer.ALPHA_LOWER
                }
                lastShiftTapMs = now
                rebuildLayout(width, height)
                recomputePrediction()
            }

            KeyAction.Space -> {
                val now = System.currentTimeMillis()
                val topAuto = suggestions.firstOrNull()?.takeIf {
                    it.isAutoCorrect && config.autoCorrect && currentPrefix.isNotEmpty()
                }
                if (topAuto != null) {
                    callbacks?.onCommitWordSuggestion(topAuto.word, currentPrefix.length)
                } else if (currentPrefix.isEmpty() && (now - lastSpaceTapMs) < 320L) {
                    // Double-tap space inserts period + space
                    callbacks?.onBackspace()
                    callbacks?.onCommitText(". ")
                } else {
                    callbacks?.onCommitText(" ")
                }
                lastSpaceTapMs = now
            }

            KeyAction.Enter -> callbacks?.onEnter()

            KeyAction.ToggleSymbols -> {
                layer = if (layer == KeyboardLayer.SYMBOLS || layer == KeyboardLayer.SYMBOLS_EXTENDED) {
                    KeyboardLayer.ALPHA_LOWER
                } else {
                    KeyboardLayer.SYMBOLS
                }
                rebuildLayout(width, height)
                recomputePrediction()
            }

            KeyAction.ToggleExtendedSymbols -> {
                layer = if (layer == KeyboardLayer.SYMBOLS_EXTENDED) {
                    KeyboardLayer.SYMBOLS
                } else {
                    KeyboardLayer.SYMBOLS_EXTENDED
                }
                rebuildLayout(width, height)
                recomputePrediction()
            }

            KeyAction.ToggleEmoji -> {
                activePanel = if (activePanel == ActivePanel.EMOJI) ActivePanel.KEYBOARD else ActivePanel.EMOJI
            }

            KeyAction.ToggleClipboard -> {
                activePanel = if (activePanel == ActivePanel.CLIPBOARD) ActivePanel.KEYBOARD else ActivePanel.CLIPBOARD
            }

            KeyAction.SwitchLanguage -> {
                val next = if (config.language == Language.PT_BR) Language.EN_US else Language.PT_BR
                callbacks?.onLanguageSwitched(next)
            }

            KeyAction.Backspace -> callbacks?.onBackspace()
        }
    }

    private fun triggerHaptic() {
        if (config.hapticsEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
