package net.hdrezka.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Контейнер с «мышиным» курсором для пульта: стрелки двигают курсор,
 * OK — клик (касание) в точке курсора, у краёв экрана страница прокручивается.
 */
class CursorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Вызывается, когда курсор упирается в край: dx/dy — сколько прокрутить. */
    var onEdgeScroll: ((dx: Int, dy: Int) -> Unit)? = null

    var cursorEnabled = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val radius = 12f * density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 106, 0) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private var cx = -1f
    private var cy = -1f
    private var dirX = 0
    private var dirY = 0
    private var moveStart = 0L
    private var lastFrame = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (dirX == 0 && dirY == 0) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(1, 50) / 1000f
            lastFrame = now
            // Скорость растёт при удержании кнопки: от 250 до 1400 dp/с
            val held = (now - moveStart) / 1000f
            val speed = min(250f + held * 900f, 1400f) * density
            move(dirX * speed * dt, dirY * speed * dt)
            postOnAnimation(this)
        }
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cx < 0) {
            cx = w / 2f
            cy = h / 2f
        } else {
            cx = cx.coerceIn(0f, w.toFloat())
            cy = cy.coerceIn(0f, h.toFloat())
        }
    }

    private fun move(dx: Float, dy: Float) {
        val nx = cx + dx
        val ny = cy + dy
        val maxX = width - 1f
        val maxY = height - 1f
        var scrollX = 0
        var scrollY = 0
        if (nx < 0f) scrollX = nx.toInt() else if (nx > maxX) scrollX = (nx - maxX).toInt()
        if (ny < 0f) scrollY = ny.toInt() else if (ny > maxY) scrollY = (ny - maxY).toInt()
        cx = nx.coerceIn(0f, maxX)
        cy = ny.coerceIn(0f, maxY)
        if (scrollX != 0 || scrollY != 0) onEdgeScroll?.invoke(scrollX, scrollY)
        invalidate()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cursorEnabled) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> setDir(down, -1, 0, event)
            KeyEvent.KEYCODE_DPAD_RIGHT -> setDir(down, 1, 0, event)
            KeyEvent.KEYCODE_DPAD_UP -> setDir(down, 0, -1, event)
            KeyEvent.KEYCODE_DPAD_DOWN -> setDir(down, 0, 1, event)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.repeatCount == 0) click()
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun setDir(down: Boolean, x: Int, y: Int, event: KeyEvent) {
        if (down) {
            if (event.repeatCount > 0) return
            if (x != 0) dirX = x
            if (y != 0) dirY = y
            // Короткое нажатие — небольшой точный шаг
            move(x * 8f * density, y * 8f * density)
            val now = SystemClock.uptimeMillis()
            moveStart = now
            lastFrame = now
            removeCallbacks(ticker)
            postDelayed({ if (dirX != 0 || dirY != 0) { lastFrame = SystemClock.uptimeMillis(); postOnAnimation(ticker) } }, 150)
        } else {
            if (x != 0 && dirX == x) dirX = 0
            if (y != 0 && dirY == y) dirY = 0
            if (dirX == 0 && dirY == 0) removeCallbacks(ticker)
        }
    }

    private fun click() {
        val t = SystemClock.uptimeMillis()
        val downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
        val upEv = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, cx, cy, 0)
        downEv.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        upEv.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        super.dispatchTouchEvent(downEv)
        super.dispatchTouchEvent(upEv)
        downEv.recycle()
        upEv.recycle()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (cursorEnabled && cx >= 0) {
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)
        }
    }
}
