// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.FileUtil.inputStream

/**
 * Loads custom on-screen control art from a "theme" folder inside the user's selected
 * Azahar user data directory (e.g. citra-emu/theme/ if that's what was picked).
 *
 * Drop png files named after each control (see [Names]) into that folder and they will
 * be picked up automatically the next time the overlay is (re)built -- no import step, no
 * picker, no settings toggle. Anything not provided there just falls back to Azahar's
 * built-in art, so a theme can supply as few or as many files as it wants.
 */
object OverlayTheme {
    private const val THEME_DIR_NAME = "theme"

    // Clean, unambiguous file base names (without extension) for every themeable control.
    // Files are looked up as "<name>.png" inside the theme folder.
    object Names {
        const val A = "a"
        const val A_PRESSED = "a_pressed"
        const val B = "b"
        const val B_PRESSED = "b_pressed"
        const val X = "x"
        const val X_PRESSED = "x_pressed"
        const val Y = "y"
        const val Y_PRESSED = "y_pressed"
        const val L = "l"
        const val L_PRESSED = "l_pressed"
        const val R = "r"
        const val R_PRESSED = "r_pressed"
        const val ZL = "zl"
        const val ZL_PRESSED = "zl_pressed"
        const val ZR = "zr"
        const val ZR_PRESSED = "zr_pressed"
        const val START = "start"
        const val START_PRESSED = "start_pressed"
        const val SELECT = "select"
        const val SELECT_PRESSED = "select_pressed"
        const val HOME = "home"
        const val HOME_PRESSED = "home_pressed"
        const val SWAP_SCREEN = "swap_screen"
        const val SWAP_SCREEN_PRESSED = "swap_screen_pressed"
        const val TURBO = "turbo"
        const val TURBO_PRESSED = "turbo_pressed"
        const val COMBO_1 = "combo1"
        const val COMBO_1_PRESSED = "combo1_pressed"
        const val COMBO_2 = "combo2"
        const val COMBO_2_PRESSED = "combo2_pressed"
        const val COMBO_3 = "combo3"
        const val COMBO_3_PRESSED = "combo3_pressed"
        const val COMBO_4 = "combo4"
        const val COMBO_4_PRESSED = "combo4_pressed"
        const val COMBO_5 = "combo5"
        const val COMBO_5_PRESSED = "combo5_pressed"
        const val DPAD = "dpad"
        const val DPAD_PRESSED_ONE = "dpad_pressed_one"
        const val DPAD_PRESSED_TWO = "dpad_pressed_two"
        const val JOYSTICK = "joystick"
        const val JOYSTICK_PRESSED = "joystick_pressed"
        const val JOYSTICK_RANGE = "joystick_range"
        const val C_STICK = "c_stick"
        const val C_STICK_PRESSED = "c_stick_pressed"
        const val C_STICK_RANGE = "c_stick_range"
        const val BG_LANDSCAPE = "bg_landscape"
        const val BG_PORTRAIT = "bg_portrait"
    }

    // Cache the resolved theme folder for the lifetime of the process; user data directory
    // doesn't change without a restart, and re-resolving it via SAF on every single button
    // lookup would be wasteful.
    private var cachedThemeDir: DocumentFile? = null
    private var cacheAttempted = false

    private fun themeDir(): DocumentFile? {
        if (cacheAttempted) return cachedThemeDir
        cacheAttempted = true
        cachedThemeDir = try {
            val userPath = DirectoryInitialization.userPath ?: return null
            val root = DocumentFile.fromTreeUri(CitraApplication.appContext, Uri.parse(userPath))
            root?.findFile(THEME_DIR_NAME)
        } catch (_: Exception) {
            null
        }
        return cachedThemeDir
    }

    /**
     * Call this if the user data directory changes at runtime (re-selected folder) so the
     * next lookup re-resolves the theme folder instead of using a stale cached reference.
     */
    fun invalidateCache() {
        cacheAttempted = false
        cachedThemeDir = null
    }

    /**
     * Returns the decoded bitmap for [name] from the theme folder, or null if the theme
     * folder, the file, or a valid image at that file doesn't exist.
     */
    fun loadBitmap(name: String): Bitmap? {
        val dir = themeDir() ?: return null
        val file = dir.findFile("$name.png") ?: return null
        return try {
            file.inputStream().use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
