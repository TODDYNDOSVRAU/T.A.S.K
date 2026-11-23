package com.tal.task.engine

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * iPhone-style Dynamic Touch-Target Hitbox Engine with D8 Hard Core Protection.
 *
 * Guarantees:
 * - Expansion factor is strictly capped at <= 0.40 (+40% radius).
 * - Every key retains a protected central core (CORE_RATIO = 0.56f) where touches ALWAYS
 *   resolve to that key, even if dictionary probability is 0%.
 * - Dynamic resizing only applies between alphabetic letter keys and is automatically
 *   disabled in URI / Email / Password / Incognito fields.
 */
class DynamicHitboxEngine {

    companion object {
        const val HARD_MAX_EXPANSION = 0.40f
        const val PROTECTED_CORE_RATIO = 0.56f
    }

    fun computeSnapshot(
        keys: List<LayoutKey>,
        dictionary: TrieDictionary,
        prefix: String,
        prevWord: String?,
        sensitivity: HitboxSensitivity,
        dynamicEnabled: Boolean
    ): HitboxSnapshot {
        val t0 = System.nanoTime()
        val probs = if (dynamicEnabled) {
            dictionary.nextCharProbabilities(prefix, prevWord)
        } else {
            FloatArray(26)
        }

        // Peak probability used to normalize visual & effective boost cleanly
        var maxProb = 0f
        for (i in 0 until 26) {
            if (probs[i] > maxProb) maxProb = probs[i]
        }

        val maxCap = min(sensitivity.maxExpansion, HARD_MAX_EXPANSION)
        val states = ArrayList<PredictedKeyState>(keys.size)

        for (key in keys) {
            val ch = key.letterChar
            if (!dynamicEnabled || ch == null || maxProb <= 0f) {
                states += PredictedKeyState(
                    keyIndex = key.index,
                    char = ch,
                    probability = 0f,
                    expansionFactor = 0f,
                    visualRect = key.rect
                )
                continue
            }

            val p = probs[ch - 'a']
            // Sublinear square-root curve gives crisp separation for top 3-5 probable letters
            val normalized = sqrt((p / maxProb).coerceIn(0f, 1f))
            val expansion = (normalized * sensitivity.gain * maxCap).coerceIn(0f, maxCap)

            // Visual hitbox expands proportionally to expansionFactor (capped at <= +40%)
            val dx = key.rect.width * expansion * 0.5f
            val dy = key.rect.height * expansion * 0.42f
            val visualRect = KeyRect(
                left = key.rect.left - dx,
                top = key.rect.top - dy,
                right = key.rect.right + dx,
                bottom = key.rect.bottom + dy
            )

            states += PredictedKeyState(
                keyIndex = key.index,
                char = ch,
                probability = p,
                expansionFactor = expansion,
                visualRect = visualRect
            )
        }

        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        return HitboxSnapshot(
            keys = keys,
            predictedStates = states,
            dynamicEnabled = dynamicEnabled,
            sensitivity = sensitivity,
            prefix = prefix,
            computeLatencyMs = elapsedMs
        )
    }

    fun resolveTouch(
        x: Float,
        y: Float,
        snapshot: HitboxSnapshot
    ): TouchResolution {
        val t0 = System.nanoTime()
        val keys = snapshot.keys
        if (keys.isEmpty()) {
            return TouchResolution(
                touchX = x,
                touchY = y,
                geometricKeyIndex = -1,
                resolvedKeyIndex = -1,
                geometricChar = null,
                resolvedChar = null,
                resolvedProbability = 0f,
                wasRescued = false,
                protectedByCore = false,
                latencyMs = 0.0
            )
        }

        // 1. Find geometric key (exact rect hit, or nearest rect center if in gap)
        var geoIndex = -1
        for (i in keys.indices) {
            if (keys[i].rect.contains(x, y)) {
                geoIndex = i
                break
            }
        }
        if (geoIndex == -1) {
            var bestDist = Float.MAX_VALUE
            for (i in keys.indices) {
                val r = keys[i].rect
                val cx = x.coerceIn(r.left, r.right)
                val cy = y.coerceIn(r.top, r.bottom)
                val d = hypot(x - cx, y - cy)
                if (d < bestDist) {
                    bestDist = d
                    geoIndex = i
                }
            }
        }

        val geoKey = keys[geoIndex]
        val geoChar = geoKey.letterChar

        // Non-letter keys or dynamic disabled -> strictly geometric
        if (!snapshot.dynamicEnabled || geoChar == null) {
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            return TouchResolution(
                touchX = x,
                touchY = y,
                geometricKeyIndex = geoIndex,
                resolvedKeyIndex = geoIndex,
                geometricChar = geoChar,
                resolvedChar = geoChar,
                resolvedProbability = snapshot.predictedStates[geoIndex].probability,
                wasRescued = false,
                protectedByCore = false,
                latencyMs = elapsedMs
            )
        }

        // 2. D8 Core Protection Guarantee: central 56% of key is inviolable
        val inCore = geoKey.rect.containsCore(x, y, PROTECTED_CORE_RATIO)
        if (inCore) {
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            return TouchResolution(
                touchX = x,
                touchY = y,
                geometricKeyIndex = geoIndex,
                resolvedKeyIndex = geoIndex,
                geometricChar = geoChar,
                resolvedChar = geoChar,
                resolvedProbability = snapshot.predictedStates[geoIndex].probability,
                wasRescued = false,
                protectedByCore = true,
                latencyMs = elapsedMs
            )
        }

        // 3. Outside core: evaluate weighted Voronoi distance across nearby letter keys
        val maxNeighborRadius = geoKey.rect.width * 1.45f
        var bestIndex = geoIndex
        var bestScore = Float.MAX_VALUE

        for (i in keys.indices) {
            val candidate = keys[i]
            if (candidate.letterChar == null) continue

            val dx = x - candidate.rect.centerX
            val dy = y - candidate.rect.centerY
            val dist = hypot(dx, dy)
            if (dist > maxNeighborRadius) continue

            val expansion = snapshot.predictedStates[i].expansionFactor.coerceIn(0f, HARD_MAX_EXPANSION)
            val effectiveDist = dist / (1f + expansion)
            if (effectiveDist < bestScore) {
                bestScore = effectiveDist
                bestIndex = i
            }
        }

        val resolvedKey = keys[bestIndex]
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        return TouchResolution(
            touchX = x,
            touchY = y,
            geometricKeyIndex = geoIndex,
            resolvedKeyIndex = bestIndex,
            geometricChar = geoChar,
            resolvedChar = resolvedKey.letterChar,
            resolvedProbability = snapshot.predictedStates[bestIndex].probability,
            wasRescued = bestIndex != geoIndex,
            protectedByCore = false,
            latencyMs = elapsedMs
        )
    }
}
