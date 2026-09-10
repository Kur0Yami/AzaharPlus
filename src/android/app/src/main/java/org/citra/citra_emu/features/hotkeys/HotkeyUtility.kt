// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.hotkeys

import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.display.ScreenAdjustmentUtil
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.model.view.InputBindingSetting
import org.citra.citra_emu.utils.ComboHelper
import org.citra.citra_emu.utils.EmulationLifecycleUtil
import org.citra.citra_emu.utils.TurboHelper

class HotkeyUtility(
    private val screenAdjustmentUtil: ScreenAdjustmentUtil,
    private val context: Context
) {

    /**
     * Describes one independent "hold modifier, tap trigger" combo slot. The trigger only
     * actually fires (and takes over the physical key it's bound to) while its modifier is
     * currently held, so the same physical key keeps working normally the rest of the time.
     */
    private data class ComboSlot(
        val triggerButton: Int,
        val modifierButton: Int,
        val slotNumber: Int
    )

    private val comboSlots = listOf(
        ComboSlot(Hotkey.COMBO_BUTTON.button, Hotkey.COMBO_MODIFIER.button, 1),
        ComboSlot(Hotkey.COMBO_BUTTON_2.button, Hotkey.COMBO_MODIFIER_2.button, 2),
        ComboSlot(Hotkey.COMBO_BUTTON_3.button, Hotkey.COMBO_MODIFIER_3.button, 3),
        ComboSlot(Hotkey.COMBO_BUTTON_4.button, Hotkey.COMBO_MODIFIER_4.button, 4)
    )
    private val comboTriggerButtons = comboSlots.map { it.triggerButton }.toSet()

    private val hotkeyButtons = Hotkey.entries.map { it.button }
    private var hotkeyIsEnabled = false
    var hotkeyIsPressed = false
    private val currentlyPressedButtons = mutableSetOf<Int>()

    // Per-slot: true while that slot's modifier physical key is currently held.
    private val comboModifierHeld = mutableMapOf<Int, Boolean>()

    // Per-slot: the host key id (see InputBindingSetting.translateEventToKeyId) of the
    // physical key that most recently fired that slot's combo, so release can be matched
    // even if the modifier was released before the trigger key.
    private val comboFiredForKeyId = mutableMapOf<Int, Int?>()

    fun handleKeyPress(keyEvent: KeyEvent): Boolean {
        var handled = false
        val buttonSet = InputBindingSetting.getButtonSet(keyEvent)
        val enableButton =
            PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
                .getString(Settings.HOTKEY_ENABLE, "")
        val thisKeyIsEnableButton = buttonSet.contains(Hotkey.ENABLE.button)
        hotkeyIsEnabled = hotkeyIsEnabled || enableButton == "" || thisKeyIsEnableButton

        for (slot in comboSlots) {
            if (buttonSet.contains(slot.modifierButton)) {
                comboModifierHeld[slot.triggerButton] = true
            }
        }

        // A hotkey bound to this physical key only actually "wins" (and suppresses the
        // key's normal button function) if it will really fire. Combo triggers are
        // special: they only fire while their own modifier is currently held, so the same
        // physical key can keep working normally when the modifier isn't held.
        val firingHotkeys = buttonSet.filter { hotkeyButtons.contains(it) }.filter { btn ->
            if (btn in comboTriggerButtons) {
                comboModifierHeld[btn] == true
            } else {
                true
            }
        }
        val thisKeyIsHotkey = !thisKeyIsEnableButton && firingHotkeys.isNotEmpty()

        if (firingHotkeys.any { it in comboTriggerButtons }) {
            val keyId = InputBindingSetting.translateEventToKeyId(keyEvent)
            for (slot in comboSlots) {
                if (firingHotkeys.contains(slot.triggerButton)) {
                    comboFiredForKeyId[slot.triggerButton] = keyId
import org.citra.citra_emu.utils.ComboHelper
import org.citra.citra_emu.utils.EmulationLifecycleUtil
import org.citra.citra_emu.utils.TurboHelper

class HotkeyUtility(
    private val screenAdjustmentUtil: ScreenAdjustmentUtil,
    private val context: Context
) {

    private val hotkeyButtons = Hotkey.entries.map { it.button }
    private var hotkeyIsEnabled = false
    var hotkeyIsPressed = false
    private val currentlyPressedButtons = mutableSetOf<Int>()

    fun handleKeyPress(keyEvent: KeyEvent): Boolean {
        var handled = false
        val buttonSet = InputBindingSetting.getButtonSet(keyEvent)
        val enableButton =
            PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
                .getString(Settings.HOTKEY_ENABLE, "")
        val thisKeyIsEnableButton = buttonSet.contains(Hotkey.ENABLE.button)
        val thisKeyIsHotkey =
            !thisKeyIsEnableButton && Hotkey.entries.any { buttonSet.contains(it.button) }
        hotkeyIsEnabled = hotkeyIsEnabled || enableButton == "" || thisKeyIsEnableButton

        // Now process all internal buttons associated with this keypress
        for (button in buttonSet) {
            currentlyPressedButtons.add(button)
            // option 1 - this is the enable command, which was already handled
            if (button == Hotkey.ENABLE.button) {
                handled = true
            }
            // option 2 - this is a different hotkey command
            else if (hotkeyButtons.contains(button)) {
                if (hotkeyIsEnabled) {
                    handled = handleHotkey(button) || handled
                }
            }
            // option 3 - this is a normal key
            else {
                // if this key press is ALSO associated with a hotkey that will process, skip
                // the normal key event.
                if (!thisKeyIsHotkey || !hotkeyIsEnabled) {
                    handled = NativeLibrary.onGamePadEvent(
                        keyEvent.device.descriptor,
                        button,
                        NativeLibrary.ButtonState.PRESSED
                    ) || handled
                }
            }
        }
        return handled
    }

    fun handleKeyRelease(keyEvent: KeyEvent): Boolean {
        var handled = false
        val buttonSet = InputBindingSetting.getButtonSet(keyEvent)
        val thisKeyIsEnableButton = buttonSet.contains(Hotkey.ENABLE.button)
        val thisKeyIsComboButton = buttonSet.contains(Hotkey.COMBO_BUTTON.button)
        val thisKeyIsHotkey =
            !thisKeyIsEnableButton && Hotkey.entries.any { buttonSet.contains(it.button) }
        if (thisKeyIsEnableButton) {
            handled = true
            hotkeyIsEnabled = false
        }

        for (slot in comboSlots) {
            if (buttonSet.contains(slot.modifierButton)) {
                comboModifierHeld[slot.triggerButton] = false
            }
        }

        // Only treat this key as having fired a slot's combo if IT was the key that fired
        // it at press time (guards against the modifier being released before the trigger).
        val firedSlotsThisKey = comboSlots.filter {
            buttonSet.contains(it.triggerButton) && comboFiredForKeyId[it.triggerButton] == keyId
        }
        for (slot in firedSlotsThisKey) {
            ComboHelper.comboActivate(NativeLibrary.ButtonState.RELEASED, slot.slotNumber)
            comboFiredForKeyId[slot.triggerButton] = null
            handled = true
        }

        // Mirrors the "firingHotkeys" gating done in handleKeyPress, so the normal button
        // release is only suppressed when a hotkey actually fired for this physical key.
        val nonComboHotkeyOnKey = Hotkey.entries.any {
            it.button !in comboTriggerButtons && buttonSet.contains(it.button)
        }
        val thisKeyIsHotkey =
            !thisKeyIsEnableButton && (nonComboHotkeyOnKey || firedSlotsThisKey.isNotEmpty())        if (thisKeyIsComboButton) {
            ComboHelper.comboActivate(NativeLibrary.ButtonState.RELEASED)
            handled = true
        }

        for (button in buttonSet) {
            // this is a hotkey button
            if (hotkeyButtons.contains(button)) {
                currentlyPressedButtons.remove(button)
                if (!currentlyPressedButtons.any { hotkeyButtons.contains(it) }) {
                    // all hotkeys are no longer pressed
                    hotkeyIsPressed = false
                }
            } else {
                // if this key ALSO sends a hotkey command that we already/will handle,
                // or if we did not register the press of this button, e.g. if this key
                // was also a hotkey pressed after enable, but released after enable button release, then
                // skip the normal key event
                if ((!thisKeyIsHotkey || !hotkeyIsEnabled) && currentlyPressedButtons.contains(
                        button
                    )
                ) {
                    handled = NativeLibrary.onGamePadEvent(
                        keyEvent.device.descriptor,
                        button,
                        NativeLibrary.ButtonState.RELEASED
                    ) || handled
                    currentlyPressedButtons.remove(button)
                }
            }
        }
        return handled
    }

    fun handleHotkey(bindedButton: Int): Boolean {
        when (bindedButton) {
            Hotkey.SWAP_SCREEN.button -> screenAdjustmentUtil.swapScreen(false)

            Hotkey.CYCLE_LAYOUT.button -> screenAdjustmentUtil.cycleLayouts()

            Hotkey.CLOSE_GAME.button -> EmulationLifecycleUtil.closeGame()

            Hotkey.PAUSE_OR_RESUME.button -> EmulationLifecycleUtil.pauseOrResume()

            Hotkey.TURBO_LIMIT.button -> TurboHelper.toggleTurbo(true)

            Hotkey.QUICKSAVE.button -> {
                NativeLibrary.saveState(NativeLibrary.QUICKSAVE_SLOT)
                Toast.makeText(
                    context,
                    context.getString(R.string.saving),
                    Toast.LENGTH_SHORT
                ).show()
            }

            Hotkey.QUICKLOAD.button -> {
                val wasLoaded = NativeLibrary.loadStateIfAvailable(NativeLibrary.QUICKSAVE_SLOT)
                val stringRes = if (wasLoaded) {
                    R.string.loading
                } else {
                    R.string.quickload_not_found
                }
                Toast.makeText(
                    context,
                    context.getString(stringRes),
                    Toast.LENGTH_SHORT
                ).show()
            }

            Hotkey.COMBO_BUTTON.button -> {
                ComboHelper.comboActivate(NativeLibrary.ButtonState.PRESSED, 1)
            }

            Hotkey.COMBO_BUTTON_2.button -> {
                ComboHelper.comboActivate(NativeLibrary.ButtonState.PRESSED, 2)
            }

            Hotkey.COMBO_BUTTON_3.button -> {
                ComboHelper.comboActivate(NativeLibrary.ButtonState.PRESSED, 3)
            }

            Hotkey.COMBO_BUTTON_4.button -> {
                ComboHelper.comboActivate(NativeLibrary.ButtonState.PRESSED, 4)
            Hotkey.COMBO_BUTTON.button -> {
                ComboHelper.comboActivate(NativeLibrary.ButtonState.PRESSED)
            }

            else -> {}
        }
        hotkeyIsPressed = true
        return true
    }
}
