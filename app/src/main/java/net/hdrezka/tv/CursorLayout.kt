package net.hdrezka.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Контейнер с «мышиным» курсором для пульта: стрелки двигают курсор,
 * OK — клик (касание) в точке курсора, у краёв экрана прокручивается
 * элемент под курсором (колесом мыши — работает и для вложенных списков).
 *
 * Фокус остаётся у дочернего WebView: клавиши перехватываются здесь,
 * в dispatchKeyEvent корневого view, поэтому поля ввода получают клавиатуру.
 */
class CursorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var cursorEnabled = true
        set(value) {
            field = value
            stopMovement()
            invalidate()
        }

    /** Временно отключить курсор (открыта панель разделов): клавиши идут в обычную навигацию. */
    var suspended = false
        set(value) {
            field = value
            stopMovement()
            okDown = false
            invalidate()
        }

    /** Видео на весь экран с контролами приложения: курсор скрыт и не перехватывает кнопки. */
    var playerActive = false
        set(value) {
            field = value
            stopMovement()
            okDown = false
            invalidate()
        }

    private val active get() = cursorEnabled && !suspended && !playerActive

    private val density = resources.displayMetrics.density
    private val radius = 12f * density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 106, 0) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    /** Сколько пикселей прокручивает одна единица AXIS_VSCROLL/AXIS_HSCROLL. */
    private val scrollFactorV: Float
    private val scrollFactorH: Float

    private var cx = -1f
    private var cy = -1f
    private var dirX = 0
    private var dirY = 0
    private var moveStart = 0L
    private var lastFrame = 0L
    private var okDown = false

    private val ticker = object : Runnable {
        override fun run() {
            if (dirX == 0 && dirY == 0) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(1, 50) / 1000f
            lastFrame = now
            // Скорость растёт при удержании кнопки: от 250 до 1400 dp/с
            val held = (now - moveStart) / 1000f
            var speed = min(250f + held * 900f, 1400f) * density
            if (dirX != 0 && dirY != 0) speed /= sqrt(2f) // по диагонали не быстрее
            move(dirX * speed * dt, dirY * speed * dt)
            postOnAnimation(this)
        }
    }

    private val startTicker = Runnable {
        if (dirX != 0 || dirY != 0) {
            lastFrame = SystemClock.uptimeMillis()
            postOnAnimation(ticker)
        }
    }

    init {
        setWillNotDraw(false)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        val vc = ViewConfiguration.get(context)
        if (Build.VERSION.SDK_INT >= 26) {
            scrollFactorV = vc.scaledVerticalScrollFactor
            scrollFactorH = vc.scaledHorizontalScrollFactor
        } else {
            scrollFactorV = 64f * density
            scrollFactorH = 64f * density
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cx < 0) {
            cx = w / 2f
            cy = h / 2f
        } else {
            cx = cx.coerceIn(0f, w - 1f)
            cy = cy.coerceIn(0f, h - 1f)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Key-up мог уйти в другое окно (диалог, системное меню) — иначе курсор «уплывёт»
        if (!hasWindowFocus) stopMovement()
    }

    override fun onDetachedFromWindow() {
        stopMovement()
        super.onDetachedFromWindow()
    }

    private fun stopMovement() {
        dirX = 0
        dirY = 0
        removeCallbacks(startTicker)
        removeCallbacks(ticker)
    }

    private fun move(dx: Float, dy: Float) {
        val nx = cx + dx
        val ny = cy + dy
        val maxX = width - 1f
        val maxY = height - 1f
        var overX = 0f
        var overY = 0f
        if (nx < 0f) overX = nx else if (nx > maxX) overX = nx - maxX
        if (ny < 0f) overY = ny else if (ny > maxY) overY = ny - maxY
        cx = nx.coerceIn(0f, maxX)
        cy = ny.coerceIn(0f, maxY)
        if (overX != 0f || overY != 0f) scrollAtCursor(overX, overY)
        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE) { }
        invalidate()
    }

    /** Прокрутить элемент под курсором на dx/dy пикселей (колесо мыши). */
    fun scrollAtCursor(dx: Float, dy: Float) {
        dispatchMouse(MotionEvent.ACTION_SCROLL) {
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / scrollFactorV)
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx / scrollFactorH)
        }
    }

    private inline fun dispatchMouse(action: Int, axes: MotionEvent.PointerCoords.() -> Unit) {
        if (cx < 0) return
        val t = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = cx
            y = cy
            axes()
        }
        val ev = MotionEvent.obtain(
            t, t, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!active) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> setDir(down, -1, 0, event)
            KeyEvent.KEYCODE_DPAD_RIGHT -> setDir(down, 1, 0, event)
            KeyEvent.KEYCODE_DPAD_UP -> setDir(down, 0, -1, event)
            KeyEvent.KEYCODE_DPAD_DOWN -> setDir(down, 0, 1, event)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Enter с полноценной клавиатуры отдаём странице (отправка формы и т.п.)
                if (event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && isFullKeyboard(event)) {
                    return super.dispatchKeyEvent(event)
                }
                // Клик на отпускании: долгое нажатие OK открывает панель разделов (MainActivity)
                if (down) {
                    if (event.repeatCount == 0) okDown = true
                } else if (okDown) {
                    okDown = false
                    click()
                }
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun isFullKeyboard(event: KeyEvent): Boolean =
        event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

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
            removeCallbacks(startTicker)
            removeCallbacks(ticker)
            postDelayed(startTicker, 150)
        } else {
            if (x != 0 && dirX == x) dirX = 0
            if (y != 0 && dirY == y) dirY = 0
            if (dirX == 0 && dirY == 0) {
                removeCallbacks(startTicker)
                removeCallbacks(ticker)
            }
        }
    }

    private fun click() = tapAt(cx, cy)

    /** Касание пальцем в точке (x, y) — для клика курсором и выбора элемента стрелками. */
    fun tapAt(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 50)) {
            val ev = MotionEvent.obtain(
                t, time, action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            super.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (active && cx >= 0) {
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)
        }
    }
}
