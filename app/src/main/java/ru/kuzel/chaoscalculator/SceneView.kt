package ru.kuzel.chaoscalculator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*
import kotlin.random.Random

/** Декорации рисуются поверх View, но не перехватывают касания. */
class SceneView(context: Context) : View(context) {
    // Каждая сцена имеет отдельный способ рисования, но использует один общий Canvas.
    enum class Scene { NONE, HOLE, HOLE_RETURN, FIGHT, SLEEP, ERASE }
    companion object {
        const val FIGHT_MS = 5200L
        const val RETURN_MS = 2500L
        const val ERASE_MS = 1500L
    }
    var scene = Scene.NONE
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private var animation: ValueAnimator? = null
    private var progress = 0f
    private var plus = PointF()
    private var minus = PointF()
    private var rope = PointF()
    private var sleepers = emptyList<PointF>()
    private val density = resources.displayMetrics.density
    private val colors = intArrayOf(0xFFC3F85C.toInt(),0xFFB59CFF.toInt(),0xFFFF8296.toInt(),0xFFFFDA64.toInt(),0xFF65DFF4.toInt())
    private val impacts = arrayOf("БАХ!", "МИНУС БРОНЯ!", "ПЛЮС КРИТ!", "НИЧЬЯ?")
    private data class Fragment(val x: Float,val y: Float,val dx: Float,val dy: Float,val turn: Float,val color: Int,val label: String)
    private var fragments = emptyList<Fragment>()
    private var eraseStage = 0
    val holeX get() = width * .5f
    val holeY get() = height * .38f
    val holeRadius get() = min(width*.24f,100*density)
    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Находим центр любой кнопки в координатах анимационного слоя. */
    fun centerOf(view: View): PointF {
        val origin = IntArray(2); val target = IntArray(2)
        getLocationInWindow(origin); view.getLocationInWindow(target)
        return PointF(target[0] - origin[0] + view.width/2f, target[1] - origin[1] + view.height/2f)
    }
    /** Проверяем, оказалась ли перетаскиваемая C в центре чёрной дыры. */
    fun catches(view: View): Boolean {
        val p = centerOf(view)
        return hypot(p.x-holeX,p.y-holeY) <= holeRadius
    }
    /** Обновляем положение виртуальной верёвки между кнопкой C и чёрной дырой. */
    fun updateRope(view: View) { rope = centerOf(view); invalidate() }
    /** Отменяем сцену и её анимацию, очищаем анимационный слой. */
    fun clear() {
        animation?.removeAllListeners(); animation?.cancel(); animation = null
        fragments = emptyList(); scene = Scene.NONE; invalidate()
    }
    /** Универсальный запуск ValueAnimator на указанную длительность с вызовом finish по завершении. */
    private fun animateFor(durationMs: Long, finish: () -> Unit = {}) {
        progress = 0f
        animation = ValueAnimator.ofFloat(0f,1f).apply {
            duration = durationMs; interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finish() }
            }); start()
        }
    }
    /** Запускаем сцену чёрной дыры и связываем её с кнопкой спасения. */
    fun hole(escape: View) { clear(); updateRope(escape); scene = Scene.HOLE; animateFor(2300) }
    /** Запускаем обратную анимацию исчезновения чёрной дыры. */
    fun returnHole() { clear(); scene = Scene.HOLE_RETURN; animateFor(RETURN_MS) }
    /** Запоминаем координаты + и − и запускаем сцену их столкновения. */
    fun fight(plusButton: View, minusButton: View, finish: () -> Unit) {
        clear(); plus = centerOf(plusButton); minus = centerOf(minusButton); scene = Scene.FIGHT
        animateFor(FIGHT_MS) { scene = Scene.NONE; invalidate(); finish() }
    }
    /** Готовим разлетающиеся фрагменты кнопок для очередного этапа стирания. */
    fun erase(stage: Int, targets: List<View>, finish: () -> Unit) {
        clear(); eraseStage = stage; scene = Scene.ERASE
        val random = Random(stage*73 + System.nanoTime())
        fragments = targets.flatMap { view ->
            val p = centerOf(view)
            List(if(stage==1) 4 else 9) { index ->
                Fragment(p.x+(random.nextFloat()-.5f)*view.width,p.y+(random.nextFloat()-.5f)*view.height,
                    (random.nextFloat()-.5f)*300*density,-(50+random.nextFloat()*180)*density,
                    (random.nextFloat()-.5f)*480,colors[index%colors.size],if(stage==1) view.tag?.toString().orEmpty() else "")
            }
        }
        animateFor(ERASE_MS) { scene = Scene.NONE; invalidate(); finish() }
    }
    /** Показываем эффект уснувших кнопок. */
    fun sleep(buttons: List<View>) { clear(); sleepers = buttons.map { centerOf(it) }; scene = Scene.SLEEP; invalidate() }
    /** При удалении View с экрана прекращаем анимации, чтобы не оставлять активные ссылки. */
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
    /** В зависимости от Scene рисуем на Canvas подходящую анимацию. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.reset(); paint.isAntiAlias = true; paint.typeface = Typeface.DEFAULT_BOLD; paint.textAlign = Paint.Align.CENTER
        when(scene) {
            Scene.HOLE, Scene.HOLE_RETURN -> drawHole(canvas)
            Scene.FIGHT -> drawFight(canvas)
            Scene.ERASE -> drawErase(canvas)
            Scene.SLEEP -> {
                paint.color = colors[1]; paint.textSize = 13f*density
                sleepers.forEachIndexed { index,p -> canvas.drawText(if(index%2==0) "z" else "zz",p.x+14*density,p.y-18*density,paint) }
            }
            else -> Unit
        }
    }
    /** Рисуем чёрную дыру, кольца и втягивающиеся частицы. */
    private fun drawHole(canvas: Canvas) {
        val returning = scene == Scene.HOLE_RETURN
        val radius = holeRadius * if(returning) (1-progress).coerceAtLeast(0f) else (.15f+.85f*min(1f,progress*2f))
        if(!returning) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3*density; paint.color = 0xFFD2B985.toInt()
            path.reset(); path.moveTo(rope.x,rope.y+18*density)
            path.cubicTo(rope.x+40*density,rope.y+65*density,holeX-60*density,holeY-radius,holeX,holeY)
            canvas.drawPath(path,paint)
        }
        for(i in 0..5) {
            paint.style = Paint.Style.STROKE; paint.color = colors[if(returning)i%5 else 1]
            paint.alpha = ((210-i*24)*(if(returning)1-progress else 1f)).toInt()
            paint.strokeWidth = (6-i*.6f)*density
            val r=radius+i*5*density
            bounds.set(holeX-r,holeY-r*.55f,holeX+r,holeY+r*.55f)
            canvas.save(); canvas.rotate(-20f+progress*45f,holeX,holeY)
            canvas.drawArc(bounds,progress*(if(returning)-720f else 720f)+i*42f,255f,false,paint);canvas.restore()
        }
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.color=Color.BLACK
        canvas.drawCircle(holeX,holeY,radius*.8f,paint)
        if(returning) {
            for(i in 0..39) {
                val a=i*2.39996;val distance=progress*(100+i%7*23)*density
                paint.color=colors[i%5];paint.alpha=((1-progress)*240).toInt()
                canvas.drawCircle(holeX+cos(a).toFloat()*distance,holeY+sin(a).toFloat()*distance,(2+i%4)*density,paint)
            }
        } else {
            paint.color=colors[0];paint.textSize=13*density
            canvas.drawText("ОТПУСТИ C ЗДЕСЬ",holeX,holeY+radius+38*density,paint)
        }
    }
    /** Рисуем столкновение знаков + и −, вспышки и движение частиц. */
    private fun drawFight(canvas: Canvas) {
        val enter = (progress/.18f).coerceIn(0f,1f)
        val leave = ((progress-.8f)/.2f).coerceIn(0f,1f)
        val presence = min(enter,1-leave)
        val cx=width*.5f;val cy=height*.57f
        paint.color=Color.BLACK;paint.alpha=(100*presence).toInt();canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        val combat=((progress-.18f)/.62f).coerceIn(0f,.999f)
        val hit = combat*4 - floor(combat*4)
        val brawling = progress in .18f.. .8f
        if(brawling) {
            for(i in 0..9) {
                val a=i*.628+progress*7
                paint.color=colors[i%5];paint.alpha=65
                canvas.drawCircle(cx+cos(a).toFloat()*50*density,cy+sin(a).toFloat()*32*density,(27+i%3*5)*density,paint)
            }
            // Комиксная вспышка после каждого из четырёх ударов.
            val flash = (1-abs(hit-.45f)/.25f).coerceIn(0f,1f)
            path.reset()
            for(i in 0..23) {
                val a=i*PI/12;val r=(if(i%2==0) 105 else 57)*density*(.7f+.3f*flash)
                val x=cx+cos(a).toFloat()*r;val y=cy+sin(a).toFloat()*r
                if(i==0)path.moveTo(x,y) else path.lineTo(x,y)
            }
            path.close();paint.color=colors[3];paint.alpha=(150*flash).toInt();canvas.drawPath(path,paint)
            for(i in 0..17) {
                val a=i*.349+progress*4;val r=(65+hit*65)*density
                paint.color=colors[i%5];paint.alpha=((1-hit)*255).toInt();paint.strokeWidth=3*density
                val x=cx+cos(a).toFloat()*r;val y=cy+sin(a).toFloat()*r
                canvas.drawLine(x,y,x+cos(a).toFloat()*15*density,y+sin(a).toFloat()*15*density,paint)
            }
        }
        for(i in 0..1) {
            val from=if(i==0)plus else minus;val direction=if(i==0)-1 else 1
            val punch=if(brawling) sin(hit*PI).toFloat()*37*density else 0f
            val targetX=cx+direction*(48*density-punch)
            val targetY=cy+(if(brawling)sin(combat*PI*8+i*PI).toFloat()*15*density else 0f)
            val x=from.x+(targetX-from.x)*presence;val y=from.y+(targetY-from.y)*presence
            paint.color=colors[if(i==0)0 else 2];paint.alpha=(65*presence).toInt()
            canvas.drawCircle(x,y,42*density*presence,paint)
            paint.alpha=255;paint.textSize=(27+38*presence)*density
            canvas.save();canvas.rotate(direction*(if(brawling)sin(hit*PI*2).toFloat()*25 else 15f)*presence,x,y)
            canvas.drawText(if(i==0) "+" else "−",x,y-(paint.ascent()+paint.descent())/2,paint);canvas.restore()
        }
        paint.alpha=(255*presence).toInt();paint.color=colors[3];paint.textSize=20*density
        canvas.drawText(if(brawling)impacts[(combat*4).toInt()] else if(progress<.18f) "ПЛЮС vs МИНУС" else "ЛАДНО, СЧИТАЕМ ДАЛЬШЕ",cx,cy-108*density,paint)
    }
    /** Рисуем разлетающиеся фрагменты и визуальный эффект ластика. */
    private fun drawErase(canvas: Canvas) {
        for(f in fragments) {
            val t=progress;val x=f.x+f.dx*t;val y=f.y+f.dy*t+180*density*t*t
            paint.color=f.color;paint.alpha=((1-t)*255).toInt()
            canvas.save();canvas.rotate(f.turn*t,x,y)
            if(f.label.isNotEmpty()) {paint.textSize=(22+8*t)*density;canvas.drawText(f.label,x,y,paint)}
            else {val size=(4+5*(1-t))*density;canvas.drawRect(x-size,y-size,x+size,y+size,paint)}
            canvas.restore()
        }
        val x=-70*density+(width+140*density)*progress
        val y=if(eraseStage==3) height*(.2f+.6f*progress) else height*.64f
        // Только объёмный ластик: отдельной линии-следа нет.
        canvas.save();canvas.translate(x,y);canvas.rotate(-22f)
        paint.alpha=255;paint.color=colors[2];bounds.set(-48*density,-23*density,48*density,23*density)
        canvas.drawRoundRect(bounds,8*density,8*density,paint)
        paint.color=colors[4];bounds.set(0f,-23*density,48*density,23*density);canvas.drawRoundRect(bounds,8*density,8*density,paint)
        paint.color=0x50FFFFFF;bounds.set(-40*density,-19*density,39*density,-14*density)
        canvas.drawRoundRect(bounds,3*density,3*density,paint)
        canvas.restore()
    }
}
