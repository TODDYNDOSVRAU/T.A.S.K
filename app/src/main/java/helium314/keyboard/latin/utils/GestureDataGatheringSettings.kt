// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.common.Constants.Separators

/**
 * Inert settings stub after purging gesture data collection (T.A.S.K. v3.2.0, D18).
 */
object GestureDataGatheringSettings {
    const val PREF_BACKGROUND_GATHERING_ENABLED = "gesture_data_background_gathering_enabled"
    const val PREF_BACKGROUND_DISABLED_BEFORE_TIME_MILLIS = "gesture_data_background_gathering_disabled_before"

    fun isInActiveGatheringMode(editorInfo: EditorInfo): Boolean = false
    fun isBackgroundGatheringEnabled(prefs: SharedPreferences): Boolean = false
    fun setBackgroundGatheringEnabled(prefs: SharedPreferences, enabled: Boolean) {}
    fun tempDisableBackgroundGathering(prefs: SharedPreferences) {}
    fun isDiscardByDefault(context: Context): Boolean = true
    fun showEndNotificationIfNecessary(context: Context) {}

    fun String.filterBackgroundGatheringToolbarKeys(prefs: SharedPreferences): String =
        split(Separators.ENTRY).filter { ToolbarKey.BACKGROUND_GATHERING.name !in it }
            .joinToString(Separators.ENTRY)
}
