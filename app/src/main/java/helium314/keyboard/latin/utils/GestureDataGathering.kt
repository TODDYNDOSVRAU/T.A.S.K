// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.view.inputmethod.EditorInfo
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.InputStyle
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SingleDictionaryFacilitator
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestionResults

/**
 * Inert no-op stub replacing legacy NLNet gesture data collection (purged in T.A.S.K. v3.2.0, D18).
 */
object BackgroundGatheringCache {
    fun addWord(word: WordData) {}
    fun removeLast(word: String) {}
    fun onPickSuggestionAfterGesturing(suggestion: SuggestedWordInfo, originalWord: String) {}
    fun onPickSuggestion(suggestion: SuggestedWordInfo, originalWord: String) {}
    fun onRejectedSuggestion(suggestion: String) {}
    fun onUndo(lastComposedWord: CharSequence) {}
    fun onEditWord(word: String) {}
    fun onEditSelection(selection: CharSequence?, before: CharSequence?, after: CharSequence?) {}

    @JvmStatic
    fun saveOrClear(context: Context) {}

    fun save(context: Context) {}
    fun clear() {}
    val isEmpty: Boolean get() = true
}

@JvmField
var useBackgroundGathering = false

fun setUseBackgroundGathering(context: Context, editorInfo: EditorInfo): Boolean {
    useBackgroundGathering = false
    return false
}

const val dictTestImeOption = ""

var gestureDataActiveFacilitator: SingleDictionaryFacilitator? = null

class WordData(
    var targetWord: String?,
    val suggestions: SuggestionResults,
    val composedData: ComposedData,
    val ngramContext: NgramContext,
    val keyboard: Keyboard,
    val inputStyle: InputStyle,
    val activeMode: Boolean,
    val topSuggestion: SuggestedWordInfo? = null
)
