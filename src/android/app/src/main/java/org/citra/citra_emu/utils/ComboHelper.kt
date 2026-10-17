// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.settings.model.IntListSetting

object ComboHelper {
    /**
     * Fires (or releases) the button list configured for the given combo slot.
     * @param slot 1-5, matching Combo Button 1-5 in the Hotkeys settings.
     */
    fun comboActivate(buttonStatus: Int, slot: Int = 1) {
        val comboArray = when (slot) {
            1 -> IntListSetting.COMBO_BUTTON_BUTTONS.list
            2 -> IntListSetting.COMBO_BUTTON_BUTTONS_2.list
            3 -> IntListSetting.COMBO_BUTTON_BUTTONS_3.list
            4 -> IntListSetting.COMBO_BUTTON_BUTTONS_4.list
            5 -> IntListSetting.COMBO_BUTTON_BUTTONS_5.list
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

    /**
     * Fires (or releases) the button list configured for the given Macro step.
     * Independent from comboActivate/Combo Button slots above -- Macro has its own
     * 5 slots so configuring it doesn't consume a Combo Button slot.
     * @param slot 1-5, matching Macro Button 1-5 in the Hotkeys settings.
     */
    fun macroActivate(buttonStatus: Int, slot: Int = 1) {
        val macroArray = when (slot) {
            1 -> IntListSetting.MACRO_BUTTON_BUTTONS.list
            2 -> IntListSetting.MACRO_BUTTON_BUTTONS_2.list
            3 -> IntListSetting.MACRO_BUTTON_BUTTONS_3.list
            4 -> IntListSetting.MACRO_BUTTON_BUTTONS_4.list
            5 -> IntListSetting.MACRO_BUTTON_BUTTONS_5.list
            else -> emptyList()
        }
        for (nativeButton in macroArray) {
            if (nativeButton == -1) {
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

    /** Macro list for a slot, or empty if the slot is out of range. */
    fun macroListFor(slot: Int): List<Int> = when (slot) {
        1 -> IntListSetting.MACRO_BUTTON_BUTTONS.list
        2 -> IntListSetting.MACRO_BUTTON_BUTTONS_2.list
        3 -> IntListSetting.MACRO_BUTTON_BUTTONS_3.list
        4 -> IntListSetting.MACRO_BUTTON_BUTTONS_4.list
        5 -> IntListSetting.MACRO_BUTTON_BUTTONS_5.list
        else -> emptyList()
    }

    /** Splits a macro list into steps. A -1 value separates steps. A list without -1 is one step. */
    fun parseSteps(list: List<Int>): List<List<Int>> {
        val steps = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (value in list) {
            if (value == -1) {
                if (current.isNotEmpty()) {
                    steps.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(value)
            }
        }
        if (current.isNotEmpty()) steps.add(current)
        return steps
    }

    /** Joins steps back into one list, with -1 between steps. */
    fun flattenSteps(steps: List<List<Int>>): List<Int> {
        val out = mutableListOf<Int>()
        steps.forEachIndexed { i, step ->
            if (i > 0) out.add(-1)
            out.addAll(step)
        }
        return out
    }

    fun macroSteps(slot: Int): List<List<Int>> = parseSteps(macroListFor(slot))

    /** Presses or releases every button in one step of a macro. */
    fun macroStepActivate(buttonStatus: Int, slot: Int, step: Int) {
        val buttons = macroSteps(slot).getOrNull(step) ?: return
        for (nativeButton in buttons) {
            NativeLibrary.onGamePadEvent(NativeLibrary.TOUCHSCREEN_DEVICE, nativeButton, buttonStatus)
        }
    }
}
