// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.ini4j.Wini

/**
 * Live drag-to-resize editor for the "Custom Layout" top/bottom screen rectangles, in the
 * style of Citra MMJ -- drag a screen's body to move it, drag a corner to resize it, see the
 * actual game screen follow along as you go. Replaces having to type raw pixel X/Y/Width/Height
 * values into a settings form.
 */
class ScreenLayoutEditor(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var settings: Settings? = null

    // Named to avoid clashing with android.view.View's own final isInEditMode(), which is
    // a completely unrelated IDE-preview-detection method with the exact same signature --
    // reusing that name here causes an "accidental override" compile error.
    var isLayoutEditModeActive = false
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            if (value) {
                refreshFromNative()
            } else {
                activeScreen = null
                activeHandle = null
            }
            invalidate()
        }

    private data class ScreenRect(
        var rect: RectF,
        val xSetting: IntSetting,
        val ySetting: IntSetting,
        val widthSetting: IntSetting,
        val heightSetting: IntSetting,
        val label: String
    )

    private enum class Handle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private var topScreen: ScreenRect? = null
    private var bottomScreen: ScreenRect? = null

    private var activeScreen: ScreenRect? = null
    private var activeHandle: Handle? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartRect = RectF()
    private var lastLiveApplyTime = 0L

    // Real display pixels per this View's own local pixel. Drag math happens entirely in
    // View-local pixels (matching MotionEvent naturally); this scale only applies at the
    // boundary when reading from / writing to the native layout, since the Custom Layout
    // settings are calibrated against the full physical display resolution (getRealMetrics),
    // which can differ from this View's measured size (e.g. system bar/inset exclusion).
    private var scaleX = 1f
    private var scaleY = 1f

    private val handleRadiusPx = resources.displayMetrics.density * 16f
    private val strokeWidthPx = resources.displayMetrics.density * 2f

    private val topPaint = Paint().apply {
        color = Color.parseColor("#4FC3F7")
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        isAntiAlias = true
    }
    private val bottomPaint = Paint().apply {
        color = Color.parseColor("#81C784")
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        isAntiAlias = true
    }
    private val handleFillPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(6f, 0f, 1f, Color.BLACK)
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = resources.displayMetrics.density * 14f
        isFakeBoldText = true
        isAntiAlias = true
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    init {
        setWillNotDraw(false)
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScale()
        // The View's real size (and therefore the scale) is only known once this fires, so
        // if edit mode was already turned on before layout finished, redo the native read
        // now that rects can actually be converted to view-local pixels correctly.
        if (isLayoutEditModeActive) refreshFromNative()
    }

    private fun updateScale() {
        if (width <= 0 || height <= 0) return
        val dm = DisplayMetrics()
        (context as? Activity)?.windowManager?.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return
        // Match the real display's long/short axis to this view's long/short axis,
        // regardless of how the display itself is currently rotated.
        val viewIsLandscape = width >= height
        val realLong = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val realShort = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val (realW, realH) = if (viewIsLandscape) realLong to realShort else realShort to realLong
        scaleX = realW / width.toFloat()
        scaleY = realH / height.toFloat()
    }

    // Reads the current on-screen top/bottom rects straight from the native renderer (the
    // exact pixel positions the game is drawn at right now, in real display pixels), converts
    // them into this view's own local pixel space, and picks the Landscape or Portrait
    // setting keys depending on which orientation is currently active, so dragging always
    // edits whichever layout is actually in effect.
    fun refreshFromNative() {
        val layout = NativeLibrary.getScreenLayout()
        if (layout == null || layout.size != 8) return
        updateScale()
        val portrait = NativeLibrary.isPortraitMode()

        topScreen = ScreenRect(
            RectF(
                layout[0].toFloat() / scaleX,
                layout[1].toFloat() / scaleY,
                layout[2].toFloat() / scaleX,
                layout[3].toFloat() / scaleY
            ),
            if (portrait) IntSetting.PORTRAIT_TOP_X else IntSetting.LANDSCAPE_TOP_X,
            if (portrait) IntSetting.PORTRAIT_TOP_Y else IntSetting.LANDSCAPE_TOP_Y,
            if (portrait) IntSetting.PORTRAIT_TOP_WIDTH else IntSetting.LANDSCAPE_TOP_WIDTH,
            if (portrait) IntSetting.PORTRAIT_TOP_HEIGHT else IntSetting.LANDSCAPE_TOP_HEIGHT,
            "Top"
        )
        bottomScreen = ScreenRect(
            RectF(
                layout[4].toFloat() / scaleX,
                layout[5].toFloat() / scaleY,
                layout[6].toFloat() / scaleX,
                layout[7].toFloat() / scaleY
            ),
            if (portrait) IntSetting.PORTRAIT_BOTTOM_X else IntSetting.LANDSCAPE_BOTTOM_X,
            if (portrait) IntSetting.PORTRAIT_BOTTOM_Y else IntSetting.LANDSCAPE_BOTTOM_Y,
            if (portrait) IntSetting.PORTRAIT_BOTTOM_WIDTH else IntSetting.LANDSCAPE_BOTTOM_WIDTH,
            if (portrait) IntSetting.PORTRAIT_BOTTOM_HEIGHT else IntSetting.LANDSCAPE_BOTTOM_HEIGHT,
            "Bottom"
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isLayoutEditModeActive) return
        topScreen?.let { drawScreenRect(canvas, it, topPaint) }
        bottomScreen?.let { drawScreenRect(canvas, it, bottomPaint) }
    }

    private fun drawScreenRect(canvas: Canvas, screen: ScreenRect, paint: Paint) {
        canvas.drawRect(screen.rect, paint)
        canvas.drawText(
            screen.label,
            screen.rect.left + strokeWidthPx * 4,
            screen.rect.top + labelPaint.textSize + strokeWidthPx * 2,
            labelPaint
        )
        drawHandle(canvas, screen.rect.left, screen.rect.top)
        drawHandle(canvas, screen.rect.right, screen.rect.top)
        drawHandle(canvas, screen.rect.left, screen.rect.bottom)
        drawHandle(canvas, screen.rect.right, screen.rect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadiusPx * 0.6f, handleFillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isLayoutEditModeActive) return false
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = findTarget(x, y) ?: return false
                activeScreen = target.first
                activeHandle = target.second
                dragStartX = x
                dragStartY = y
                dragStartRect = RectF(target.first.rect)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val screen = activeScreen ?: return false
                val handle = activeHandle ?: return false
                applyDrag(screen, handle, x - dragStartX, y - dragStartY)
                invalidate()
                maybeLiveApplyNative()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeScreen != null) {
                    applyNativeLive()
                    commitToSettings()
                }
                activeScreen = null
                activeHandle = null
                return true
            }
        }
        return false
    }

    // Corner handles take priority over a plain body-drag so screens can still be resized
    // even while their body also overlaps another screen's handle area. Bottom screen is
    // checked first since it's typically drawn smaller and on top in most layouts.
    private fun findTarget(x: Float, y: Float): Pair<ScreenRect, Handle>? {
        for (screen in listOfNotNull(bottomScreen, topScreen)) {
            if (near(x, y, screen.rect.left, screen.rect.top)) return screen to Handle.TOP_LEFT
            if (near(x, y, screen.rect.right, screen.rect.top)) return screen to Handle.TOP_RIGHT
            if (near(x, y, screen.rect.left, screen.rect.bottom)) {
                return screen to Handle.BOTTOM_LEFT
            }
            if (near(x, y, screen.rect.right, screen.rect.bottom)) {
                return screen to Handle.BOTTOM_RIGHT
            }
        }
        for (screen in listOfNotNull(bottomScreen, topScreen)) {
            if (screen.rect.contains(x, y)) return screen to Handle.MOVE
        }
        return null
    }

    private fun near(x: Float, y: Float, hx: Float, hy: Float): Boolean {
        val dx = x - hx
        val dy = y - hy
        return dx * dx + dy * dy <= handleRadiusPx * handleRadiusPx
    }

    private fun applyDrag(screen: ScreenRect, handle: Handle, dx: Float, dy: Float) {
        val r = RectF(dragStartRect)
        when (handle) {
            Handle.MOVE -> r.offset(dx, dy)
            Handle.TOP_LEFT -> {
                r.left += dx
                r.top += dy
            }

            Handle.TOP_RIGHT -> {
                r.right += dx
                r.top += dy
            }

            Handle.BOTTOM_LEFT -> {
                r.left += dx
                r.bottom += dy
            }

            Handle.BOTTOM_RIGHT -> {
                r.right += dx
                r.bottom += dy
            }
        }

        // Keep a sane minimum size so a screen can't be dragged inside-out or to nothing.
        if (r.width() < MIN_SIZE_PX) {
            when (handle) {
                Handle.TOP_LEFT, Handle.BOTTOM_LEFT -> r.left = r.right - MIN_SIZE_PX
                else -> r.right = r.left + MIN_SIZE_PX
            }
        }
        if (r.height() < MIN_SIZE_PX) {
            when (handle) {
                Handle.TOP_LEFT, Handle.TOP_RIGHT -> r.top = r.bottom - MIN_SIZE_PX
                else -> r.bottom = r.top + MIN_SIZE_PX
            }
        }

        screen.rect = r
    }

    // Throttled while dragging so this can run every frame's worth of movement without
    // spamming the JNI boundary, though the call itself is now cheap enough that throttling
    // mostly just caps it at a sane rate rather than working around real jank.
    private fun maybeLiveApplyNative() {
        val now = System.currentTimeMillis()
        if (now - lastLiveApplyTime < LIVE_APPLY_THROTTLE_MS) return
        lastLiveApplyTime = now
        applyNativeLive()
    }

    // Pushes both screens' current rects straight into the native renderer's in-memory
    // settings and asks it to recompute the framebuffer layout right now -- no file I/O, no
    // Config{} reload, no System::ApplySettings() re-running every other setting. This is
    // what actually makes the game screen follow the finger smoothly; nothing here persists
    // across a restart by itself (see commitToSettings for that).
    private fun applyNativeLive() {
        val top = topScreen ?: return
        val bottom = bottomScreen ?: return
        NativeLibrary.setCustomLayout(
            NativeLibrary.isPortraitMode(),
            (top.rect.left * scaleX).toInt(),
            (top.rect.top * scaleY).toInt(),
            (top.rect.width() * scaleX).toInt(),
            (top.rect.height() * scaleY).toInt(),
            (bottom.rect.left * scaleX).toInt(),
            (bottom.rect.top * scaleY).toInt(),
            (bottom.rect.width() * scaleX).toInt(),
            (bottom.rect.height() * scaleY).toInt()
        )
    }

    // Called once on touch-up: writes the screen that was actually being dragged to the
    // config file so the new layout survives an app restart. The live in-memory value was
    // already applied continuously during the drag via applyNativeLive(), so this does not
    // need to touch reloadSettings()/updateFramebuffer() at all -- it's pure persistence.
    private fun commitToSettings() {
        val screen = activeScreen ?: return
        if (settings == null) return
        screen.xSetting.int = (screen.rect.left * scaleX).toInt()
        screen.ySetting.int = (screen.rect.top * scaleY).toInt()
        screen.widthSetting.int = (screen.rect.width() * scaleX).toInt()
        screen.heightSetting.int = (screen.rect.height() * scaleY).toInt()
        saveFourValuesInOnePass(screen)
    }

    private fun saveFourValuesInOnePass(screen: ScreenRect) {
        try {
            val ini = SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
            val context = CitraApplication.appContext
            val inputStream = context.contentResolver.openInputStream(ini.uri)
            val writer = Wini(inputStream)
            writer.put(screen.xSetting.section, screen.xSetting.key, screen.xSetting.valueAsString)
            writer.put(screen.ySetting.section, screen.ySetting.key, screen.ySetting.valueAsString)
            writer.put(
                screen.widthSetting.section,
                screen.widthSetting.key,
                screen.widthSetting.valueAsString
            )
            writer.put(
                screen.heightSetting.section,
                screen.heightSetting.key,
                screen.heightSetting.valueAsString
            )
            inputStream?.close()
            val outputStream = context.contentResolver.openOutputStream(ini.uri, "wt")
            writer.store(outputStream)
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Exception) {
            // Best-effort: if this particular save hiccups, the value is still correct and
            // live in the renderer for this session; only a future restart would miss it.
        }
    }

    companion object {
        private const val MIN_SIZE_PX = 80f

        // The native call itself is now cheap (direct memory write + targeted framebuffer
        // refresh), so this just caps the JNI call rate to something sane rather than
        // working around real cost.
        private const val LIVE_APPLY_THROTTLE_MS = 32L
    }
}
