package com.tal.task.engine

import java.text.Normalizer
import kotlin.math.abs

/**
 * Compact, zero-network prefix Trie + word bigram + character bigram model.
 * Designed for sub-millisecond next-character probability and 3-slot word suggestions.
 */
class TrieDictionary(val language: Language) {

    private class Node {
        val children = arrayOfNulls<Node>(26)
        var subtreeMass: Long = 0L
        var terminalWord: String? = null
        var terminalFreq: Int = 0
        var userBoost: Int = 0
    }

    private val root = Node()
    private val wordBigrams = HashMap<String, HashMap<String, Int>>(256)
    private val charBigrams = Array(27) { FloatArray(26) } // index 26 = start of word
    private val charBigramTotals = FloatArray(27)
    private val userWords = LinkedHashSet<String>()

    var wordCount: Int = 0
        private set

    fun insertWord(rawWord: String, frequency: Int, isUserWord: Boolean = false) {
        val cleaned = rawWord.trim()
        if (cleaned.isEmpty()) return
        val folded = foldAscii(cleaned)
        if (folded.isEmpty()) return

        var node = root
        var prevCharIdx = 26
        val mass = frequency.coerceAtLeast(1).toLong()
        node.subtreeMass += mass

        for (i in folded.indices) {
            val idx = folded[i] - 'a'
            if (idx !in 0..25) return
            charBigrams[prevCharIdx][idx] += mass.toFloat()
            charBigramTotals[prevCharIdx] += mass.toFloat()
            prevCharIdx = idx

            var child = node.children[idx]
            if (child == null) {
                child = Node()
                node.children[idx] = child
            }
            child.subtreeMass += mass
            node = child
        }

        if (node.terminalWord == null) {
            wordCount++
            node.terminalWord = cleaned
            node.terminalFreq = frequency.coerceAtLeast(1)
        } else if (frequency > node.terminalFreq) {
            node.terminalWord = cleaned
            node.terminalFreq = frequency
        }

        if (isUserWord) {
            node.userBoost += 500
            userWords.add(cleaned)
        }
    }

    fun insertBigram(prevWord: String, nextWord: String, count: Int) {
        val prev = foldAscii(prevWord)
        if (prev.isEmpty()) return
        val map = wordBigrams.getOrPut(prev) { HashMap() }
        map[nextWord] = (map[nextWord] ?: 0) + count.coerceAtLeast(1)
    }

    fun learnWord(word: String, prevWord: String?) {
        val trimmed = word.trim()
        if (trimmed.length < 2) return
        insertWord(trimmed, frequency = 600, isUserWord = true)
        if (!prevWord.isNullOrBlank()) {
            insertBigram(prevWord, trimmed, count = 250)
        }
    }

    fun learnedWordCount(): Int = userWords.size

    /**
     * Computes probability distribution over 'a'..'z' (FloatArray of size 26)
     * for the next character given the current [prefix] and optional [prevWord].
     */
    fun nextCharProbabilities(prefix: String, prevWord: String? = null): FloatArray {
        val probs = FloatArray(26)
        val folded = foldAscii(prefix)

        // 1. Traverse Trie to prefix node
        var node: Node? = root
        for (i in folded.indices) {
            val idx = folded[i] - 'a'
            if (idx !in 0..25) {
                node = null
                break
            }
            node = node?.children?.get(idx)
            if (node == null) break
        }

        var totalMass = 0.0
        if (node != null) {
            for (i in 0 until 26) {
                val child = node.children[i]
                if (child != null && child.subtreeMass > 0L) {
                    val m = child.subtreeMass.toDouble()
                    probs[i] = m.toFloat()
                    totalMass += m
                }
            }
        }

        // 2. Blend word-bigram prior when at start of word (prefix empty)
        if (folded.isEmpty() && !prevWord.isNullOrBlank()) {
            val nextMap = wordBigrams[foldAscii(prevWord)]
            if (nextMap != null) {
                for ((nextW, count) in nextMap) {
                    val first = foldAscii(nextW).firstOrNull() ?: continue
                    val idx = first - 'a'
                    if (idx in 0..25) {
                        val boost = count * 80.0
                        probs[idx] += boost.toFloat()
                        totalMass += boost
                    }
                }
            }
        }

        // 3. Smooth with character bigram fallback (handles out-of-vocabulary prefixes cleanly)
        val lastIdx = if (folded.isEmpty()) 26 else (folded.last() - 'a').coerceIn(0, 25)
        val bigramDenom = charBigramTotals[lastIdx]
        if (totalMass <= 0.0) {
            if (bigramDenom > 0f) {
                for (i in 0 until 26) {
                    probs[i] = charBigrams[lastIdx][i] / bigramDenom
                }
            } else {
                val uniform = 1f / 26f
                for (i in 0 until 26) probs[i] = uniform
            }
            return probs
        }

        // Normalize Trie distribution and mix 88% Trie + 12% char-bigram smoothing
        val invTotal = (1.0 / totalMass).toFloat()
        var sum = 0f
        for (i in 0 until 26) {
            val trieP = probs[i] * invTotal
            val bgP = if (bigramDenom > 0f) charBigrams[lastIdx][i] / bigramDenom else 0f
            val mixed = trieP * 0.88f + bgP * 0.12f
            probs[i] = mixed
            sum += mixed
        }
        if (sum > 0f) {
            val invSum = 1f / sum
            for (i in 0 until 26) probs[i] *= invSum
        }
        return probs
    }

    /**
     * Returns up to [limit] word suggestions for the 3-slot bar.
     */
    fun suggestWords(
        prefix: String,
        prevWord: String? = null,
        limit: Int = 3
    ): List<WordSuggestion> {
        val folded = foldAscii(prefix)
        val candidates = ArrayList<WordSuggestion>(16)

        if (folded.isEmpty()) {
            // Next-word prediction from bigrams, falling back to top unigrams
            val nextMap = if (!prevWord.isNullOrBlank()) wordBigrams[foldAscii(prevWord)] else null
            if (nextMap != null && nextMap.isNotEmpty()) {
                nextMap.entries
                    .sortedByDescending { it.value }
                    .take(limit)
                    .forEach { (w, c) -> candidates += WordSuggestion(w, c.toDouble()) }
            }
            if (candidates.size < limit) {
                val defaults = if (language == Language.PT_BR) {
                    listOf("eu", "não", "que", "você", "para")
                } else {
                    listOf("the", "I", "you", "to", "and")
                }
                for (w in defaults) {
                    if (candidates.none { it.word.equals(w, ignoreCase = true) }) {
                        candidates += WordSuggestion(w, 10.0)
                    }
                    if (candidates.size >= limit) break
                }
            }
            return candidates.take(limit)
        }

        // 1. Prefix completions from Trie
        var node: Node? = root
        for (ch in folded) {
            val idx = ch - 'a'
            if (idx !in 0..25) {
                node = null
                break
            }
            node = node?.children?.get(idx)
            if (node == null) break
        }

        val bigramBoosts = if (!prevWord.isNullOrBlank()) wordBigrams[foldAscii(prevWord)] else null
        if (node != null) {
            collectTopWords(node, candidates, bigramBoosts, maxCollect = 16)
        }

        // 2. If prefix has no exact matches and length >= 3, check 1-character substitution/transposition
        if (candidates.isEmpty() && folded.length >= 3) {
            collectFuzzyCorrections(folded, candidates, maxCollect = 6)
        }

        val sorted = candidates
            .distinctBy { it.word.lowercase() }
            .sortedByDescending { it.score }
            .take(limit)
            .mapIndexed { idx, s ->
                val autoCorrect = idx == 0 &&
                    !s.word.equals(prefix, ignoreCase = true) &&
                    (foldAscii(s.word) == folded || s.score >= 250.0)
                s.copy(isAutoCorrect = autoCorrect)
            }

        return sorted
    }

    private fun collectTopWords(
        start: Node,
        out: MutableList<WordSuggestion>,
        bigramBoosts: Map<String, Int>?,
        maxCollect: Int
    ) {
        val stack = ArrayDeque<Node>()
        stack.addLast(start)
        var visited = 0
        while (stack.isNotEmpty() && visited < 220) {
            val curr = stack.removeLast()
            visited++
            val term = curr.terminalWord
            if (term != null) {
                val bg = bigramBoosts?.get(term) ?: 0
                val score = (curr.terminalFreq + curr.userBoost + bg * 40).toDouble()
                out += WordSuggestion(term, score)
            }
            // Push highest-mass children last so they pop first
            val children = curr.children
            for (i in 0 until 26) {
                val c = children[i]
                if (c != null) stack.addLast(c)
            }
            if (out.size >= maxCollect * 3) break
        }
    }

    private fun collectFuzzyCorrections(
        folded: String,
        out: MutableList<WordSuggestion>,
        maxCollect: Int
    ) {
        // Check adjacent transpositions (e.g., "teh" -> "the", "qeu" -> "que")
        val chars = folded.toCharArray()
        for (i in 0 until chars.size - 1) {
            val tmp = chars[i]
            chars[i] = chars[i + 1]
            chars[i + 1] = tmp
            lookupExact(String(chars))?.let { (word, freq) ->
                out += WordSuggestion(word, freq * 0.85, isAutoCorrect = true)
            }
            chars[i + 1] = chars[i]
            chars[i] = tmp
            if (out.size >= maxCollect) return
        }
    }

    private fun lookupExact(folded: String): Pair<String, Int>? {
        var node: Node? = root
        for (ch in folded) {
            val idx = ch - 'a'
            if (idx !in 0..25) return null
            node = node?.children?.get(idx) ?: return null
        }
        val term = node?.terminalWord ?: return null
        return term to (node.terminalFreq + node.userBoost)
    }

    companion object {
        private val combiningMarks = Regex("\\p{InCombiningDiacriticalMarks}+")

        fun foldAscii(input: String): String {
            if (input.isEmpty()) return ""
            val normalized = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
            val stripped = combiningMarks.replace(normalized, "")
            val sb = StringBuilder(stripped.length)
            for (ch in stripped) {
                if (ch in 'a'..'z') sb.append(ch)
            }
            return sb.toString()
        }

        fun loadFromLines(language: Language, lines: Sequence<String>): TrieDictionary {
            val dict = TrieDictionary(language)
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                if (trimmed.startsWith("B:")) {
                    val parts = trimmed.substring(2).split('\t', ' ')
                    if (parts.size >= 3) {
                        dict.insertBigram(parts[0], parts[1], parts[2].toIntOrNull() ?: 10)
                    }
                } else {
                    val parts = trimmed.split('\t', ' ')
                    val word = parts[0]
                    val freq = parts.getOrNull(1)?.toIntOrNull() ?: 100
                    dict.insertWord(word, freq)
                }
            }
            return dict
        }
    }
}
