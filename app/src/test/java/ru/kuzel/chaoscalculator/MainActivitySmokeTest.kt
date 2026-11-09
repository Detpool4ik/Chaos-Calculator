package ru.kuzel.chaoscalculator

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** View, реальные события касания и управляемое время кадров на API 24. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivitySmokeTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private fun view(tag: String): View = activity.window.decorView.findViewWithTag(tag)
    private fun button(tag: String) = view(tag) as Button
    private fun click(vararg keys: String) { keys.forEach { view(it).performClick() }; shadowOf(Looper.getMainLooper()).idle() }
    private fun waitMs(ms: Long) {
        // В paused-режиме vsync создаётся при продвижении часов. Прокручиваем каждый кадр,
        // иначе один скачок на 5 секунд только запустит первый кадр анимации.
        var remaining=ms
        while(remaining>0) {
            val step=minOf(16L,remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step));remaining-=step
        }
    }
    private fun answer() = (view("expression") as TextView).text.toString()
    private fun scene() = (view("scenes") as SceneView).scene
    private fun drag(tag: String, dx: Float, dy: Float, cancel: Boolean = false) {
        val v=view(tag);val pos=IntArray(2);v.getLocationOnScreen(pos)
        val x=pos[0]+v.width/2f;val y=pos[1]+v.height/2f;val now=SystemClock.uptimeMillis()
        fun send(action:Int,px:Float,py:Float) {
            val e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,px,py,0)
            v.dispatchTouchEvent(e);e.recycle()
        }
        send(MotionEvent.ACTION_DOWN,x,y);send(MotionEvent.ACTION_MOVE,x+dx,y+dy)
        send(if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,x+dx,y+dy)
    }
    private fun dragIntoHole(cancel:Boolean=false) {
        val scene=view("scenes") as SceneView;val point=scene.centerOf(view("rescue"))
        drag("rescue",scene.holeX-point.x,scene.holeY-point.y,cancel)
    }
    @Before fun setup() {
        ShadowChoreographer.setPaused(false)
        RuntimeEnvironment.getApplication().getSharedPreferences("calculator",Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("calculator",Context.MODE_PRIVATE).edit().putBoolean("chaos",true).commit()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible();activity=controller.get()
        val decor=activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY))
        decor.layout(0,0,1080,1920);shadowOf(Looper.getMainLooper()).idle()
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ShadowChoreographer.setPaused(true)
    }
    @After fun finish() { controller.pause().stop().destroy() }

    @Test fun erasingShowsReplyChoicesAndRecoversAllKeys() {
        repeat(9){click("⌫")};assertEquals(SceneView.Scene.ERASE,scene());waitMs(2100)
        assertEquals(View.INVISIBLE,view("calc_content").visibility)
        assertEquals(View.VISIBLE,view("ending_card").visibility)
        val first=(view("ending_text") as TextView).text.toString()
        click("ending_artist");assertNotEquals(first,(view("ending_text") as TextView).text.toString())
        click("ending_denial");assertTrue((view("ending_text") as TextView).text.contains("кот"))
        click("ending_restore");assertEquals(View.VISIBLE,view("calc_content").visibility)
        click("2","+","2","=");assertEquals("4",answer());assertEquals(1f,button("2").alpha,.01f)
    }
    @Test fun erasingCanBeCancelledBeforeEndingAppears() {
        repeat(6){click("⌫")};waitMs(200);click("rescue");waitMs(2200)
        assertEquals(View.GONE,view("ending_card").visibility);assertEquals(SceneView.Scene.NONE,scene())
        assertEquals(1f,button("8").scaleX,.01f);assertEquals(1f,button("8").alpha,.01f)
        click("8");assertEquals("8",answer())
    }
    @Test fun finalReplyChangesOnNextErase() {
        repeat(9){click("⌫")};waitMs(2100);val first=(view("ending_text") as TextView).text.toString()
        click("ending_restore");repeat(9){click("⌫")};waitMs(2100)
        assertNotEquals(first,(view("ending_text") as TextView).text.toString())
    }
    @Test fun blackHoleRequiresDropAndReturnsKeysWithAnimation() {
        click("8","÷","0","=");waitMs(2600)
        click("rescue");assertEquals(SceneView.Scene.HOLE,scene())
        drag("rescue",70f,30f);waitMs(400);assertEquals(SceneView.Scene.HOLE,scene())
        assertEquals(0f,view("rescue").translationX,.01f)
        dragIntoHole();assertEquals(SceneView.Scene.HOLE_RETURN,scene());waitMs(600)
        assertEquals(SceneView.Scene.HOLE_RETURN,scene());assertTrue(button("8").scaleX>.02f)
        waitMs(2300);assertEquals(SceneView.Scene.NONE,scene())
        assertEquals(1f,button("8").scaleX,.01f);assertEquals(1f,button("8").alpha,.01f)
        assertEquals(0f,button("8").translationX,.01f)
        click("5","+","2","=");assertEquals("7",answer())
    }
    @Test fun cancellingHoleDragDoesNotReleaseButtons() {
        click("8","÷","0","=");waitMs(2600);dragIntoHole(cancel=true);waitMs(500)
        assertEquals(SceneView.Scene.HOLE,scene());assertEquals(0f,view("rescue").translationY,.01f)
    }
    @Test fun turningOffChaosDuringReturnCancelsPendingAnimation() {
        click("8","÷","0","=");waitMs(2600);dragIntoHole();waitMs(400)
        (view("chaos_switch") as Switch).isChecked=false
        click("C","3","+","3","=");waitMs(3000)
        assertEquals("6",answer());assertEquals(SceneView.Scene.NONE,scene())
        assertEquals(1f,button("3").alpha,.01f)
    }
    @Test fun interfaceCanBeDraggedAndHomePreservesCalculation() {
        click("4","2");drag("drag_handle",110f,180f)
        assertTrue(view("calc_content").translationX>0);assertTrue(view("calc_content").translationY>0)
        assertEquals(View.VISIBLE,view("move_home").visibility)
        click("move_home");assertEquals(0f,view("calc_content").translationY,.01f)
        assertEquals(0f,view("calc_content").translationX,.01f);assertEquals("42",answer())
    }
    @Test fun dragIsFreeAndDisabledInOrdinaryMode() {
        drag("drag_handle",100000f,100000f)
        val origin=IntArray(2);view("drag_handle").getLocationOnScreen(origin)
        assertTrue(origin[1]>activity.window.decorView.height)
        click("move_home");assertEquals(0f,view("calc_content").translationX,.01f)
        (view("chaos_switch") as Switch).isChecked=false;drag("drag_handle",100f,100f)
        assertEquals(0f,view("calc_content").translationY,.01f)
    }
    @Test fun negativeAnswersNoLongerMoveInterfaceOrReplacePlus() {
        click("1","−","2","=");click("2","−","4","=");click("3","−","5","=");waitMs(700)
        assertEquals("+",button("+").text.toString());assertEquals(0f,view("calc_content").translationY,.01f)
        click("+","4","=");assertEquals("2",answer())
    }
    @Test fun signsFightStaysVisibleAndExtraPressDoesNotCancelIt() {
        click("5","+","−","+","−","+","−")
        assertEquals(SceneView.Scene.FIGHT,scene());click("−");waitMs(2400)
        assertEquals(SceneView.Scene.FIGHT,scene());assertFalse(button("2").isEnabled)
        waitMs(3100);assertEquals(SceneView.Scene.NONE,scene());assertTrue(button("2").isEnabled)
        assertEquals(1f,button("+").alpha,.01f);click("2","=");assertEquals("3",answer())
    }
    @Test fun clearInterruptsFightAndRestoresKeys() {
        click("5","+","−","+","−","+","−");waitMs(500);click("C","8");waitMs(5500)
        assertEquals(SceneView.Scene.NONE,scene());assertEquals("8",answer());assertTrue(button("+").isEnabled)
    }
    @Test fun sleepingWakesOnFirstDigitAndDoesNotEatInput() {
        activity.window.decorView.requestFocus();controller.windowFocusChanged(true);waitMs(20500)
        assertEquals(SceneView.Scene.SLEEP,scene());click("8");waitMs(300)
        assertEquals("8",answer());assertEquals(SceneView.Scene.NONE,scene());assertEquals(0f,button("8").rotation,.01f)
    }
    @Test fun pauseDuringFastInputPreventsCollapse() {
        repeat(30){click("1")};waitMs(1900)
        assertEquals(View.VISIBLE,view("expression").visibility);assertEquals("1".repeat(30),answer());assertTrue(button("2").isEnabled)
    }
    @Test fun ordinaryModeDoesNotEraseOrOpenHole() {
        (view("chaos_switch") as Switch).isChecked=false;repeat(9){click("⌫")};click("8","÷","0","=")
        assertEquals(SceneView.Scene.NONE,scene());assertEquals(View.GONE,view("rescue").visibility)
        assertEquals(View.VISIBLE,view("calc_content").visibility)
    }
    @Test fun savedCalculationAndPreferencesSurviveRecreation() {
        (view("chaos_switch") as Switch).isChecked=false;click("4","0","+","2","=")
        ShadowChoreographer.setPaused(false);waitMs(32)
        controller.recreate();activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
        ShadowChoreographer.setPaused(true)
        assertEquals("42",answer());assertFalse((view("chaos_switch") as Switch).isChecked)
        val history=activity.getSharedPreferences("calculator",Context.MODE_PRIVATE).getString("history","").orEmpty()
        assertTrue(history.contains("40+2 = 42"))
    }
    @Test fun threeDisplayLinesPreservePriorityAndSurviveRecreation() {
        (view("chaos_switch") as Switch).isChecked=false
        click("5","+","1","0","×","2")
        assertEquals("5 + 10 ×",(view("previous_expression") as TextView).text.toString())
        assertEquals("×",(view("current_operation") as TextView).text.toString())
        assertEquals("2",answer())
        click("=");assertEquals("25",answer())
        ShadowChoreographer.setPaused(false);waitMs(32)
        controller.recreate();activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
        ShadowChoreographer.setPaused(true)
        assertEquals("5 + 10 × 2",(view("previous_expression") as TextView).text.toString())
        assertEquals("",(view("current_operation") as TextView).text.toString())
        assertEquals("25",answer())
    }
    @Test fun computed33RemainsUsableWhileTyped33Locks() {
        click("1","1","×","3","=");assertEquals("33",answer());assertTrue(button("8").isEnabled)
        click("+","2","=");assertEquals("35",answer())
        click("C","3","3");assertFalse(button("8").isEnabled)
    }
    @Test fun invisibleKeysCannotRestoreDuringOrAfterLastErase() {
        repeat(9){click("⌫")}
        click("8","C","=");assertEquals(View.VISIBLE,view("erase_shield").visibility)
        assertFalse(button("8").isEnabled)
        waitMs(2100);click("2","C")
        assertEquals(View.INVISIBLE,view("calc_content").visibility)
        assertEquals(View.VISIBLE,view("ending_card").visibility)
        click("ending_restore","2");assertEquals("2",answer())
    }
    @Test fun erasedInterfaceStaysErasedAfterRecreation() {
        repeat(9){click("⌫")};waitMs(2100)
        ShadowChoreographer.setPaused(false);waitMs(32)
        controller.recreate();activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
        ShadowChoreographer.setPaused(true)
        click("8");assertEquals(View.INVISIBLE,view("calc_content").visibility)
        assertEquals(View.VISIBLE,view("ending_card").visibility)
        click("ending_restore","8");assertEquals("8",answer())
    }
    @Test fun clearLetterStaysWhileItsBackgroundMoves() {
        val before=IntArray(2);button("C").getLocationInWindow(before)
        drag("drag_handle",110f,180f);waitMs(32)
        val letter=IntArray(2);view("fixed_clear").getLocationInWindow(letter)
        val after=IntArray(2);button("C").getLocationInWindow(after)
        assertArrayEquals(before,letter)
        assertEquals(110,after[0]-before[0]);assertEquals(180,after[1]-before[1])
        assertEquals(android.graphics.Color.TRANSPARENT,button("C").currentTextColor)
        click("move_home");assertEquals(View.GONE,view("fixed_clear").visibility)
    }
    @Test fun percentReturnsItsOriginalInkAndStopsCleanly() {
        val ink=button("%").currentTextColor
        repeat(5){click("%")}
        assertEquals(android.graphics.Color.TRANSPARENT,button("%").currentTextColor)
        waitMs(2700);assertEquals(ink,button("%").currentTextColor)
        click("C");repeat(5){click("%")};click("8")
        assertEquals(ink,button("%").currentTextColor)
    }
    @Test @Config(qualifiers="w640dp-h360dp-land")
    fun landscapeKeepsDisplayBesideKeyboardAndShowsAllDigits() {
        click("5","+","1","0","×","2","=");assertEquals("25",answer())
        val d=IntArray(2);val k=IntArray(2)
        view("display_column").getLocationInWindow(d);button("C").getLocationInWindow(k)
        assertTrue(d[0]<k[0]);for(i in 0..9)assertEquals(i.toString(),button(i.toString()).text.toString())
    }

}
