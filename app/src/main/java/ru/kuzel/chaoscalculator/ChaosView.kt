package ru.kuzel.chaoscalculator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import kotlin.math.*
import kotlin.random.Random

/** Прозрачный слой: не перехватывает нажатия на кнопки под ним. */
class ChaosView(context: Context) : View(context) {
    private data class Glyph(
        val text: String, var x: Float, var y: Float,
        var vx: Float, var vy: Float, var rotation: Float, var spin: Float,
        val radius: Float, val color: Int, var squash: Float = 1f
    )
    private val glyphs = mutableListOf<Glyph>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val palette = intArrayOf(Color.rgb(195,248,92), Color.rgb(181,156,255), Color.rgb(255,135,160))
    private var start = 0L
    private var previous = 0L
    private var fontSize = 40f
    private var running = false
    private val density = resources.displayMetrics.density

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }

    /** Разбиваем видимые символы результата на отдельные частицы для анимации падения. */
    fun collapse(source: TextView) {
        glyphs.clear()
        val layout = source.layout ?: return
        val from = IntArray(2); val to = IntArray(2)
        source.getLocationInWindow(from); getLocationInWindow(to)
        fontSize = source.textSize
        paint.textSize = fontSize
        source.text.toString().take(CalculatorEngine.MAX_LENGTH).forEachIndexed { index, char ->
            val x = from[0] - to[0] + source.totalPaddingLeft + layout.getPrimaryHorizontal(index) - source.scrollX
            if (x < -fontSize || x > width + fontSize) return@forEachIndexed
            val y = from[1] - to[1] + source.totalPaddingTop + layout.getLineBaseline(0) - source.scrollY
            glyphs += Glyph(char.toString(), x, y.toFloat(), Random.nextFloat() * 160f - 80f,
                -Random.nextFloat() * 180f, 0f, Random.nextFloat() * 300f - 150f,
                max(fontSize * .28f, 5f), palette[index % palette.size])
        }
        start = SystemClock.uptimeMillis(); previous = start; running = true
        invalidate()
    }

    /** Прерываем падение символов и очищаем список частиц. */
    fun clear() { running = false; glyphs.clear(); invalidate() }

    /** На каждом кадре обновляем положение символов, столкновения и отрисовываем частицы. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glyphs.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val age = (now - start) / 1000f
        val dt = ((now - previous) / 1000f).coerceIn(0f, .033f)
        previous = now
        val floor = height - 12f * density
        if (running && age > .65f) {
            glyphs.forEach { p ->
                p.vy += 1100f * density * dt
                p.x += p.vx * dt; p.y += p.vy * dt; p.rotation += p.spin * dt
                if (p.x < p.radius) { p.x = p.radius; p.vx = abs(p.vx) * .55f }
                if (p.x > width - p.radius * 2) { p.x = width - p.radius * 2; p.vx = -abs(p.vx) * .55f }
                if (p.y >= floor) {
                    p.y = floor; p.vy = -abs(p.vy) * .32f; p.vx *= .9f; p.spin *= .75f
                    p.squash = max(.22f, p.squash - dt * 1.3f)
                }
            }
            // Простые столкновения кругов. Для нескольких десятков символов достаточно.
            for (i in glyphs.indices) for (j in i + 1 until glyphs.size) {
                val a = glyphs[i]; val b = glyphs[j]
                val dx = b.x - a.x; val dy = b.y - a.y
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(.01f)
                val minimum = (a.radius + b.radius) * min(a.squash, b.squash)
                if (distance < minimum && age < 5f) {
                    val nx = if (distance <= .01f) 1f else dx / distance
                    val ny = dy / distance
                    val push = (minimum - distance) * .5f
                    a.x -= nx * push; b.x += nx * push; a.y -= ny * push; b.y += ny * push
                    val impulse = ((b.vx - a.vx) * nx + (b.vy - a.vy) * ny).coerceAtMost(0f) * .65f
                    a.vx += impulse * nx; b.vx -= impulse * nx
                    a.vy += impulse * ny; b.vy -= impulse * ny
                }
            }
            if (age > 8f) running = false
        }
        glyphs.forEachIndexed { i, p ->
            canvas.save()
            val jitter = if (age < .65f) sin(age * 95f + i) * density * 5 else 0f
            canvas.translate(p.x + jitter, min(p.y, floor))
            canvas.rotate(p.rotation)
            canvas.scale(1f + (1f - p.squash) * .8f, p.squash)
            paint.color = p.color; paint.textSize = fontSize
            canvas.drawText(p.text, 0f, 0f, paint)
            canvas.restore()
        }
        if (running && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    /** Останавливаем обновление анимации, когда окно перестало быть видимым. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        previous = SystemClock.uptimeMillis()
        if (visibility == VISIBLE && running) invalidate()
    }
}
