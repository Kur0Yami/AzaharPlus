// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.model.view

import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.features.settings.model.AbstractStringSetting
import org.citra.citra_emu.features.settings.model.Settings

object StringKeyBinding {
    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    fun setting(key: String): AbstractStringSetting = object : AbstractStringSetting {
        override var string: String
            get() = prefs().getString(key, "")!!
            set(value) {
                prefs().edit().putString(key, value).apply()
            }
        override val key = key
        override val section = Settings.SECTION_CONTROLS
        override val isRuntimeEditable = true
        override val valueAsString = prefs().getString(key, "")!!
        override val defaultValue = ""
    }

    fun binding(key: String, titleId: Int): InputBindingSetting = InputBindingSetting(setting(key), titleId)

    fun label(key: String): String = prefs().getString(key, "")!!
}
