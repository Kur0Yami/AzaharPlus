// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.hotkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.display.ScreenAdjustmentUtil
import org.citra.citra_emu.features.settings.model.IntListSetting
import org.citra.citra_emu.features.settings.model.IntSetting
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
        val modifierKey: String,
        val slotNumber: Int
    )

    // Whether a slot requires holding its modifier is decided automatically by whether
    // that modifier has a binding at all: bind it -> "hold to fire" behavior; leave it
    // unbound -> the trigger fires standalone, immediately, on its own.
    private val comboSlots = listOf(
        ComboSlot(Hotkey.COMBO_BUTTON.button, Hotkey.COMBO_MODIFIER.button, Settings.HOTKEY_BUTTON_COMBO_MODIFIER, 1),
        ComboSlot(Hotkey.COMBO_BUTTON_2.button, Hotkey.COMBO_MODIFIER_2.button, Settings.HOTKEY_BUTTON_COMBO_MODIFIER_2, 2),
        ComboSlot(Hotkey.COMBO_BUTTON_3.button, Hotkey.COMBO_MODIFIER_3.button, Settings.HOTKEY_BUTTON_COMBO_MODIFIER_3, 3),
        ComboSlot(Hotkey.COMBO_BUTTON_4.button, Hotkey.COMBO_MODIFIER_4.button, Settings.HOTKEY_BUTTON_COMBO_MODIFIER_4, 4),
        ComboSlot(Hotkey.COMBO_BUTTON_5.button, Hotkey.COMBO_MODIFIER_5.button, Settings.HOTKEY_BUTTON_COMBO_MODIFIER_5, 5),
        // Combo Chain reuses the same modifier-hold/standalone gating and release-matching
        // as the 5 fixed slots, but which slot it actually fires is decided dynamically at
        // press time (see handleHotkey) -- CHAIN_SLOT_MARKER is a placeholder, never used
        // directly to fire a combo.
        ComboSlot(
            Hotkey.COMBO_CHAIN.button,
            Hotkey.COMBO_CHAIN_MODIFIER.button,
            Settings.HOTKEY_BUTTON_COMBO_CHAIN_MODIFIER,
            CHAIN_SLOT_MARKER
        ),
        // A second, independent physical trigger for the exact same chain state as
        // COMBO_CHAIN above -- lets a sequence be driven by different buttons per step
        // (e.g. ZR fires step 1, then X fires step 2) instead of re-pressing one button.
        ComboSlot(
            Hotkey.COMBO_CHAIN_CONTINUE.button,
            Hotkey.COMBO_CHAIN_CONTINUE_MODIFIER.button,
            Settings.HOTKEY_BUTTON_COMBO_CHAIN_CONTINUE_MODIFIER,
            CHAIN_SLOT_MARKER
        ),
        // Macro reuses the same modifier-hold/standalone gating too, purely so binding and
        // suppression behave consistently with every other combo-family trigger. Its
        // release is a deliberate no-op (see handleKeyRelease) -- the macro's own timer
        // owns pressing and releasing each step, independent of when the trigger key itself
        // is physically released.
        ComboSlot(
            Hotkey.MACRO_TRIGGER.button,
            Hotkey.MACRO_MODIFIER.button,
            Settings.HOTKEY_BUTTON_MACRO_MODIFIER,
            MACRO_SLOT_MARKER
        ),
        ComboSlot(Hotkey.MACRO_TRIGGER_2.button, Hotkey.MACRO_MODIFIER.button, Settings.HOTKEY_BUTTON_MACRO_MODIFIER, MACRO_SLOT_MARKER),
        ComboSlot(Hotkey.MACRO_TRIGGER_3.button, Hotkey.MACRO_MODIFIER.button, Settings.HOTKEY_BUTTON_MACRO_MODIFIER, MACRO_SLOT_MARKER),
        ComboSlot(Hotkey.MACRO_TRIGGER_4.button, Hotkey.MACRO_MODIFIER.button, Settings.HOTKEY_BUTTON_MACRO_MODIFIER, MACRO_SLOT_MARKER),
        ComboSlot(Hotkey.MACRO_TRIGGER_5.button, Hotkey.MACRO_MODIFIER.button, Settings.HOTKEY_BUTTON_MACRO_MODIFIER, MACRO_SLOT_MARKER)
    )
    private val comboTriggerButtons = comboSlots.map { it.triggerButton }.toSet()

    // Combo Chain runtime state: cycles through combo slots 1-5 (reusing their existing
    // button lists) on each successive press, so a single trigger can step through a
    // sequence like Guard (slot 1) -> Counter (slot 2) without needing a separate button
    // per step. Leave the slots after the ones you use empty; firing an empty slot is a
    // harmless no-op, which is what makes the chain "length" implicitly customizable.
    private var chainNextIndex = 0
    private var chainLastFireTime = 0L
    private var chainFiredSlotNumber: Int? = null

    // Macro runtime state: five independent macros (index 1..5). Each plays its own Macro Button
    // list, for its own repeat count, waiting its own step delay between press and release.
    private val macroHandler = Handler(Looper.getMainLooper())
    private val macroRunning = BooleanArray(6)
    private val macroToken = IntArray(6)
    private val macroPressed = BooleanArray(6)
    private val macroCurrentStep = IntArray(6)

    private val hotkeyButtons = Hotkey.entries.map { it.button }

    // Modifiers never count as a hotkey firing, so the game still gets their normal input.
    private val modifierButtons: Set<Int> = setOf(
        Hotkey.COMBO_MODIFIER.button, Hotkey.COMBO_MODIFIER_2.button, Hotkey.COMBO_MODIFIER_3.button,
        Hotkey.COMBO_MODIFIER_4.button, Hotkey.COMBO_MODIFIER_5.button,
        Hotkey.COMBO_CHAIN_MODIFIER.button, Hotkey.COMBO_CHAIN_CONTINUE_MODIFIER.button,
        Hotkey.MACRO_MODIFIER.button, Hotkey.MISC_MODIFIER.button
    )

    // Misc actions: a 3DS button chosen in Gamepad settings (prefs "misc_action_<name>").
    // While Misc Modifier is held, pressing that button runs the action instead of sending it to the game.
    private val miscActions = listOf(
        "swap_screen" to Hotkey.SWAP_SCREEN.button,
        "cycle_layout" to Hotkey.CYCLE_LAYOUT.button,
        "close_game" to Hotkey.CLOSE_GAME.button,
        "pause" to Hotkey.PAUSE_OR_RESUME.button,
        "quicksave" to Hotkey.QUICKSAVE.button,
        "quickload" to Hotkey.QUICKLOAD.button,
        "turbo" to Hotkey.TURBO_LIMIT.button
    )
    private var miscModifierHeld = false
    private val miscConsumed = mutableSetOf<Int>()

    private fun miscActionFor(gameButton: Int): Int? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        return miscActions.firstOrNull { (name, _) ->
            prefs.getInt("misc_action_$name", -1) == gameButton
        }?.second
    }
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        // Hotkey Enable is no longer in the UI. Hotkeys are always enabled.
        val enableButton = ""
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
            val slot = comboSlots.find { it.triggerButton == btn }
            if (slot != null) {
                // Standalone: this slot's modifier has no binding, so the trigger fires
                // immediately on its own. Otherwise it only fires while held.
                val modifierIsBound = !prefs.getString(slot.modifierKey, "").isNullOrEmpty()
                !modifierIsBound || comboModifierHeld[btn] == true
            } else {
                true
            }
        }
        val thisKeyIsHotkey = !thisKeyIsEnableButton && firingHotkeys.any { it !in modifierButtons }

        if (firingHotkeys.any { it in comboTriggerButtons }) {
            val keyId = InputBindingSetting.translateEventToKeyId(keyEvent)
            for (slot in comboSlots) {
                if (firingHotkeys.contains(slot.triggerButton)) {
                    comboFiredForKeyId[slot.triggerButton] = keyId
                }
            }
        }

        if (buttonSet.contains(Hotkey.MISC_MODIFIER.button)) miscModifierHeld = true
        val miscNow = if (miscModifierHeld) buttonSet.filter { miscActionFor(it) != null } else emptyList()
        for (button in miscNow) {
            val action = miscActionFor(button) ?: continue
            handled = handleHotkey(action) || handled
            miscConsumed.add(button)
        }

        // Now process all internal buttons associated with this keypress
        for (button in buttonSet) {
            currentlyPressedButtons.add(button)
            // option 1 - this is the enable command, which was already handled
            if (button == Hotkey.ENABLE.button) {
                handled = true
            }
            // option 2 - this is a different hotkey command
            else if (hotkeyButtons.contains(button)) {
                if (hotkeyIsEnabled && firingHotkeys.contains(button)) {
                    handled = handleHotkey(button) || handled
                }
            }
            // option 3 - this is a normal key
            else {
                // if this key press is ALSO associated with a hotkey that will process, skip
                // the normal key event.
                if (button !in miscNow && (!thisKeyIsHotkey || !hotkeyIsEnabled)) {
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
        val keyId = InputBindingSetting.translateEventToKeyId(keyEvent)

        val thisKeyIsComboButton = buttonSet.contains(Hotkey.COMBO_BUTTON.button)
        val thisKeyIsHotkey =
            !thisKeyIsEnableButton && Hotkey.entries.any { buttonSet.contains(it.button) }
        if (thisKeyIsEnableButton) {
            handled = true
            hotkeyIsEnabled = false
        }

        if (buttonSet.contains(Hotkey.MISC_MODIFIER.button)) miscModifierHeld = false

        for (slot in comboSlots) {
            if (buttonSet.contains(slot.modifierButton)) {
                comboModifierHeld[slot.triggerButton] = false
            }
        if (thisKeyIsComboButton) {
            ComboHelper.comboActivate(NativeLibrary.ButtonState.RELEASED)
            handled = true
        }

        // Only treat this key as having fired a slot's combo if IT was the key that fired
        // it at press time (guards against the modifier being released before the trigger).
        val firedSlotsThisKey = comboSlots.filter {
            buttonSet.contains(it.triggerButton) && comboFiredForKeyId[it.triggerButton] == keyId
        }
        for (slot in firedSlotsThisKey) {
            if (slot.slotNumber == MACRO_SLOT_MARKER) {
                // Deliberate no-op: the macro's own timer presses and releases each step on
                // its own schedule, regardless of when the trigger key is physically let go.
            } else {
                val slotNumberToRelease = if (slot.slotNumber == CHAIN_SLOT_MARKER) {
                    chainFiredSlotNumber
                } else {
                    slot.slotNumber
                }
                if (slotNumberToRelease != null) {
                    ComboHelper.comboActivate(
                        NativeLibrary.ButtonState.RELEASED,
                        slotNumberToRelease
                    )
                }
                if (slot.slotNumber == CHAIN_SLOT_MARKER) {
                    chainFiredSlotNumber = null
                }
            }
            comboFiredForKeyId[slot.triggerButton] = null
            handled = true
        }

        // Mirrors the "firingHotkeys" gating done in handleKeyPress, so the normal button
        // release is only suppressed when a hotkey actually fired for this physical key.
        val nonComboHotkeyOnKey = Hotkey.entries.any {
            it.button !in comboTriggerButtons && it.button !in modifierButtons && buttonSet.contains(it.button)
        }
        val thisKeyIsHotkey =
            !thisKeyIsEnableButton && (nonComboHotkeyOnKey || firedSlotsThisKey.isNotEmpty())

        for (button in buttonSet) {
            // this is a hotkey button
            if (hotkeyButtons.contains(button)) {
                currentlyPressedButtons.remove(button)
                if (!currentlyPressedButtons.any { hotkeyButtons.contains(it) }) {
                    // all hotkeys are no longer pressed
                    hotkeyIsPressed = false
                }
            } else if (miscConsumed.remove(button)) {
                currentlyPressedButtons.remove(button)
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
            }

            Hotkey.COMBO_BUTTON_5.button -> {
                ComboHelper.comboActivate(NativeLibrary.ButtonState.PRESSED, 5)
            }

            Hotkey.COMBO_CHAIN.button, Hotkey.COMBO_CHAIN_CONTINUE.button -> fireNextChainStep()

            Hotkey.MACRO_TRIGGER.button -> startMacro(1)
            Hotkey.MACRO_TRIGGER_2.button -> startMacro(2)
            Hotkey.MACRO_TRIGGER_3.button -> startMacro(3)
            Hotkey.MACRO_TRIGGER_4.button -> startMacro(4)
            Hotkey.MACRO_TRIGGER_5.button -> startMacro(5)

                ComboHelper.comboActivate(NativeLibrary.ButtonState.PRESSED)
            }

            else -> {}
        }
        hotkeyIsPressed = true
        return true
    }

    // Shared by both COMBO_CHAIN and COMBO_CHAIN_CONTINUE -- whichever physical button was
    // actually pressed, this advances the same underlying sequence by one step.
    private fun fireNextChainStep() {
        val now = System.currentTimeMillis()
        val timeoutMs = IntSetting.COMBO_CHAIN_TIMEOUT_MS.int.toLong()
        if (now - chainLastFireTime > timeoutMs) {
            chainNextIndex = 0
        }
        chainLastFireTime = now
        val slotNumber = chainNextIndex + 1
        chainFiredSlotNumber = slotNumber
        ComboHelper.comboActivate(NativeLibrary.ButtonState.PRESSED, slotNumber)
        chainNextIndex = (chainNextIndex + 1) % 5
    }

    private fun slotHasContent(slotNumber: Int): Boolean = when (slotNumber) {
        1 -> IntListSetting.COMBO_BUTTON_BUTTONS.list.isNotEmpty()
        2 -> IntListSetting.COMBO_BUTTON_BUTTONS_2.list.isNotEmpty()
        3 -> IntListSetting.COMBO_BUTTON_BUTTONS_3.list.isNotEmpty()
        4 -> IntListSetting.COMBO_BUTTON_BUTTONS_4.list.isNotEmpty()
        5 -> IntListSetting.COMBO_BUTTON_BUTTONS_5.list.isNotEmpty()
        else -> false
    }

    // Macro Button slots are independent from Combo Button slots above -- separate
    // settings, separate storage -- so configuring one never consumes the other's slots.
    private fun macroSlotHasContent(slotNumber: Int): Boolean = when (slotNumber) {
        1 -> IntListSetting.MACRO_BUTTON_BUTTONS.list.isNotEmpty()
        2 -> IntListSetting.MACRO_BUTTON_BUTTONS_2.list.isNotEmpty()
        3 -> IntListSetting.MACRO_BUTTON_BUTTONS_3.list.isNotEmpty()
        4 -> IntListSetting.MACRO_BUTTON_BUTTONS_4.list.isNotEmpty()
        5 -> IntListSetting.MACRO_BUTTON_BUTTONS_5.list.isNotEmpty()
        else -> false
    }

    // Public entry point for the on-screen Macro button (InputOverlay). Defaults to Macro 1.
    fun triggerMacro(macroNumber: Int = 1) {
        startMacro(macroNumber)
    }

    // Repeat count per macro. Macro 1 uses the existing global setting. Macros 2 to 5 use their
    // own saved value, default 1. 0 means repeat until the same trigger is pressed again.
    private fun macroRepeatCount(n: Int): Int =
        if (n == 1) {
            IntSetting.MACRO_REPEAT_COUNT.int
        } else {
            PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
                .getInt("macro_repeat_count_$n", 1)
        }

    // Delay between each press and release. Macro 1 uses the existing global setting. Macros 2 to 5
    // use their own saved value, default 80 ms.
    private fun macroStepDelay(n: Int): Long =
        if (n == 1) {
            IntSetting.MACRO_STEP_DELAY_MS.int.toLong()
        } else {
            PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
                .getInt("macro_step_delay_ms_$n", 80).toLong()
        }

    private fun startMacro(n: Int) {
        if (!macroSlotHasContent(n)) return
        if (macroRunning[n]) {
            // Repeat 0 runs until stopped, so pressing the same trigger again turns it off.
            if (macroRepeatCount(n) == 0) stopMacro(n)
            return
        }
        macroRunning[n] = true
        macroToken[n]++
        runMacroStep(n, macroToken[n], cycle = 0, step = 0, isPress = true)
    }

    private fun runMacroStep(n: Int, token: Int, cycle: Int, step: Int, isPress: Boolean) {
        // Callbacks left over from a stopped macro are ignored.
        if (token != macroToken[n] || !macroRunning[n]) return
        val repeat = macroRepeatCount(n)
        val stepCount = ComboHelper.macroSteps(n).size
        if (stepCount == 0 || (repeat != 0 && cycle >= repeat)) {
            macroRunning[n] = false
            return
        }
        val delay = macroStepDelay(n)
        if (isPress) {
            macroCurrentStep[n] = step
            ComboHelper.macroStepActivate(NativeLibrary.ButtonState.PRESSED, n, step)
            macroPressed[n] = true
            macroHandler.postDelayed({ runMacroStep(n, token, cycle, step, isPress = false) }, delay)
        } else {
            ComboHelper.macroStepActivate(NativeLibrary.ButtonState.RELEASED, n, step)
            macroPressed[n] = false
            val next = step + 1
            val nextCycle = if (next >= stepCount) cycle + 1 else cycle
            val nextStep = if (next >= stepCount) 0 else next
            macroHandler.postDelayed({ runMacroStep(n, token, nextCycle, nextStep, isPress = true) }, delay)
        }
    }

    // Stops one macro now and releases the step it is still holding.
    private fun stopMacro(n: Int) {
        if (macroPressed[n]) {
            ComboHelper.macroStepActivate(NativeLibrary.ButtonState.RELEASED, n, macroCurrentStep[n])
            macroPressed[n] = false
        }
        macroRunning[n] = false
        macroToken[n]++
    }

    // Call when the game is paused, goes to the background, or the menu opens.
    fun stopAllMacros() {
        for (n in 1..5) stopMacro(n)
    }

    companion object {
        private const val CHAIN_SLOT_MARKER = 0
        private const val MACRO_SLOT_MARKER = -1
    }
}
