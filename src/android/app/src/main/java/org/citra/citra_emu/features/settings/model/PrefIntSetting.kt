// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.model

import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication

/**
 * An int setting stored in the app's default SharedPreferences instead of the native config.
 * Used for per-macro repeat count and step delay, so they don't need native setting keys.
 */
class PrefIntSetting(
    override val key: String,
    override val section: String,
    override val defaultValue: Int
) : AbstractIntSetting {
    override var int: Int
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
            .getInt(key, defaultValue)
        set(value) {
            PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
                .edit().putInt(key, value).apply()
        }

    override val valueAsString: String
        get() = int.toString()

    override val isRuntimeEditable: Boolean
        get() = true
}
