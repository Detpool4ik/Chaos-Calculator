package ru.kuzel.chaoscalculator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Проверяем реальные границы раскладки, дополнительно сохраняем PNG для осмотра. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutRenderTest {
    private fun render(name:String,width:Int,height:Int) {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("calculator",Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("chaos",false).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity=controller.get();val decor=activity.window.decorView
        fun layout() {
            decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            decor.layout(0,0,width,height)
        }
        try {
            layout();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64));layout()
            listOf("5","+","1","0","×","2").forEach { decor.findViewWithTag<View>(it).performClick() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64));layout()
            for(tag in listOf("display_column","C","0","=")) {
                val v=decor.findViewWithTag<View>(tag);val pos=IntArray(2);v.getLocationInWindow(pos)
                assertTrue("$name $tag width",v.width>0)
                assertTrue("$name $tag bottom ${pos[1]+v.height}/$height",pos[1]+v.height<=height)
                assertTrue("$name $tag right",pos[0]+v.width<=width)
            }
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val directory=File("build/layout-previews").apply { mkdirs() }
            File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun portrait() = render("portrait",720,1600)
    @Test @Config(qualifiers="w640dp-h360dp-land-xhdpi")
    fun landscape() = render("landscape",1280,720)
}
