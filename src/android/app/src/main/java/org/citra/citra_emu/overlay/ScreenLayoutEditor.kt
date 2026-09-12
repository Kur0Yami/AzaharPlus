// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile

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

    // Reads the current on-screen top/bottom rects straight from the native renderer (the
    // exact pixel positions the game is drawn at right now) and picks the Landscape or
    // Portrait setting keys depending on which orientation is currently active, so dragging
    // always edits whichever layout is actually in effect.
    fun refreshFromNative() {
        val layout = NativeLibrary.getScreenLayout()
        if (layout == null || layout.size != 8) return
        val portrait = NativeLibrary.isPortraitMode()

        topScreen = ScreenRect(
            RectF(
                layout[0].toFloat(),
                layout[1].toFloat(),
                layout[2].toFloat(),
                layout[3].toFloat()
            ),
            if (portrait) IntSetting.PORTRAIT_TOP_X else IntSetting.LANDSCAPE_TOP_X,
            if (portrait) IntSetting.PORTRAIT_TOP_Y else IntSetting.LANDSCAPE_TOP_Y,
            if (portrait) IntSetting.PORTRAIT_TOP_WIDTH else IntSetting.LANDSCAPE_TOP_WIDTH,
            if (portrait) IntSetting.PORTRAIT_TOP_HEIGHT else IntSetting.LANDSCAPE_TOP_HEIGHT,
            "Top"
        )
        bottomScreen = ScreenRect(
            RectF(
                layout[4].toFloat(),
                layout[5].toFloat(),
                layout[6].toFloat(),
                layout[7].toFloat()
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
                maybeLiveApply(screen)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeScreen?.let { applyToSettings(it) }
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

    // Applies + reloads at a throttled rate while dragging, so the actual game screen visibly
    // follows the finger (like MMJ) without saving to disk and reloading native settings on
    // every single touch-move frame.
    private fun maybeLiveApply(screen: ScreenRect) {
        val now = System.currentTimeMillis()
        if (now - lastLiveApplyTime < LIVE_APPLY_THROTTLE_MS) return
        lastLiveApplyTime = now
        applyToSettings(screen)
    }

    private fun applyToSettings(screen: ScreenRect) {
        val s = settings ?: return
        screen.xSetting.int = screen.rect.left.toInt()
        screen.ySetting.int = screen.rect.top.toInt()
        screen.widthSetting.int = screen.rect.width().toInt()
        screen.heightSetting.int = screen.rect.height().toInt()
        s.saveSetting(screen.xSetting, SettingsFile.FILE_NAME_CONFIG)
        s.saveSetting(screen.ySetting, SettingsFile.FILE_NAME_CONFIG)
        s.saveSetting(screen.widthSetting, SettingsFile.FILE_NAME_CONFIG)
        s.saveSetting(screen.heightSetting, SettingsFile.FILE_NAME_CONFIG)
        NativeLibrary.reloadSettings()
    }

    companion object {
        private const val MIN_SIZE_PX = 80f
        private const val LIVE_APPLY_THROTTLE_MS = 80L
    }
}
