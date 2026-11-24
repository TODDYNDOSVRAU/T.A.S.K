// SPDX-License-Identifier: GPL-3.0-only
package com.tal.task

import android.content.ComponentCallbacks2
import android.content.Context
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Clean-room SHARK²-inspired Offline Glide / Swipe Typing Decoder for T.A.S.K.
 *
 * Combines:
 * - Location match (point-by-point arc-length-resampled spatial error vs ideal key-center polyline)
 * - Scale- & translation-invariant shape match (normalized to stroke centroid & span)
 * - Monotonic ordered letter alignment (verifies each letter along the trajectory order)
 * - Sharp corner coverage (penalizes trajectories with turns not explained by candidate vertices)
 * - Unigram log-frequency prior + TaskHitboxBridge full-writing context & topic fusion
 *
 * 100% offline, zero INTERNET permission, CSR bucket indexing by (first, last) character.
 */
object TaskGlideDecoder {

    data class LanguageDecodeSpec(
        val locale: Locale,
        val weightForLocale: Float,
        val sourceDict: Dictionary,
        val isBlacklisted: (String) -> Boolean
    )

    class GlideLexicon(
        val words: Array<String>,
        val folded: Array<String>,
        val collapsed: Array<String>,
        val logFreqs: FloatArray,
        val bucketStart: IntArray,   // size 26 * 26 + 1 = 677
        val bucketWordIdx: IntArray  // indices into words/folded/collapsed/logFreqs
    ) {
        val wordCount: Int get() = words.size

        companion object {
            fun fromLines(lines: Sequence<String>): GlideLexicon {
                val wordList = ArrayList<String>(16000)
                val foldedList = ArrayList<String>(16000)
                val collapsedList = ArrayList<String>(16000)
                val logFreqList = ArrayList<Float>(16000)

                val bucketCounts = IntArray(676)
                val foldBuf = CharArray(48)
                val collBuf = CharArray(48)

                for (rawLine in lines) {
                    val tab = rawLine.indexOf('\t')
                    if (tab <= 0) continue
                    val origWord = rawLine.substring(0, tab).trim()
                    if (origWord.isEmpty() || origWord.length > 36) continue

                    val freq = rawLine.substring(tab + 1).trim().toIntOrNull()?.coerceAtLeast(1) ?: 100
                    var fLen = 0
                    var cLen = 0
                    var lastColl = '\u0000'
                    var validWord = true

                    for (k in 0 until origWord.length) {
                        val fc = foldCharAscii(origWord[k])
                        if (fc == '\u0000') {
                            validWord = false
                            break
                        }
                        if (fLen < foldBuf.size) {
                            foldBuf[fLen++] = fc
                            if (fc != lastColl) {
                                collBuf[cLen++] = fc
                                lastColl = fc
                            }
                        }
                    }
                    if (!validWord || fLen == 0 || cLen == 0) continue

                    val firstIdx = foldBuf[0] - 'a'
                    val lastIdx = foldBuf[fLen - 1] - 'a'
                    if (firstIdx !in 0..25 || lastIdx !in 0..25) continue

                    val wordIdx = wordList.size
                    wordList.add(origWord)
                    foldedList.add(String(foldBuf, 0, fLen))
                    collapsedList.add(String(collBuf, 0, cLen))
                    logFreqList.add(ln(freq.toFloat() + 1f))
                    bucketCounts[firstIdx * 26 + lastIdx]++
                    // Keep index count synced with wordList
                    if (wordIdx == Int.MAX_VALUE) break
                }

                val bucketStart = IntArray(677)
                for (b in 0 until 676) {
                    bucketStart[b + 1] = bucketStart[b] + bucketCounts[b]
                }
                val total = bucketStart[676]
                val bucketWordIdx = IntArray(total)
                val cursor = bucketStart.copyOf()

                for (i in 0 until total) {
                    val f = foldedList[i]
                    val b = (f[0] - 'a') * 26 + (f[f.length - 1] - 'a')
                    bucketWordIdx[cursor[b]++] = i
                }

                val freqsArr = FloatArray(total) { i -> logFreqList[i] }
                return GlideLexicon(
                    words = wordList.toTypedArray(),
                    folded = foldedList.toTypedArray(),
                    collapsed = collapsedList.toTypedArray(),
                    logFreqs = freqsArr,
                    bucketStart = bucketStart,
                    bucketWordIdx = bucketWordIdx
                )
            }

            fun loadFromAsset(context: Context, assetPath: String): GlideLexicon? {
                return try {
                    context.assets.open(assetPath).use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                            fromLines(reader.lineSequence())
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private const val RESAMPLE_N = 32
    private const val MAX_CANDIDATES = 8
    private const val MAX_ENERGY = 2.35f
    private const val CORNER_ANGLE_COS_THRESHOLD = 0.766f // cos(40 deg)

    private val loadLock = Any()
    private val decodeLock = Any()

    @Volatile private var ptLexicon: GlideLexicon? = null
    @Volatile private var enLexicon: GlideLexicon? = null
    @Volatile var lastGlideWord: String = "-"
        private set
    @Volatile var lastGlideLatencyMs: Double = 0.0
        private set
    @Volatile var totalGlides: Int = 0
        private set

    // Cached keyboard geometry (avoid scanning keys every 25 ms during glide updates)
    private var cachedWidth = -1
    private var cachedHeight = -1
    private var cachedKeyCount = -1
    private var cachedLocaleTag = ""
    private val cachedKeyX = FloatArray(26)
    private val cachedKeyY = FloatArray(26)
    private val cachedHasKey = BooleanArray(26)
    private val cachedKeyDist = FloatArray(26 * 26)
    private var cachedAvgKeyW = 1f
    private var cachedAvgKeyH = 1f

    // Pre-allocated scratch buffers guarded by decodeLock (0 allocations per candidate evaluation)
    private val rawX = FloatArray(512)
    private val rawY = FloatArray(512)
    private val cumArc = FloatArray(512)
    private val uX = FloatArray(RESAMPLE_N)
    private val uY = FloatArray(RESAMPLE_N)
    private val sX = FloatArray(RESAMPLE_N)
    private val sY = FloatArray(RESAMPLE_N)
    private val cornerX = FloatArray(12)
    private val cornerY = FloatArray(12)

    private val startKeys = IntArray(4)
    private val startDists = FloatArray(4)
    private val endKeys = IntArray(6)
    private val endDists = FloatArray(6)

    private val polyX = FloatArray(48)
    private val polyY = FloatArray(48)
    private val polyArc = FloatArray(48)
    private val wX = FloatArray(RESAMPLE_N)
    private val wY = FloatArray(RESAMPLE_N)
    private val wsX = FloatArray(RESAMPLE_N)
    private val wsY = FloatArray(RESAMPLE_N)
    private val dpPrev = FloatArray(RESAMPLE_N)
    private val dpCurr = FloatArray(RESAMPLE_N)

    // Pre-allocated top-K result slots (guarded by decodeLock)
    private val topWords = arrayOfNulls<String>(MAX_CANDIDATES)
    private val topScores = IntArray(MAX_CANDIDATES)
    private val topOrigScores = IntArray(MAX_CANDIDATES)
    private val topDicts = arrayOfNulls<Dictionary>(MAX_CANDIDATES)

    private fun getOrLoadLexicon(context: Context, isPt: Boolean): GlideLexicon? {
        if (isPt) {
            ptLexicon?.let { return it }
            synchronized(loadLock) {
                ptLexicon?.let { return it }
                return GlideLexicon.loadFromAsset(context, "dict/pt_br.tsv")?.also { ptLexicon = it }
            }
        } else {
            enLexicon?.let { return it }
            synchronized(loadLock) {
                enLexicon?.let { return it }
                return GlideLexicon.loadFromAsset(context, "dict/en_us.tsv")?.also { enLexicon = it }
            }
        }
    }

    @JvmStatic
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            synchronized(loadLock) {
                ptLexicon = null
                enLexicon = null
            }
        }
    }

    fun decode(
        context: Context,
        xs: IntArray,
        ys: IntArray,
        pointerIds: IntArray,
        pointerSize: Int,
        keyboard: Keyboard,
        specs: List<LanguageDecodeSpec>,
        prevWord1: String?,
        prevWord2: String?,
        isUpdating: Boolean
    ): List<SuggestedWordInfo> {
        if (pointerSize < 2 || specs.isEmpty()) return emptyList()
        val t0 = System.nanoTime()

        // Filter primary pointer raw coords first
        val primaryId = pointerIds[0]
        var count = 0
        val cap = min(pointerSize, rawX.size)
        val filteredX = IntArray(cap)
        val filteredY = IntArray(cap)
        for (i in 0 until pointerSize) {
            if (pointerIds[i] == primaryId && count < cap) {
                filteredX[count] = xs[i]
                filteredY[count] = ys[i]
                count++
            }
        }
        if (count < 2) return emptyList()

        val foldedPrev1 = foldStringAscii(prevWord1)
        val foldedPrev2 = foldStringAscii(prevWord2)

        val results = synchronized(decodeLock) {
            updateKeyboardGeometryLocked(keyboard)
            decodeCoreLocked(
                xs = filteredX,
                ys = filteredY,
                pointCount = count,
                keyX = cachedKeyX,
                keyY = cachedKeyY,
                hasKey = cachedHasKey,
                keyDistNorm = cachedKeyDist,
                keyW = cachedAvgKeyW,
                keyH = cachedAvgKeyH,
                specs = specs,
                lexiconForLocale = { loc ->
                    val isPt = loc.language.equals("pt", ignoreCase = true)
                    if (isPt || loc.language.equals("en", ignoreCase = true)) {
                        getOrLoadLexicon(context, isPt)
                    } else null
                },
                prevWord1 = foldedPrev1,
                prevWord2 = foldedPrev2,
                isUpdating = isUpdating
            )
        }

        if (!isUpdating && results.isNotEmpty()) {
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            lastGlideLatencyMs = ms
            lastGlideWord = results[0].word
            totalGlides++
        }
        return results
    }

    /**
     * Pure-JVM testable entry point using explicit key centers and preloaded lexicons.
     */
    fun decodeWithGeometry(
        xs: IntArray,
        ys: IntArray,
        pointCount: Int,
        keyCentersX: FloatArray,
        keyCentersY: FloatArray,
        hasKey: BooleanArray,
        keyW: Float,
        keyH: Float,
        specs: List<LanguageDecodeSpec>,
        ptLexicon: GlideLexicon?,
        enLexicon: GlideLexicon?,
        prevWord1: String? = null,
        prevWord2: String? = null,
        isUpdating: Boolean = false
    ): List<SuggestedWordInfo> {
        if (pointCount < 2 || specs.isEmpty() || keyW <= 0f) return emptyList()
        val distMat = FloatArray(26 * 26)
        computeDistMatrix(keyCentersX, keyCentersY, hasKey, keyW, distMat)
        val p1 = foldStringAscii(prevWord1)
        val p2 = foldStringAscii(prevWord2)

        return synchronized(decodeLock) {
            decodeCoreLocked(
                xs = xs,
                ys = ys,
                pointCount = pointCount,
                keyX = keyCentersX,
                keyY = keyCentersY,
                hasKey = hasKey,
                keyDistNorm = distMat,
                keyW = keyW,
                keyH = keyH,
                specs = specs,
                lexiconForLocale = { loc ->
                    when {
                        loc.language.equals("pt", ignoreCase = true) -> ptLexicon
                        loc.language.equals("en", ignoreCase = true) -> enLexicon
                        else -> null
                    }
                },
                prevWord1 = p1,
                prevWord2 = p2,
                isUpdating = isUpdating
            )
        }
    }

    private fun updateKeyboardGeometryLocked(keyboard: Keyboard) {
        val sortedKeys = keyboard.sortedKeys
        val localeTag = keyboard.mId.locale.toLanguageTag()
        if (cachedWidth == keyboard.mOccupiedWidth &&
            cachedHeight == keyboard.mOccupiedHeight &&
            cachedKeyCount == sortedKeys.size &&
            cachedLocaleTag == localeTag
        ) {
            return
        }
        cachedWidth = keyboard.mOccupiedWidth
        cachedHeight = keyboard.mOccupiedHeight
        cachedKeyCount = sortedKeys.size
        cachedLocaleTag = localeTag
        cachedHasKey.fill(false)

        var wSum = 0f
        var hSum = 0f
        var alphaCount = 0
        for (i in sortedKeys.indices) {
            val key = sortedKeys[i]
            val code = key.code
            if (code < 0) continue
            val ch = code.toChar().lowercaseChar()
            if (ch !in 'a'..'z') continue
            val idx = ch - 'a'
            if (!cachedHasKey[idx]) {
                cachedHasKey[idx] = true
                val hb = key.hitBox
                if (!hb.isEmpty) {
                    cachedKeyX[idx] = hb.exactCenterX()
                    cachedKeyY[idx] = hb.exactCenterY()
                    wSum += hb.width().toFloat()
                    hSum += hb.height().toFloat()
                } else {
                    cachedKeyX[idx] = key.x + key.width * 0.5f
                    cachedKeyY[idx] = key.y + key.height * 0.5f
                    wSum += key.width.toFloat()
                    hSum += key.height.toFloat()
                }
                alphaCount++
            }
        }
        cachedAvgKeyW = if (alphaCount > 0) (wSum / alphaCount).coerceAtLeast(1f) else keyboard.mMostCommonKeyWidth.toFloat().coerceAtLeast(1f)
        cachedAvgKeyH = if (alphaCount > 0) (hSum / alphaCount).coerceAtLeast(1f) else keyboard.mMostCommonKeyHeight.toFloat().coerceAtLeast(1f)
        computeDistMatrix(cachedKeyX, cachedKeyY, cachedHasKey, cachedAvgKeyW, cachedKeyDist)
    }

    private fun computeDistMatrix(
        keyX: FloatArray,
        keyY: FloatArray,
        hasKey: BooleanArray,
        keyW: Float,
        outDistNorm: FloatArray
    ) {
        val invKeyW = 1f / keyW.coerceAtLeast(1f)
        for (i in 0 until 26) {
            for (j in 0 until 26) {
                outDistNorm[i * 26 + j] = if (i == j || !hasKey[i] || !hasKey[j]) {
                    0f
                } else {
                    hypot(keyX[j] - keyX[i], keyY[j] - keyY[i]) * invKeyW
                }
            }
        }
    }

    private fun decodeCoreLocked(
        xs: IntArray,
        ys: IntArray,
        pointCount: Int,
        keyX: FloatArray,
        keyY: FloatArray,
        hasKey: BooleanArray,
        keyDistNorm: FloatArray,
        keyW: Float,
        keyH: Float,
        specs: List<LanguageDecodeSpec>,
        lexiconForLocale: (Locale) -> GlideLexicon?,
        prevWord1: String,
        prevWord2: String,
        isUpdating: Boolean
    ): List<SuggestedWordInfo> {
        val minStep = keyW * 0.06f
        var dedup = 0
        rawX[0] = xs[0].toFloat()
        rawY[0] = ys[0].toFloat()
        cumArc[0] = 0f
        dedup = 1

        val nPts = min(pointCount, rawX.size)
        for (i in 1 until nPts) {
            val px = xs[i].toFloat()
            val py = ys[i].toFloat()
            val prevX = rawX[dedup - 1]
            val prevY = rawY[dedup - 1]
            val d = hypot(px - prevX, py - prevY)
            if (d >= minStep || i == nPts - 1) {
                if (d > 0f) {
                    rawX[dedup] = px
                    rawY[dedup] = py
                    cumArc[dedup] = cumArc[dedup - 1] + d
                    dedup++
                }
            }
        }
        val pathLen = cumArc[dedup - 1]
        if (dedup < 2 || pathLen < 0.45f * keyW) {
            return emptyList()
        }

        // Uniform arc-length resampling into uX[32], uY[32]
        val stepArc = pathLen / (RESAMPLE_N - 1)
        var seg = 0
        for (r in 0 until RESAMPLE_N) {
            val targetArc = if (r == RESAMPLE_N - 1) pathLen else r * stepArc
            while (seg + 1 < dedup - 1 && cumArc[seg + 1] < targetArc) {
                seg++
            }
            val a0 = cumArc[seg]
            val a1 = cumArc[seg + 1]
            val span = (a1 - a0).coerceAtLeast(1e-5f)
            val t = ((targetArc - a0) / span).coerceIn(0f, 1f)
            uX[r] = rawX[seg] + (rawX[seg + 1] - rawX[seg]) * t
            uY[r] = rawY[seg] + (rawY[seg + 1] - rawY[seg]) * t
        }

        // Bounding box + centroid-normalized shape
        var uMinX = Float.MAX_VALUE
        var uMaxX = -Float.MAX_VALUE
        var uMinY = Float.MAX_VALUE
        var uMaxY = -Float.MAX_VALUE
        var sumX = 0f
        var sumY = 0f
        for (r in 0 until RESAMPLE_N) {
            val x = uX[r]
            val y = uY[r]
            if (x < uMinX) uMinX = x
            if (x > uMaxX) uMaxX = x
            if (y < uMinY) uMinY = y
            if (y > uMaxY) uMaxY = y
            sumX += x
            sumY += y
        }
        val uCx = sumX / RESAMPLE_N
        val uCy = sumY / RESAMPLE_N
        val uScale = max(max(uMaxX - uMinX, uMaxY - uMinY), keyW)
        val invUScale = 1f / uScale
        for (r in 0 until RESAMPLE_N) {
            sX[r] = (uX[r] - uCx) * invUScale
            sY[r] = (uY[r] - uCy) * invUScale
        }

        // Expanded bounding box for pruning
        val bboxPad = 1.75f * keyW
        val boxLeft = uMinX - bboxPad
        val boxRight = uMaxX + bboxPad
        val boxTop = uMinY - bboxPad
        val boxBottom = uMaxY + bboxPad

        // Detect sharp turning corners along uX, uY (angle > 40 deg -> cos < 0.766)
        var cornerCount = 0
        for (r in 2 until RESAMPLE_N - 2 step 2) {
            val v1x = uX[r] - uX[r - 2]
            val v1y = uY[r] - uY[r - 2]
            val v2x = uX[r + 2] - uX[r]
            val v2y = uY[r + 2] - uY[r]
            val l1 = hypot(v1x, v1y)
            val l2 = hypot(v2x, v2y)
            if (l1 > keyW * 0.18f && l2 > keyW * 0.18f) {
                val dotCos = (v1x * v2x + v1y * v2y) / (l1 * l2)
                if (dotCos < CORNER_ANGLE_COS_THRESHOLD && cornerCount < cornerX.size) {
                    cornerX[cornerCount] = uX[r]
                    cornerY[cornerCount] = uY[r]
                    cornerCount++
                }
            }
        }

        // Start & end nearest key candidates
        val startCount = findNearestKeys(uX[0], uY[0], keyX, keyY, hasKey, 3, 1.55f * keyW, startKeys, startDists)
        val maxEndK = if (isUpdating) 5 else 4
        val maxEndR = (if (isUpdating) 2.10f else 1.80f) * keyW
        val endCount = findNearestKeys(uX[RESAMPLE_N - 1], uY[RESAMPLE_N - 1], keyX, keyY, hasKey, maxEndK, maxEndR, endKeys, endDists)
        if (startCount == 0 || endCount == 0) return emptyList()

        // Reset top-K candidate buffer
        var topCount = 0
        topWords.fill(null)

        val invKeyW = 1f / keyW
        val minIdealLen = pathLen * 0.30f - 1.5f * keyW
        val maxIdealLen = pathLen * 2.0f + 1.8f * keyW
        val allowSingleLetter = pathLen < 1.1f * keyW
        val hasCtx = prevWord1.isNotEmpty() || prevWord2.isNotEmpty()

        for (sIdx in specs.indices) {
            val spec = specs[sIdx]
            val lexicon = lexiconForLocale(spec.locale) ?: continue
            val isPt = spec.locale.language.equals("pt", ignoreCase = true)
            val weight = spec.weightForLocale
            val words = lexicon.words
            val folded = lexicon.folded
            val collapsed = lexicon.collapsed
            val logFreqs = lexicon.logFreqs
            val bStart = lexicon.bucketStart
            val bWordIdx = lexicon.bucketWordIdx

            for (si in 0 until startCount) {
                val sk = startKeys[si]
                val sDistNorm = startDists[si] * invKeyW
                for (ei in 0 until endCount) {
                    val ek = endKeys[ei]
                    val eDistNorm = endDists[ei] * invKeyW
                    val anchorCost = (sDistNorm + eDistNorm) * 0.32f

                    val bucket = sk * 26 + ek
                    val from = bStart[bucket]
                    val to = bStart[bucket + 1]
                    for (bi in from until to) {
                        val wi = bWordIdx[bi]
                        val fWord = folded[wi]
                        if (fWord.length < 2 && !allowSingleLetter) continue
                        val origWord = words[wi]
                        if (spec.isBlacklisted(origWord)) continue

                        val col = collapsed[wi]
                        val m = col.length
                        if (m == 0 || m > polyX.size) continue

                        // 1. Fast ideal path length & bbox pruning
                        var idealLenNorm = 0f
                        var missingKey = false
                        var prevK = -1
                        var outOfBox = false
                        for (ci in 0 until m) {
                            val kIdx = col[ci] - 'a'
                            if (kIdx !in 0..25 || !hasKey[kIdx]) {
                                missingKey = true
                                break
                            }
                            val kx = keyX[kIdx]
                            val ky = keyY[kIdx]
                            if (kx < boxLeft || kx > boxRight || ky < boxTop || ky > boxBottom) {
                                outOfBox = true
                                break
                            }
                            polyX[ci] = kx
                            polyY[ci] = ky
                            if (ci == 0) {
                                polyArc[0] = 0f
                            } else {
                                val segNorm = keyDistNorm[prevK * 26 + kIdx]
                                idealLenNorm += segNorm
                                polyArc[ci] = idealLenNorm * keyW
                            }
                            prevK = kIdx
                        }
                        if (missingKey || outOfBox) continue

                        val idealLenPx = idealLenNorm * keyW
                        if (idealLenPx > maxIdealLen || idealLenPx < minIdealLen) continue

                        // 2. Resample candidate key-center polyline to RESAMPLE_N = 32 points
                        var wSumX = 0f
                        var wSumY = 0f
                        var wMinX = Float.MAX_VALUE
                        var wMaxX = -Float.MAX_VALUE
                        var wMinY = Float.MAX_VALUE
                        var wMaxY = -Float.MAX_VALUE

                        if (m == 1 || idealLenPx <= 1f) {
                            val cx0 = polyX[0]
                            val cy0 = polyY[0]
                            for (r in 0 until RESAMPLE_N) {
                                wX[r] = cx0
                                wY[r] = cy0
                            }
                            wSumX = cx0 * RESAMPLE_N
                            wSumY = cy0 * RESAMPLE_N
                            wMinX = cx0; wMaxX = cx0; wMinY = cy0; wMaxY = cy0
                        } else {
                            val wStep = idealLenPx / (RESAMPLE_N - 1)
                            var pSeg = 0
                            for (r in 0 until RESAMPLE_N) {
                                val target = if (r == RESAMPLE_N - 1) idealLenPx else r * wStep
                                while (pSeg + 1 < m - 1 && polyArc[pSeg + 1] < target) {
                                    pSeg++
                                }
                                val a0 = polyArc[pSeg]
                                val a1 = polyArc[pSeg + 1]
                                val span = (a1 - a0).coerceAtLeast(1e-5f)
                                val t = ((target - a0) / span).coerceIn(0f, 1f)
                                val px = polyX[pSeg] + (polyX[pSeg + 1] - polyX[pSeg]) * t
                                val py = polyY[pSeg] + (polyY[pSeg + 1] - polyY[pSeg]) * t
                                wX[r] = px
                                wY[r] = py
                                wSumX += px
                                wSumY += py
                                if (px < wMinX) wMinX = px
                                if (px > wMaxX) wMaxX = px
                                if (py < wMinY) wMinY = py
                                if (py > wMaxY) wMaxY = py
                            }
                        }

                        // 3. Location & Shape distances (32 points)
                        val wCx = wSumX / RESAMPLE_N
                        val wCy = wSumY / RESAMPLE_N
                        val wScale = max(max(wMaxX - wMinX, wMaxY - wMinY), keyW)
                        val invWScale = 1f / wScale

                        var locSum = 0f
                        var shapeSum = 0f
                        for (r in 0 until RESAMPLE_N) {
                            locSum += hypot(uX[r] - wX[r], uY[r] - wY[r])
                            val nsx = (wX[r] - wCx) * invWScale
                            val nsy = (wY[r] - wCy) * invWScale
                            wsX[r] = nsx
                            wsY[r] = nsy
                            shapeSum += hypot(sX[r] - nsx, sY[r] - nsy)
                        }
                        val locDist = (locSum * invKeyW) / RESAMPLE_N
                        val shapeDist = shapeSum / RESAMPLE_N

                        // Quick spatial prune before ordered/corner checks
                        val baseCost = 0.42f * locDist + 0.30f * shapeDist + anchorCost
                        if (baseCost > MAX_ENERGY + 0.55f) continue

                        // 4. Monotonic ordered letter alignment (O(m * RESAMPLE_N) prefix-min DP, zero allocations)
                        val orderedMiss: Float
                        if (m == 1) {
                            val dNorm = hypot(uX[0] - polyX[0], uY[0] - polyY[0]) * invKeyW
                            orderedMiss = if (dNorm > 0.55f) dNorm - 0.55f else 0f
                        } else {
                            val inf = 1e6f
                            for (r in 0 until RESAMPLE_N) {
                                dpPrev[r] = if (r <= 1) {
                                    val dNorm = hypot(uX[r] - polyX[0], uY[r] - polyY[0]) * invKeyW
                                    if (dNorm > 0.55f) dNorm - 0.55f else 0f
                                } else {
                                    inf
                                }
                            }
                            for (ci in 1 until m) {
                                val kx = polyX[ci]
                                val ky = polyY[ci]
                                var runningMin = inf
                                for (r in 0 until RESAMPLE_N) {
                                    val prev = dpPrev[r]
                                    if (prev < runningMin) runningMin = prev
                                    val dNorm = hypot(uX[r] - kx, uY[r] - ky) * invKeyW
                                    val pen = if (dNorm > 0.55f) dNorm - 0.55f else 0f
                                    dpCurr[r] = runningMin + pen
                                }
                                System.arraycopy(dpCurr, 0, dpPrev, 0, RESAMPLE_N)
                            }
                            orderedMiss = min(dpPrev[RESAMPLE_N - 2], dpPrev[RESAMPLE_N - 1])
                        }

                        // 5. Corner coverage: sharp user turns should align near a word key
                        var cornerMiss = 0f
                        for (c in 0 until cornerCount) {
                            val cx = cornerX[c]
                            val cy = cornerY[c]
                            var bestD = Float.MAX_VALUE
                            for (ci in 0 until m) {
                                val d = hypot(cx - polyX[ci], cy - polyY[ci])
                                if (d < bestD) bestD = d
                            }
                            val dNorm = bestD * invKeyW
                            if (dNorm > 0.85f) {
                                cornerMiss += (dNorm - 0.85f)
                            }
                        }

                        // 6. Frequency prior & whole-writing context bonus
                        val freqBonus = ((logFreqs[wi] - 4.0f).coerceIn(-1.0f, 6.0f)) * 0.09f
                        val ctxBonus = if (hasCtx) {
                            TaskHitboxBridge.getWordContextBoost(prevWord2, prevWord1, fWord, isPt) * 0.38f
                        } else 0f

                        val energy = baseCost + 0.48f * orderedMiss + 0.28f * cornerMiss - freqBonus - ctxBonus
                        if (energy > MAX_ENERGY) continue

                        val rawScore = (1_000_000f * exp(-0.85f * max(0f, energy + 0.40f)))
                            .toInt().coerceIn(50_000, 1_000_000)
                        val weightedScore = (rawScore * weight).toInt().coerceAtLeast(10_000)

                        topCount = insertTopCandidateLocked(
                            word = origWord,
                            score = weightedScore,
                            origScore = rawScore,
                            dict = spec.sourceDict,
                            currentCount = topCount
                        )
                    }
                }
            }
        }

        if (topCount == 0) return emptyList()
        val out = ArrayList<SuggestedWordInfo>(topCount)
        for (i in 0 until topCount) {
            val w = topWords[i] ?: continue
            val d = topDicts[i] ?: continue
            val info = SuggestedWordInfo(
                w,
                "",
                topScores[i],
                SuggestedWordInfo.KIND_CORRECTION,
                d,
                SuggestedWordInfo.NOT_AN_INDEX,
                SuggestedWordInfo.NOT_A_CONFIDENCE,
                null
            )
            info.originalScore = topOrigScores[i]
            out.add(info)
        }
        return out
    }

    private fun insertTopCandidateLocked(
        word: String,
        score: Int,
        origScore: Int,
        dict: Dictionary,
        currentCount: Int
    ): Int {
        // Deduplicate by exact word keeping higher score
        var existingIdx = -1
        for (i in 0 until currentCount) {
            if (topWords[i] == word) {
                existingIdx = i
                break
            }
        }
        if (existingIdx >= 0) {
            if (score <= topScores[existingIdx]) return currentCount
            // Remove existing and re-insert sorted
            for (j in existingIdx until currentCount - 1) {
                topWords[j] = topWords[j + 1]
                topScores[j] = topScores[j + 1]
                topOrigScores[j] = topOrigScores[j + 1]
                topDicts[j] = topDicts[j + 1]
            }
            val reduced = currentCount - 1
            return insertSortedLocked(word, score, origScore, dict, reduced)
        }
        return insertSortedLocked(word, score, origScore, dict, currentCount)
    }

    private fun insertSortedLocked(
        word: String,
        score: Int,
        origScore: Int,
        dict: Dictionary,
        count: Int
    ): Int {
        if (count == MAX_CANDIDATES && score <= topScores[MAX_CANDIDATES - 1]) {
            return count
        }
        var insertAt = min(count, MAX_CANDIDATES - 1)
        while (insertAt > 0 && score > topScores[insertAt - 1]) {
            if (insertAt < MAX_CANDIDATES) {
                topWords[insertAt] = topWords[insertAt - 1]
                topScores[insertAt] = topScores[insertAt - 1]
                topOrigScores[insertAt] = topOrigScores[insertAt - 1]
                topDicts[insertAt] = topDicts[insertAt - 1]
            }
            insertAt--
        }
        topWords[insertAt] = word
        topScores[insertAt] = score
        topOrigScores[insertAt] = origScore
        topDicts[insertAt] = dict
        return min(count + 1, MAX_CANDIDATES)
    }

    private fun findNearestKeys(
        px: Float,
        py: Float,
        keyX: FloatArray,
        keyY: FloatArray,
        hasKey: BooleanArray,
        maxK: Int,
        maxRadius: Float,
        outKeys: IntArray,
        outDists: FloatArray
    ): Int {
        var found = 0
        for (k in 0 until 26) {
            if (!hasKey[k]) continue
            val d = hypot(px - keyX[k], py - keyY[k])
            if (d > maxRadius) continue
            var pos = found
            while (pos > 0 && d < outDists[pos - 1]) {
                if (pos < maxK) {
                    outKeys[pos] = outKeys[pos - 1]
                    outDists[pos] = outDists[pos - 1]
                }
                pos--
            }
            if (pos < maxK) {
                outKeys[pos] = k
                outDists[pos] = d
                if (found < maxK) found++
            }
        }
        return found
    }

    private fun foldStringAscii(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (i in 0 until text.length) {
            val fc = foldCharAscii(text[i])
            if (fc != '\u0000') sb.append(fc)
        }
        return sb.toString()
    }

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
}
