package ru.kuzel.chaoscalculator

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/** Движение считаем по координатам экрана: сам View при этом может перемещаться. */
class DragTouchListener(
    private val allowed: () -> Boolean,
    private val begin: () -> Unit = {},
    private val move: (Float, Float) -> Unit,
    private val finish: (dragged: Boolean, cancelled: Boolean) -> Unit
) : View.OnTouchListener {
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var tracking = false
    /** Отслеживаем начало, перемещение и конец касания; различаем обычный клик и настоящий жест перетаскивания. */
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!allowed()) return false
                tracking = true; dragging = false
                startX = event.rawX; startY = event.rawY
                v.parent?.requestDisallowInterceptTouchEvent(true)
                begin()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = event.rawX-startX; val dy = event.rawY-startY
                if (hypot(dx,dy) > ViewConfiguration.get(v.context).scaledTouchSlop) dragging = true
                if (dragging) move(dx,dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (tracking) {
                tracking = false
                val cancelled = event.actionMasked != MotionEvent.ACTION_UP
                v.parent?.requestDisallowInterceptTouchEvent(false)
                finish(dragging,cancelled)
                if (!dragging && !cancelled) v.performClick()
                return true
            }
        }
        return tracking
    }
}
