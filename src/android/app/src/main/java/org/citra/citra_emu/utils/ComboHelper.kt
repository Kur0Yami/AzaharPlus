// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.settings.model.IntListSetting

object ComboHelper {
    /**
     * Fires (or releases) the button list configured for the given combo slot.
     * @param slot 1-4, matching Combo Button 1-4 in the Hotkeys settings.
     */
    fun comboActivate(buttonStatus: Int, slot: Int = 1) {
        val comboArray = when (slot) {
            1 -> IntListSetting.COMBO_BUTTON_BUTTONS.list
            2 -> IntListSetting.COMBO_BUTTON_BUTTONS_2.list
            3 -> IntListSetting.COMBO_BUTTON_BUTTONS_3.list
            4 -> IntListSetting.COMBO_BUTTON_BUTTONS_4.list
            else -> emptyList()
        }
        for (nativeButton in comboArray) {
            if (nativeButton == -1) {
                // We don't want to parse any bad inputs here so we continue loop
                continue
            } else {
                NativeLibrary.onGamePadEvent(
                    NativeLibrary.TOUCHSCREEN_DEVICE,
                    nativeButton,
                    buttonStatus
                )
            }
        }
    }
}
