// SPDX-License-Identifier: GPL-3.0-only
package com.tal.task

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import java.util.Locale

/**
 * Clean-room simultaneous PT-BR + EN-US multilingual resolver.
 * Never mutates PREF_ENABLED_SUBTYPES or PREF_ADDITIONAL_SUBTYPES; computes active secondary
 * locales dynamically at settings/facilitator read time.
 */
object TaskMultilingualHelper {

    @JvmStatic
    fun resolveSecondaryLocales(
        prefs: SharedPreferences,
        mainLocale: Locale,
        configured: List<Locale>
    ): List<Locale> {
        val result = LinkedHashSet<Locale>(configured.size + 1)
        for (loc in configured) {
            if (loc != mainLocale) {
                result.add(loc)
            }
        }
        val enabled = prefs.getBoolean(Settings.PREF_MULTILINGUAL_PT_EN, Defaults.PREF_MULTILINGUAL_PT_EN)
        if (enabled) {
            val mainLang = mainLocale.language.lowercase(Locale.ROOT)
            if (mainLang == "pt") {
                val hasEn = result.any { it.language.equals("en", ignoreCase = true) }
                if (!hasEn) {
                    result.add(Locale.US)
                }
            } else if (mainLang == "en") {
                val hasPt = result.any { it.language.equals("pt", ignoreCase = true) }
                if (!hasPt) {
                    result.add(Locale("pt", "BR"))
                }
            }
        }
        result.remove(mainLocale)
        return ArrayList(result)
    }

    @JvmStatic
    fun isBilingualPtEnActive(
        mainLocale: Locale?,
        secondaryLocales: Collection<Locale>?
    ): Boolean {
        var hasPt = mainLocale?.language?.equals("pt", ignoreCase = true) == true
        var hasEn = mainLocale?.language?.equals("en", ignoreCase = true) == true
        if (secondaryLocales != null) {
            for (loc in secondaryLocales) {
                if (loc.language.equals("pt", ignoreCase = true)) hasPt = true
                if (loc.language.equals("en", ignoreCase = true)) hasEn = true
                if (hasPt && hasEn) return true
            }
        }
        return hasPt && hasEn
    }
}
