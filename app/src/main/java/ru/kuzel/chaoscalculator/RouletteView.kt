package ru.kuzel.chaoscalculator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.*
import kotlin.random.Random

/** Настоящее вращающееся колесо: выбран сектор под верхним указателем. */
class RouletteView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val pointer = Path()
    private var angle = 0f
    private var animator: ValueAnimator? = null
    private var stopping = false
    private val colors = intArrayOf(Color.rgb(195,248,92), Color.rgb(181,156,255))
    /** Переводим угол остановленного колеса в цифру сектора от 0 до 9. */
    private fun selected() = floor((((-90f - angle) % 360f + 360f) % 360f) / 36f).toInt().coerceIn(0,9)

    /** Запускаем непрерывное вращение колеса. */
    fun spin() {
        animator?.cancel()
        stopping = false
        animator = ValueAnimator.ofFloat(angle, angle + 360f).apply {
            duration = 750; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }; start()
        }
    }

    /** Плавно тормозим рулетку и передаём выбранную цифру в callback. */
    fun stop(onResult: (Int) -> Unit) {
        if (stopping) return
        stopping = true
        animator?.cancel()
        animator = ValueAnimator.ofFloat(angle, angle + 540f + Random.nextFloat() * 360f).apply {
            duration = 1800; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onResult(selected()) }
            })
            start()
        }
    }

    /** Останавливаем колесо и удаляем аниматор. */
    fun release() { animator?.cancel(); animator = null }
    /** Освобождаем аниматор при закрытии окна с рулеткой. */
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    /** Рисуем сектора, цифры и стрелку рулетки на Canvas. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val radius = min(width, height) * .43f
        bounds.set(cx-radius, cy-radius, cx+radius, cy+radius)
        for (i in 0..9) {
            paint.color = colors[i % 2]
            canvas.drawArc(bounds, angle + i*36f, 35.5f, true, paint)
            val radians = Math.toRadians((angle + i*36f + 18f).toDouble())
            paint.color = Color.rgb(25,25,32); paint.textSize = radius * .2f
            paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(i.toString(), cx + cos(radians).toFloat()*radius*.74f,
                cy + sin(radians).toFloat()*radius*.74f + paint.textSize*.35f, paint)
        }
        paint.color = Color.rgb(25,25,32); canvas.drawCircle(cx,cy,radius*.3f,paint)
        paint.color = Color.WHITE; paint.textSize = radius*.32f
        canvas.drawText(selected().toString(),cx,cy+paint.textSize*.35f,paint)
        pointer.apply {
            reset()
            moveTo(cx - radius*.09f, cy-radius-10f)
            lineTo(cx + radius*.09f, cy-radius-10f)
            lineTo(cx,cy-radius+radius*.14f); close()
        }
        paint.color = Color.WHITE; canvas.drawPath(pointer, paint)
    }
}
