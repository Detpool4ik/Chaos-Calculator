package ru.kuzel.chaoscalculator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.PI
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import kotlin.math.min
import kotlin.math.sin

/** Знак вылетает из своей кнопки, увеличивается перед глазами и возвращается точно на место. */
class PercentFlightView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var progress = 0f
    private var originX = 0f
    private var originY = 0f
    private var destinationX = 0f
    private var destinationY = 0f
    private var textPixels = 26f
    private var ink = Color.WHITE
    private var animator: ValueAnimator? = null
    private var sourceButton: Button? = null
    var isRunning = false
        private set

    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Запускаем полёт знака % из кнопки к дисплею и обратно. */
    fun fly(source: Button, target: View) {
        cancel()
        val here = IntArray(2); val from = IntArray(2); val to = IntArray(2)
        getLocationInWindow(here); source.getLocationInWindow(from); target.getLocationInWindow(to)
        originX = from[0] - here[0] + source.width / 2f
        originY = from[1] - here[1] + source.height / 2f
        destinationX = to[0] - here[0] + target.width / 2f
        destinationY = to[1] - here[1] + target.height / 2f
        textPixels = source.textSize; ink = source.currentTextColor; sourceButton = source
        source.setTextColor(Color.TRANSPARENT)
        progress = 0f; isRunning = true
        animator = ValueAnimator.ofFloat(0f,1f).apply {
            duration = 2400; interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finish() }
            })
            start()
        }
    }

    /** Завершаем полёт и восстанавливаем исходный вид кнопки %. */
    private fun finish() {
        sourceButton?.setTextColor(ink); sourceButton = null
        isRunning = false; invalidate()
    }
    /** Отменяем активный полёт и возвращаем кнопку в исходное состояние. */
    fun cancel() { animator?.cancel(); animator = null; finish() }
    /** При удалении слоя останавливаем полёт и освобождаем аниматор. */
    override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }

    private data class Pose(val x: Float, val y: Float, val scale: Float, val angle: Float)
    /** По прогрессу анимации вычисляем положение, угол и размер летящего знака. */
    private fun pose(t: Float): Pose {
        val reach = if(t < .45f) (t/.45f).let { 1f-(1f-it)*(1f-it)*(1f-it) }
            else if(t < .60f) 1f else ((1f-t)/.40f).coerceIn(0f,1f).let { it*it*(3-2*it) }
        val arc = sin(PI*reach).toFloat()
        val direction = if(t < .6f) -1f else 1f
        val scale = 1f + (min(6.5f, width/(textPixels*1.8f)).coerceAtLeast(1f)-1f)*reach
        return Pose(originX+(destinationX-originX)*reach+direction*arc*width*.16f,
            originY+(destinationY-originY)*reach-arc*height*.10f,scale,
            if(t<.6f) -16f*reach else -16f*reach+sin(PI*reach).toFloat()*30f)
    }

    /** Рисуем на Canvas знак %, свечение, след, искры и возвращение. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isRunning) return
        val density=resources.displayMetrics.density
        val p=pose(progress)
        val presence=sin(PI*progress).toFloat().coerceAtLeast(0f)
        val radius=textPixels*p.scale*.8f
        // Мягкое свечение и короткий шлейф, без перекрывающей интерфейс вспышки.
        paint.shader=RadialGradient(p.x,p.y,radius.coerceAtLeast(1f),
            intArrayOf(0x60B59CFF,0x0065DFF4),null,Shader.TileMode.CLAMP)
        paint.alpha=(180*presence).toInt()
        canvas.drawCircle(p.x,p.y,radius,paint);paint.shader=null
        for(i in 5 downTo 1) {
            val ghost=pose((progress-i*.016f).coerceAtLeast(0f))
            paint.color=if(i%2==0)0xFF65DFF4.toInt() else 0xFFB59CFF.toInt()
            paint.alpha=((60-i*9)*presence).toInt();paint.textSize=textPixels
            canvas.save();canvas.translate(ghost.x,ghost.y);canvas.rotate(ghost.angle);canvas.scale(ghost.scale,ghost.scale)
            canvas.drawText("%",0f,-(paint.ascent()+paint.descent())/2,paint);canvas.restore()
        }
        // В точке разворота расходятся кольцо и искры.
        val burst=((progress-.38f)/.36f).coerceIn(0f,1f)
        if(burst>0f && burst<1f) {
            paint.color=0xFFC3F85C.toInt();paint.alpha=((1-burst)*175).toInt()
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density
            canvas.drawCircle(destinationX,destinationY,(20+85*burst)*density,paint)
            paint.style=Paint.Style.FILL
            for(i in 0..11) {
                val angle=i*PI/6;val distance=(35+95*burst)*density
                paint.color=if(i%2==0)0xFF65DFF4.toInt() else 0xFFC3F85C.toInt()
                paint.alpha=((1-burst)*220).toInt()
                canvas.drawCircle(destinationX+cos(angle).toFloat()*distance,
                    destinationY+sin(angle).toFloat()*distance,(2+2*(i%2))*density*(1-burst),paint)
            }
        }
        canvas.save();canvas.translate(p.x,p.y);canvas.rotate(p.angle);canvas.scale(p.scale,p.scale)
        paint.textSize=textPixels;paint.alpha=255
        paint.color=0xFF5D478B.toInt()
        canvas.drawText("%",1.2f*density,2*density-(paint.ascent()+paint.descent())/2,paint)
        paint.color=ink;canvas.drawText("%",0f,-(paint.ascent()+paint.descent())/2,paint)
        canvas.restore()
    }
}
