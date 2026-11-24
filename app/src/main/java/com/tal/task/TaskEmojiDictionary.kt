// SPDX-License-Identifier: GPL-3.0-only
package com.tal.task

import android.content.Context
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.makedict.WeightedString
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Clean-room offline bilingual (PT-BR + EN-US) emoji & kaomoji keyword dictionary.
 *
 * Modes:
 * - exactMatchOnly = true: attached to main dict collection so typing a complete keyword (>= 2 chars)
 *   like "fogo" or "heart" suggests the emoji safely in slot 3 using KIND_PREDICTION (score 400_000,
 *   never auto-replacing typed words on Space).
 * - exactMatchOnly = false: powers EmojiSearchActivity & inline `:emoji` search with empty-query
 *   popular defaults, prefix/substring ranking, and offline PT-BR descriptions.
 */
class TaskEmojiDictionary private constructor(
    locale: Locale,
    private val exactMatchOnly: Boolean,
    private val index: EmojiKeywordIndex
) : Dictionary(TYPE_EMOJI, locale) {

    constructor(context: Context, locale: Locale, exactMatchOnly: Boolean) : this(
        locale = locale,
        exactMatchOnly = exactMatchOnly,
        index = getOrLoadIndex(context)
    )

    data class EmojiEntry(
        val emoji: String,
        val label: String,
        val keywords: Array<String>
    )

    class EmojiKeywordIndex(
        val entries: List<EmojiEntry>,
        val emojiSet: Set<String>,
        val byEmoji: Map<String, EmojiEntry>
    ) {
        companion object {
            fun fromLines(lines: Sequence<String>): EmojiKeywordIndex {
                val entries = ArrayList<EmojiEntry>(420)
                val set = HashSet<String>(512)
                val byEmoji = HashMap<String, EmojiEntry>(512)
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val parts = line.split('\t')
                    if (parts.size < 3) continue
                    val emoji = parts[0].trim()
                    val label = parts[1].trim()
                    if (emoji.isEmpty()) continue
                    val kwTokens = parts[2].trim().split(' ')
                        .mapNotNull { tok ->
                            val folded = foldKeyword(tok)
                            if (folded.isNotEmpty()) folded else null
                        }
                        .distinct()
                        .toTypedArray()
                    if (kwTokens.isEmpty()) continue
                    val entry = EmojiEntry(emoji, label, kwTokens)
                    entries.add(entry)
                    set.add(emoji)
                    byEmoji.putIfAbsent(emoji, entry)
                }
                return EmojiKeywordIndex(entries, set, byEmoji)
            }
        }
    }

    override fun getSuggestions(
        composedData: ComposedData,
        ngramContext: NgramContext,
        proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion,
        sessionId: Int,
        weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray
    ): ArrayList<SuggestedWordInfo>? {
        if (composedData.mIsBatchMode) return null

        val query = foldKeyword(composedData.mTypedWord)
        if (query.isEmpty()) {
            if (exactMatchOnly) return null
            val limit = minOf(24, index.entries.size)
            val list = ArrayList<SuggestedWordInfo>(limit)
            for (i in 0 until limit) {
                val e = index.entries[i]
                list.add(
                    SuggestedWordInfo(
                        e.emoji,
                        "",
                        900_000 - i * 1_000,
                        SuggestedWordInfo.KIND_PREDICTION,
                        this,
                        SuggestedWordInfo.NOT_AN_INDEX,
                        SuggestedWordInfo.NOT_A_CONFIDENCE,
                        null
                    )
                )
            }
            return list
        }

        if (exactMatchOnly) {
            if (query.length < 2) return null
            val list = ArrayList<SuggestedWordInfo>(3)
            for (i in index.entries.indices) {
                val e = index.entries[i]
                var exact = false
                for (k in e.keywords.indices) {
                    if (e.keywords[k] == query) {
                        exact = true
                        break
                    }
                }
                if (exact) {
                    list.add(
                        SuggestedWordInfo(
                            e.emoji,
                            "",
                            400_000,
                            SuggestedWordInfo.KIND_PREDICTION,
                            this,
                            SuggestedWordInfo.NOT_AN_INDEX,
                            SuggestedWordInfo.NOT_A_CONFIDENCE,
                            null
                        )
                    )
                    if (list.size >= 3) break
                }
            }
            return if (list.isEmpty()) null else list
        }

        // Full search mode (EmojiSearchActivity & inline `:query`)
        val scored = ArrayList<Pair<EmojiEntry, Int>>(24)
        for (i in index.entries.indices) {
            val e = index.entries[i]
            var bestScore = 0
            for (k in e.keywords.indices) {
                val kw = e.keywords[k]
                val s = when {
                    kw == query -> 950_000
                    kw.startsWith(query) -> 780_000
                    query.length >= 2 && kw.contains(query) -> 580_000
                    else -> 0
                }
                if (s > bestScore) bestScore = s
                if (bestScore == 950_000) break
            }
            if (bestScore > 0) {
                scored.add(e to (bestScore - i))
            }
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.second }
        val limit = minOf(24, scored.size)
        val out = ArrayList<SuggestedWordInfo>(limit)
        for (i in 0 until limit) {
            val (entry, score) = scored[i]
            out.add(
                SuggestedWordInfo(
                    entry.emoji,
                    "",
                    score,
                    SuggestedWordInfo.KIND_PREDICTION,
                    this,
                    SuggestedWordInfo.NOT_AN_INDEX,
                    SuggestedWordInfo.NOT_A_CONFIDENCE,
                    null
                )
            )
        }
        return out
    }

    override fun isInDictionary(word: String): Boolean = index.emojiSet.contains(word)

    override fun getWordProperty(word: String, isBeginningOfSentence: Boolean): WordProperty? {
        val entry = index.byEmoji[word] ?: return null
        val targets = arrayListOf(WeightedString(entry.label, 150))
        return WordProperty(word, ProbabilityInfo(150), targets, null, false, false)
    }

    companion object {
        private const val ASSET_PATH = "dict/emoji_keywords_pt_en.tsv"
        private val loadLock = Any()
        @Volatile private var cachedIndex: EmojiKeywordIndex? = null

        private fun getOrLoadIndex(context: Context): EmojiKeywordIndex {
            cachedIndex?.let { return it }
            synchronized(loadLock) {
                cachedIndex?.let { return it }
                val loaded = try {
                    context.assets.open(ASSET_PATH).use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                            EmojiKeywordIndex.fromLines(reader.lineSequence())
                        }
                    }
                } catch (_: Exception) {
                    EmojiKeywordIndex(emptyList(), emptySet(), emptyMap())
                }
                cachedIndex = loaded
                return loaded
            }
        }

        fun createFromLinesForTest(
            locale: Locale,
            exactMatchOnly: Boolean,
            lines: Sequence<String>
        ): TaskEmojiDictionary = TaskEmojiDictionary(locale, exactMatchOnly, EmojiKeywordIndex.fromLines(lines))

        fun foldKeyword(raw: CharSequence?): String {
            if (raw.isNullOrEmpty()) return ""
            val sb = StringBuilder(raw.length)
            for (i in 0 until raw.length) {
                val fc = foldChar(raw[i])
                if (fc != '\u0000') sb.append(fc)
            }
            return sb.toString()
        }

        private fun foldChar(ch: Char): Char = when (ch) {
            in 'a'..'z', in '0'..'9' -> ch
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
}
