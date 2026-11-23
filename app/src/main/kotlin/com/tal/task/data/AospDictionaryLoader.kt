package com.tal.task.data

import android.content.Context
import com.tal.task.engine.BundledCorpus
import com.tal.task.engine.Language
import com.tal.task.engine.TrieDictionary
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the 30,000-word AOSP LatinIME PT-BR + EN-US dictionaries extracted from the S23
 * (`assets/dict/pt_br.tsv` and `en_us.tsv`), merged with bundled bigrams and local user words.
 * Cached in-process so MainActivity and TaskInputMethodService share a single instance.
 */
object AospDictionaryLoader {

    @Volatile
    private var cached: Map<Language, TrieDictionary>? = null

    fun getOrLoad(context: Context, userDictStore: UserDictionaryStore): Map<Language, TrieDictionary> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val appCtx = context.applicationContext
            val pt = BundledCorpus.createDictionary(Language.PT_BR).also {
                mergeAssetTsv(appCtx, "dict/pt_br.tsv", it)
                userDictStore.loadInto(it)
            }
            val en = BundledCorpus.createDictionary(Language.EN_US).also {
                mergeAssetTsv(appCtx, "dict/en_us.tsv", it)
                userDictStore.loadInto(it)
            }
            val map = mapOf(Language.PT_BR to pt, Language.EN_US to en)
            cached = map
            return map
        }
    }

    private fun mergeAssetTsv(context: Context, assetPath: String, target: TrieDictionary) {
        try {
            context.assets.open(assetPath).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            val word = line.substring(0, tab)
                            val freq = line.substring(tab + 1).toIntOrNull() ?: 100
                            target.insertWord(word, freq)
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Exception) {
            // BundledCorpus remains available as fallback
        }
    }
}
