package ru.kuzel.chaoscalculator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp as composeDp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.res.Configuration
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import kotlin.math.sin
import kotlin.math.PI
import android.widget.*
import org.json.JSONArray

/** Compose Column для дисплея, Android Views для клавиш и физических эффектов. API 24+. */
class MainActivity : ComponentActivity() {
    // Движок хранит выражение и считает; Activity только показывает состояние и принимает нажатия.
    private val engine = CalculatorEngine()
    // Локальное постоянное хранилище Android: тема, история, выбранный режим и ввод.
    private val prefs by lazy { getSharedPreferences("calculator", MODE_PRIVATE) }
    // Очередь действий главного UI-потока: отложенные эффекты, сон, предупреждения.
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var expressionScroll: HorizontalScrollView
    private lateinit var sceneView: SceneView
    private lateinit var rescue: Button
    private lateinit var dragHandle: TextView
    private lateinit var homeButton: Button
    private lateinit var endingCard: LinearLayout
    private lateinit var endingText: TextView
    private var holeReady = false
    private var holeReturning = false
    private var returnAnimator: ValueAnimator? = null
    private var holeStartX = 0f
    private var holeStartY = 0f
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var previousEnding = -1
    private val endingLines = listOf(
        "Всё. Теперь это приложение для созерцания пустоты.",
        "Поздравляю. Ты удалил математику.",
        "Калькулятор вышел из чата. Ластик остался главным.",
        "Интерфейс закончился. Можешь пока посчитать в уме.",
        "Ты победил. Осталось объяснить это преподавателю."
    )
    private val holeReadyTick = Runnable {
        if(engine.mode == Mode.BLACK_HOLE && !holeReturning) {
            holeReady = true; rescue.text = "Тяни C в дыру"
        }
    }
    // Вспомогательная логика пасхалок, не зависящая от интерфейса.
    private val jokes = JokeState()
    private var sleeping = false
    private var displayedEraseStage = 0
    private lateinit var expressionView: TextView
    private lateinit var subtitle: TextView
    private lateinit var operationView: TextView
    private lateinit var fixedClear: TextView
    private lateinit var eraseShield: View
    private lateinit var messageView: TextView
    private lateinit var statusView: TextView
    private lateinit var chaosView: ChaosView
    private lateinit var percentFlight: PercentFlightView
    private lateinit var chaosSwitch: Switch
    // Счётчик повторных нажатий для анимаций (% , = и цифр).
    private val tapSequence = TapSequence()
    private var inflatedButton: Button? = null
    private val deflate = Runnable { resetInflation(animated = true) }
    private val buttons = mutableListOf<Button>()
    private val digits = mutableListOf<Button>()
    private val digitOrder = listOf("7","8","9","4","5","6","1","2","3","0")
    private val history = mutableListOf<String>()
    private var dark = true
    private var haptics = true
    private var lastMode: Mode? = null
    private var shaker: ObjectAnimator? = null
    private var roulette: AlertDialog? = null
    private var wheel: RouletteView? = null
    private var active = false
    private var thinkingStep = 0
    private val lime = Color.rgb(195,248,92)
    private val violet = Color.rgb(181,156,255)
    private val background get() = if (dark) Color.rgb(16,17,22) else Color.rgb(242,243,237)
    private val foreground get() = if (dark) Color.rgb(243,244,237) else Color.rgb(25,27,35)
    private val panel get() = if (dark) Color.rgb(29,31,39) else Color.WHITE
    private val muted get() = if (dark) Color.rgb(153,157,172) else Color.rgb(94,98,115)

    private val thinkingTick = object : Runnable {
        override fun run() {
            if (!active || engine.mode != Mode.THINKING) return
            val lines = arrayOf("Думаю… 98%", "Думаю… 99%", "Думаю… 98%", "Не торопи. Тут сложное.", "Почти… 99%")
            expressionView.text = lines[thinkingStep++ % lines.size]
            handler.postDelayed(this, 1100)
        }
    }
    private val settleShake = Runnable { stopShaking() }
    private val overloadTick = Runnable {
        engine.finishOverload(SystemClock.uptimeMillis()); render(); save()
    }
    private val idleTick = object : Runnable {
        override fun run() {
            if (!active) return
            if (engine.chaos && engine.mode == Mode.NORMAL && jokes.eraseStage == 0 &&
                sceneView.scene == SceneView.Scene.NONE &&
                !percentFlight.isRunning && roulette == null && window.decorView.hasWindowFocus()) {
                resetNewEffects(); sleeping = true
                buttons.forEachIndexed { index, b -> b.animate().rotation(if(index%2==0) 10f else -8f)
                    .translationY(dp(4).toFloat()).alpha(.65f).setDuration(650).start() }
                sceneView.sleep(digits)
                messageView.text = "Тс-с. Они уснули. Нажми что-нибудь."
            } else scheduleIdle()
        }
    }

    /** Точка входа Activity: загружаем настройки, восстанавливаем вычисление и создаём экран. savedInstanceState используется после поворота, SharedPreferences — после перезапуска. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = prefs.getBoolean("dark", true)
        haptics = prefs.getBoolean("haptics", true)
        engine.setChaos(prefs.getBoolean("chaos", false))
        val saved = savedInstanceState
        engine.restore(saved?.getString("expression") ?: prefs.getString("expression", "").orEmpty(),
            saved?.getString("mode") ?: prefs.getString("mode", "NORMAL").orEmpty(),
            saved?.getBoolean("evaluated") ?: prefs.getBoolean("evaluated", false),
            saved?.getString("previous") ?: prefs.getString("previous", "").orEmpty(),
            saved?.getInt("generated") ?: prefs.getInt("generated", if(prefs.getBoolean("evaluated",false)) prefs.getString("expression", "").orEmpty().length else 0))
        runCatching {
            val data = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until minOf(data.length(), 30)) history += data.getString(i)
        }
        buildUi()
        jokes.restoreStage(saved?.getInt("erase_stage") ?: prefs.getInt("erase_stage",0))
    }

    /** Переводим независимые от плотности dp в реальные пиксели для размеров Android View. */
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    /** Создаём TextView с нужным размером и цветом. Вынесено в функцию, чтобы не дублировать оформление. */
    private fun text(size: Float, color: Int = foreground) = TextView(this).apply {
        textSize = size; setTextColor(color); fontFeatureSettings = "tnum"
    }
    /** Создаём фоновую фигуру со скруглёнными углами. */
    private fun rounded(color: Int, radius: Int = 22) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    /** Создаём кнопку с текстом, цветом, скруглением и эффектом нажатия. */
    private fun key(label: String, color: Int = panel, ink: Int = foreground, size: Float = 25f) = Button(this).apply {
        text = label; textSize = size; isAllCaps = false; setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = RippleDrawable(ColorStateList.valueOf(0x30555555), rounded(color), null)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0,0,0,0)
        stateListAnimator = null
    }

    /** Собираем весь интерфейс программно: верхняя панель, Compose-дисплей, клавиатура, анимационные слои и вспомогательные кнопки. Для альбомной и книжной ориентации разная раскладка. */
    @Suppress("DEPRECATION")
    private fun buildUi() {
        resetTheatre()
        resetNewEffects()
        stopShaking(); handler.removeCallbacks(thinkingTick)
        buttons.clear(); digits.clear(); lastMode = null
        root = FrameLayout(this).apply { setBackgroundColor(this@MainActivity.background) }
        // Системные панели не перекрывают клавиши, в том числе при targetSdk 35.
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        window.statusBarColor = background; window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val compact = landscape || resources.configuration.screenHeightDp < 740
        val keyHeight = if(landscape) 46 else if(compact) 48 else 60
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; clipChildren = false }
        contentScroll = scroll; contentScroll.tag = "calc_content"
        contentScroll.background = rounded(background,24)
        root.addView(scroll, FrameLayout.LayoutParams(-1,-1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(14),dp(4),dp(14),dp(4)) }
        scroll.addView(content, FrameLayout.LayoutParams(-1,-1))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        dragHandle = text(12f, muted).apply {
            tag = "drag_handle"; text = "⠿ КАЛЬКУЛЯТОР / 05\nТяни за эту полоску"; gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Переместить калькулятор"; isClickable = true
            setOnClickListener { if(engine.chaos) Toast.makeText(this@MainActivity,"Зажми полоску и перетаскивай",Toast.LENGTH_SHORT).show() }
        }
        attachInterfaceDrag()
        header.addView(dragHandle, LinearLayout.LayoutParams(0,dp(if(landscape)32 else 40),1f))
        header.addView(key("☰", panel, foreground, 22f).apply {
            contentDescription = "История вычислений"; setOnClickListener { showHistory() }
        }, LinearLayout.LayoutParams(dp(40),dp(if(landscape)32 else 40)).apply { marginEnd = dp(8) })
        header.addView(key("⚙", panel, foreground, 23f).apply {
            contentDescription = "Настройки"; setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(40),dp(if(landscape)32 else 40)))
        content.addView(header)
        val displayHost: LinearLayout
        val keyboardHost: LinearLayout
        if(landscape) {
            val body=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            displayHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            keyboardHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false }
            body.addView(displayHost,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dp(10) })
            body.addView(keyboardHost,LinearLayout.LayoutParams(0,-2,1.2f))
            content.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        } else {
            displayHost=content;keyboardHost=content
        }
        chaosSwitch = Switch(this).apply {
            tag = "chaos_switch"
            text = "Режим хаоса"; textSize = 15f; setTextColor(violet); isChecked = engine.chaos
            setOnCheckedChangeListener { _, value ->
                resetTheatre()
                resetNewEffects()
                roulette?.dismiss(); roulette = null; wheel?.release()
                engine.setChaos(value); render(); save()
            }
        }
        statusView = text(10f,violet).apply { letterSpacing = .08f }
        subtitle = text(13f,muted).apply {
            tag = "previous_expression"; gravity = Gravity.END; setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.START
            contentDescription = "Предыдущее выражение"
        }
        operationView = text(if(landscape)18f else 22f,violet).apply {
            tag = "current_operation"; gravity = Gravity.END; contentDescription = "Текущая операция"
        }
        expressionView = text(42f).apply {
            tag = "expression"
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(true); setHorizontallyScrolling(true)
            contentDescription = "Текущее число или результат"
            setOnLongClickListener {
                if (engine.justEvaluated && engine.answer.isNotEmpty()) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Результат",engine.answer))
                    Toast.makeText(this@MainActivity,"Результат скопирован",Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this@MainActivity,"Сначала вычисли результат",Toast.LENGTH_SHORT).show()
                true
            }
        }
        expressionScroll = HorizontalScrollView(this).apply { isFillViewport = true; isHorizontalScrollBarEnabled = true }
        expressionScroll.addView(expressionView, FrameLayout.LayoutParams(-2,-1))
        // Три текстовых элемента сверху вниз, именно в Compose Column.
        // Compose используется именно для Column с тремя строками. Внутри AndroidView размещает существующие View.
        val display = ComposeView(this).apply {
            tag = "display_column"
            background = rounded(panel,24)
            minimumHeight=dp(if(landscape)96 else 116)
            setPadding(dp(14),dp(4),dp(14),dp(4))
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    AndroidView(factory = { subtitle }, modifier = Modifier.fillMaxWidth().height(22.composeDp))
                    AndroidView(factory = { operationView }, modifier = Modifier.fillMaxWidth().height((if(landscape)24 else 28).composeDp))
                    AndroidView(factory = { expressionScroll }, modifier = Modifier.fillMaxWidth().height((if(landscape)42 else 58).composeDp))
                }
            }
        }
        displayHost.addView(display,LinearLayout.LayoutParams(-1,0,1f))
        val toolbar = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        toolbar.addView(statusView,LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(chaosSwitch,LinearLayout.LayoutParams(-2,dp(if(landscape)32 else 40)))
        displayHost.addView(toolbar)
        messageView = text(12f,muted).apply {
            gravity = Gravity.CENTER_VERTICAL; setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        displayHost.addView(messageView, LinearLayout.LayoutParams(-1,dp(if(landscape)20 else 28)))
        // Кнопки в двух ориентациях расположены по-разному, но обрабатываются одинаковой функцией handle().
        val rows = if(landscape) listOf(
            listOf("C","⌫","±","%","÷"), listOf("7","8","9","×","−"),
            listOf("4","5","6","+","="), listOf("1","2","3","0",".")
        ) else listOf(listOf("C","⌫","±","÷"), listOf("7","8","9","×"),
            listOf("4","5","6","−"), listOf("1","2","3","+"), listOf("%","0",".","="))
        rows.forEach { labels ->
            val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
            labels.forEach { label ->
                val operation = label in listOf("+","−","×","÷")
                val button = key(label, when { label == "=" -> lime; operation -> if(dark) 0xFF302A43.toInt() else 0xFFE5DDF7.toInt(); else -> panel },
                    when { label == "=" -> Color.rgb(24,30,16); operation -> if(dark) violet else 0xFF604290.toInt(); label == "C" -> 0xFFFF8296.toInt(); else -> foreground })
                button.tag = label
                button.contentDescription = when(label) { "⌫" -> "Удалить последний символ"; "C" -> "Очистить"; "±" -> "Сменить знак"; "%" -> "Процент"; else -> label }
                button.setOnClickListener {
                    val tagLabel = button.tag.toString()
                    val label = if (tagLabel.length == 1 && tagLabel[0].isDigit() && button.text.isNotEmpty()) button.text.toString() else tagLabel
                    handle(label,button)
                }
                if (label.length == 1 && label[0].isDigit()) digits += button
                buttons += button
                row.addView(button, LinearLayout.LayoutParams(0,dp(keyHeight),1f).apply {
                    setMargins(dp(3),dp(3),dp(3),dp(3))
                })
            }
            keyboardHost.addView(row)
        }
        fixedClear = text(25f,0xFFFF8296.toInt()).apply {
            tag="fixed_clear"; text="C"; gravity=Gravity.CENTER; visibility=View.GONE
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            contentDescription="Очистить"; isClickable=true
            setOnClickListener { handle("C",this) }
        }
        root.addView(fixedClear,FrameLayout.LayoutParams(dp(48),dp(48)))
        chaosView = ChaosView(this)
        root.addView(chaosView, FrameLayout.LayoutParams(-1,-1))
        percentFlight = PercentFlightView(this)
        root.addView(percentFlight, FrameLayout.LayoutParams(-1,-1))
        sceneView = SceneView(this).apply { tag = "scenes" }
        root.addView(sceneView, FrameLayout.LayoutParams(-1,-1))
        rescue = key("Верни",lime,Color.BLACK,17f).apply {
            tag = "rescue"; visibility = View.GONE
            setOnClickListener {
                if(engine.mode == Mode.BLACK_HOLE) {
                    if(!holeReturning) Toast.makeText(this@MainActivity,"Зажми C, перетащи в центр дыры и отпусти",Toast.LENGTH_SHORT).show()
                } else restoreTheatre()
            }
        }
        root.addView(rescue,FrameLayout.LayoutParams(dp(210),dp(52),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        attachHoleDrag()
        homeButton = key("На место",panel,foreground,13f).apply {
            tag = "move_home"; visibility = View.GONE
            setOnClickListener { resetPosition() }
        }
        root.addView(homeButton,FrameLayout.LayoutParams(dp(92),dp(44),Gravity.BOTTOM or Gravity.END).apply { bottomMargin=dp(10); marginEnd=dp(10) })
        // Даже если старые кнопки существуют в памяти, перехватчик запрещает нажимать их после полного стирания.
        // Непрозрачный для касаний слой перекрывает ВСЕ старые элементы с начала третьего этапа.
        eraseShield = View(this).apply { tag="erase_shield"; isClickable=true; visibility=View.GONE }
        root.addView(eraseShield,FrameLayout.LayoutParams(-1,-1))
        buildEndingCard()
        setContentView(root)
        root.requestApplyInsets()
        root.post { render(); scheduleIdle() }
    }

    /** Единый обработчик нажатий: определяет действие клавиши, передаёт его в CalculatorEngine, запускает эффекты, перерисовывает экран и сохраняет состояние. */
    private fun handle(label: String, source: View) {
        if(jokes.eraseStage == 3) return
        wakeUp(); scheduleIdle()
        if (sceneView.scene == SceneView.Scene.FIGHT && label != "C") return
        if (engine.mode == Mode.BLACK_HOLE) return
        if (label == "C") resetTheatre()
        val wasEmpty = engine.expression.isEmpty()
        val fight = jokes.input(label, wasEmpty, SystemClock.uptimeMillis(),
            engine.chaos && engine.mode in listOf(Mode.NORMAL,Mode.WORD_RESULT))
        // Во время полёта повторный % не создаёт второй эффект.
        if (percentFlight.isRunning && label == "%") return
        if (label != "%") percentFlight.cancel()
        if (source !== inflatedButton || label.length != 1 || !label[0].isDigit()) resetInflation()
        val now = SystemClock.uptimeMillis()
        val effect = tapSequence.record(label, now,
            engine.chaos && engine.mode in listOf(Mode.NORMAL, Mode.WORD_RESULT))
        if (haptics) source.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        // Один when заменяет множество отдельных обработчиков для всех математических клавиш.
        when (label) {
            "C" -> engine.clear()
            "⌫" -> engine.delete()
            "±" -> engine.changeSign()
            "." -> engine.decimal()
            "%" -> engine.percent()
            "=" -> if (effect.equalsWord) engine.showEqualsWord() else engine.equals()?.let { entry ->
                // Спам = не засоряет историю одинаковыми строками вроде «4 = 4».
                if (history.firstOrNull() != entry) history.add(0,entry)
                while(history.size>30) history.removeAt(history.lastIndex)
            }
            "+","−","×","÷" -> engine.operator(label[0])
            else -> if (label.length == 1 && label[0].isDigit()) engine.digit(label[0],now)
        }
        render()
        if (engine.mode == Mode.NORMAL && engine.chaos) {
            if (effect.scale > 1f && source is Button) inflate(source,effect.scale)
            if (effect.percentFlight && source is Button) {
                percentFlight.fly(source, expressionScroll)
                messageView.text = "Процент улетел. Сейчас вернётся."
            }
        } else resetNewEffects()
        if (fight && engine.mode == Mode.NORMAL) startFight()
        save()
    }

    /** Увеличиваем многократно нажимаемую цифру; через паузу откладываем возврат размера. */
    private fun inflate(button: Button, scale: Float) {
        inflatedButton = button
        button.translationZ = dp(16).toFloat()
        (button.parent as? ViewGroup)?.translationZ = dp(12).toFloat()
        button.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        handler.removeCallbacks(deflate)
        handler.postDelayed(deflate, TapSequence.PAUSE_MS)
    }

    /** Возвращаем увеличенной кнопке обычный масштаб и отменяем отложенное уменьшение. */
    private fun resetInflation(animated: Boolean = false) {
        handler.removeCallbacks(deflate)
        val button = inflatedButton ?: return
        inflatedButton = null
        button.animate().cancel()
        button.translationZ = 0f
        (button.parent as? ViewGroup)?.translationZ = 0f
        if (animated) button.animate().scaleX(1f).scaleY(1f).setDuration(280).start()
        else { button.scaleX = 1f; button.scaleY = 1f }
    }

    /** Сбрасываем короткие эффекты и счётчик быстрых нажатий. */
    private fun resetNewEffects() {
        resetInflation()
        tapSequence.reset()
        if (::percentFlight.isInitialized) percentFlight.cancel()
    }

    /** Перезапускаем таймер простоя: через 20 секунд можно запустить сцену сна. */
    private fun scheduleIdle() {
        handler.removeCallbacks(idleTick)
        if(active && ::sceneView.isInitialized) handler.postDelayed(idleTick,20_000L)
    }

    /** Любое прикосновение к экрану будит кнопки и обновляет таймер сна перед обычной обработкой касания. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked == MotionEvent.ACTION_DOWN && ::sceneView.isInitialized) {
            wakeUp(); scheduleIdle()
        }
        return super.dispatchTouchEvent(event)
    }

    /** Снимаем эффект сна и восстанавливаем обычное положение/прозрачность кнопок. */
    private fun wakeUp() {
        if(!sleeping) return
        sleeping = false; sceneView.clear()
        buttons.forEach { b ->
            b.animate().cancel(); b.rotation = 0f; b.alpha = 1f
            b.translationY = -dp(9).toFloat()
            b.animate().translationY(0f).setDuration(220).start()
        }
        messageView.text = "Проснулись! Кто тут считает?"
    }

    /** Останавливаем текущие сцены и анимации, возвращаем клавиши на места и сбрасываем временные блокировки. Нужен при очистке, переключении режима и пересоздании интерфейса. */
    private fun resetTheatre() {
        lastMode = null
        if(::fixedClear.isInitialized) fixedClear.visibility=View.GONE
        buttons.firstOrNull { it.tag=="C" }?.setTextColor(0xFFFF8296.toInt())
        if(::eraseShield.isInitialized) eraseShield.visibility=View.GONE
        handler.removeCallbacks(idleTick); handler.removeCallbacks(overloadTick); handler.removeCallbacks(holeReadyTick)
        returnAnimator?.removeAllListeners(); returnAnimator?.cancel(); returnAnimator=null
        holeReady=false; holeReturning=false
        engine.cancelOverload()
        sleeping = false
        if(::sceneView.isInitialized) sceneView.clear()
        if(::rescue.isInitialized) {
            rescue.animate().cancel(); rescue.animate().setUpdateListener(null)
            rescue.translationX=0f; rescue.translationY=0f; rescue.alpha=1f; rescue.isEnabled=true; rescue.visibility=View.GONE
        }
        if(::homeButton.isInitialized) homeButton.visibility=View.GONE
        if(::endingCard.isInitialized) { endingCard.animate().cancel(); endingCard.visibility=View.GONE }
        if(::contentScroll.isInitialized) {
            contentScroll.animate().cancel(); contentScroll.alpha=1f; contentScroll.translationX=0f; contentScroll.translationY = 0f
            contentScroll.visibility = View.VISIBLE
        }
        buttons.forEach { b ->
            b.animate().cancel(); b.animate().withEndAction(null).setStartDelay(0)
            b.scaleX = 1f; b.scaleY = 1f; b.translationX = 0f; b.translationY = 0f
            b.rotation = 0f; b.alpha = 1f; b.visibility = View.VISIBLE
            if(displayedEraseStage > 0 || b.tag == "+") b.text = b.tag.toString()
        }
        displayedEraseStage = 0; jokes.clear()
    }

    /** Полностью восстанавливаем интерфейс после стирания: сбрасываем эффекты, перерисовываем и сохраняем состояние. */
    private fun restoreTheatre() {
        resetTheatre(); resetNewEffects(); render(); save(); scheduleIdle()
    }

    /** Добавляем перетаскивание калькулятора за верхнюю полоску в режиме хаоса. Сдвигается ScrollView со всеми кнопками. */
    private fun attachInterfaceDrag() {
        dragHandle.setOnTouchListener(DragTouchListener(
            allowed = { engine.chaos && engine.mode in listOf(Mode.NORMAL,Mode.WORD_RESULT) && jokes.eraseStage==0 && sceneView.scene in listOf(SceneView.Scene.NONE,SceneView.Scene.SLEEP) },
            begin = { wakeUp(); moveStartX=contentScroll.translationX; moveStartY=contentScroll.translationY },
            move = { dx,dy ->
                pinClearLetter()
                // Двигается весь калькулятор и фон C. Отдельная буква C закреплена на экране.
                contentScroll.translationX=moveStartX+dx
                contentScroll.translationY=moveStartY+dy
                homeButton.visibility=View.VISIBLE
            },
            finish = { _,_ -> scheduleIdle() }
        ))
    }

    /** Рисуем отдельную букву C в исходной точке экрана, скрывая надпись на уехавшей кнопке. Поэтому фон C двигается, а буква остаётся. */
    private fun pinClearLetter() {
        if(fixedClear.visibility==View.VISIBLE) return
        val button=buttons.first { it.tag=="C" }
        val from=IntArray(2);val origin=IntArray(2)
        button.getLocationInWindow(from);root.getLocationInWindow(origin)
        fixedClear.layoutParams=FrameLayout.LayoutParams(button.width,button.height).apply {
            leftMargin=from[0]-origin[0]-root.paddingLeft
            topMargin=from[1]-origin[1]-root.paddingTop
        }
        button.setTextColor(Color.TRANSPARENT)
        fixedClear.visibility=View.VISIBLE
    }

    /** Возвращаем калькулятор в исходное место и снова показываем C прямо на кнопке. */
    private fun resetPosition() {
        contentScroll.translationX=0f;contentScroll.translationY=0f
        fixedClear.visibility=View.GONE
        buttons.first { it.tag=="C" }.setTextColor(0xFFFF8296.toInt())
        homeButton.visibility=View.GONE
    }

    /** Добавляем жест: спасательная C освобождает интерфейс только после перетаскивания и отпускания внутри чёрной дыры. */
    private fun attachHoleDrag() {
        rescue.setOnTouchListener(DragTouchListener(
            allowed = { engine.mode==Mode.BLACK_HOLE && holeReady && !holeReturning },
            begin = { rescue.animate().cancel(); holeStartX=rescue.translationX;holeStartY=rescue.translationY },
            move = { dx,dy ->
                rescue.translationX=holeStartX+dx;rescue.translationY=holeStartY+dy
                sceneView.updateRope(rescue)
                rescue.text=if(sceneView.catches(rescue)) "Отпускай!" else "Тяни C в дыру"
            },
            finish = { dragged,cancelled ->
                if(dragged && !cancelled && sceneView.catches(rescue)) returnFromHole()
                else {
                    rescue.text="Тяни C в дыру"
                    rescue.animate().translationX(0f).translationY(0f).setDuration(320)
                        .setUpdateListener { if(sceneView.scene==SceneView.Scene.HOLE) sceneView.updateRope(rescue) }.start()
                }
            }
        ))
    }

    /** Создаём финальную карточку после полного стирания интерфейса. Только действие «Верни как было» восстанавливает кнопки. */
    private fun buildEndingCard() {
        endingCard=LinearLayout(this).apply {
            tag="ending_card";orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            background=rounded(panel,26);setPadding(dp(22),dp(24),dp(22),dp(22));visibility=View.GONE
        }
        endingText=text(21f).apply { tag="ending_text";gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD }
        endingCard.addView(endingText,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(18)})
        fun option(label:String,tagName:String,action:()->Unit) {
            endingCard.addView(key(label,if(tagName=="ending_restore")lime else background,if(tagName=="ending_restore")Color.BLACK else foreground,16f).apply {
                tag=tagName;setOnClickListener{action()}
            },LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(8)})
        }
        option("Верни как было","ending_restore") { restoreTheatre() }
        option("Я художник, я так вижу","ending_artist") { endingText.text="Шедевр «Ноль интерфейса».\nОценка: пустота из десяти." }
        option("Это не я","ending_denial") { endingText.text="Ага. А ⌫ девять раз нажал кот.\nСвидетели — все кнопки." }
        root.addView(endingCard,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply { leftMargin=dp(24);rightMargin=dp(24) })
    }

    /** Показываем случайную финальную реплику и плавно выводим карточку. */
    private fun showEnding() {
        val options=endingLines.indices.filter { it!=previousEnding }
        previousEnding=options.random();endingText.text=endingLines[previousEnding]
        endingCard.visibility=View.VISIBLE;endingCard.alpha=0f;endingCard.scaleX=.85f;endingCard.scaleY=.85f
        endingCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(OvershootInterpolator(.7f)).start()
        rescue.visibility=View.GONE
    }

    /** Показываем нужную стадию стирания (0–3), прячем подписи/кнопки и перекрываем касания на последнем этапе. */
    private fun applyTheatreState() {
        val stage=jokes.eraseStage
        eraseShield.visibility=if(stage==3) View.VISIBLE else View.GONE
        if(stage>0) fixedClear.visibility=View.GONE
        dragHandle.text=if(engine.chaos) "⠿ КАЛЬКУЛЯТОР / 05\nТяни за эту полоску" else "КАЛЬКУЛЯТОР / 05"
        if(stage==0 && displayedEraseStage>0) {
            if(sceneView.scene==SceneView.Scene.ERASE) sceneView.clear()
            contentScroll.animate().cancel();contentScroll.alpha=1f;contentScroll.visibility=View.VISIBLE
            endingCard.visibility=View.GONE
            buttons.forEach { b -> b.animate().cancel();b.animate().setStartDelay(0);b.text=b.tag.toString();b.alpha=1f;b.scaleX=1f;b.scaleY=1f;b.rotation=0f }
        }
        if(stage>0) {
            homeButton.visibility=View.GONE
            val targets=buttons.filter { it.tag !in listOf("C","⌫") }
            targets.forEach { it.text=""; if(stage>=2)it.isEnabled=false }
            if(stage==3) buttons.forEach { it.isEnabled=false }
            if(stage!=displayedEraseStage) {
                resetNewEffects()
                sceneView.erase(stage,targets) {
                    if(jokes.eraseStage==3 && stage==3) {
                        contentScroll.visibility=View.INVISIBLE;showEnding()
                    }
                }
                if(stage==2) targets.forEachIndexed { index,b ->
                    b.animate().alpha(0f).scaleX(.12f).scaleY(.12f).rotation(if(index%2==0)18f else -18f)
                        .setStartDelay(index*24L).setDuration(700).start()
                }
                if(stage==3) {
                    contentScroll.animate().alpha(0f).setDuration(SceneView.ERASE_MS).start()
                }
            }
            messageView.text=when(stage) {1->"Буквы рассыпались. Ты точно стираешь число?";2->"Кнопки пошли на конфетти.";else->"Последний взмах ластика…"}
        }
        if(stage==0 && sceneView.scene in listOf(SceneView.Scene.NONE,SceneView.Scene.SLEEP)) {
            homeButton.visibility=if(contentScroll.translationX!=0f || contentScroll.translationY!=0f)View.VISIBLE else View.GONE
        }
        if(stage==0)rescue.visibility=View.GONE
        else if(stage<3 && endingCard.visibility!=View.VISIBLE)rescue.visibility=View.VISIBLE
        else rescue.visibility=View.GONE
        rescue.text=if(stage>=3) "Стой! Верни всё" else "Вернуть интерфейс"
        displayedEraseStage=stage
    }

    /** Запускаем анимацию чёрной дыры после деления на ноль: кнопки притягиваются к её центру. */
    private fun startBlackHole() {
        resetNewEffects();resetTheatre()
        rescue.text="Сейчас всё затянет…";rescue.visibility=View.VISIBLE
        messageView.text="Потом затащи C в центр дыры и отпусти."
        rescue.post {
            if(engine.mode!=Mode.BLACK_HOLE || !active)return@post
            sceneView.hole(rescue)
            buttons.forEachIndexed { index,b ->
                val center=sceneView.centerOf(b)
                b.animate().translationX(sceneView.holeX-center.x).translationY(sceneView.holeY-center.y)
                    .scaleX(.02f).scaleY(.02f).rotation(320f).alpha(0f)
                    .setStartDelay(index*22L).setDuration(1500).start()
            }
            handler.postDelayed(holeReadyTick,2000)
        }
    }

    /** Анимируем возвращение кнопок из дыры; когда анимация закончится, очищаем выражение и восстанавливаем экран. */
    private fun returnFromHole() {
        if(holeReturning || !holeReady)return
        holeReturning=true;holeReady=false;rescue.isEnabled=false;rescue.visibility=View.INVISIBLE
        messageView.text="Вызываем математику обратно!"
        sceneView.returnHole()
        val starts=buttons.map { b ->
            b.animate().cancel();floatArrayOf(b.translationX,b.translationY,b.scaleX,b.rotation)
        }
        val spring=OvershootInterpolator(.85f)
        returnAnimator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=SceneView.RETURN_MS
            interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener { animation ->
                val global=animation.animatedValue as Float
                buttons.forEachIndexed { index,b ->
                    val t=((global-index*.009f)/.78f).coerceIn(0f,1f)
                    val ease=spring.getInterpolation(t);val from=starts[index]
                    val arc=sin(t*PI).toFloat()
                    b.translationX=from[0]*(1-ease)+arc*dp(if(index%2==0)38 else -38)
                    b.translationY=from[1]*(1-ease)-arc*dp(65)
                    b.scaleX=(from[2]+(1-from[2])*ease).coerceAtLeast(.02f);b.scaleY=b.scaleX
                    b.rotation=from[3]*(1-ease);b.alpha=(t*5).coerceIn(0f,1f)
                }
            }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation:Animator) {
                    engine.clear();restoreTheatre()
                }
            });start()
        }
    }

    /** Запускаем короткую сцену столкновения кнопок + и −, на время отключая остальные клавиши кроме C. */
    private fun startFight() {
        if(sceneView.scene!=SceneView.Scene.NONE)return
        resetNewEffects()
        val plus=buttons.first { it.tag=="+" };val minus=buttons.first { it.tag=="−" }
        plus.alpha=0f;minus.alpha=0f
        buttons.forEach { it.isEnabled=it.tag=="C" }
        homeButton.visibility=View.GONE
        messageView.text="Пять секунд на выяснение отношений. C — разнять."
        sceneView.fight(plus,minus) { render();scheduleIdle() }
    }

    /** Главная функция обновления экрана: получает данные Display от движка, выводит число/операцию/историю, меняет доступность клавиш и включает сцену по состоянию Mode. */
    private fun render() {
        // При смене режима удаляем анимации предыдущего состояния, чтобы эффекты не накладывались друг на друга.
        val changed = lastMode != engine.mode
        if (changed) {
            handler.removeCallbacks(overloadTick)
            if (engine.mode != Mode.BLACK_HOLE && sceneView.scene in listOf(SceneView.Scene.HOLE,SceneView.Scene.HOLE_RETURN)) resetTheatre()
            handler.removeCallbacks(thinkingTick)
            stopShaking()
            if (engine.mode != Mode.COLLAPSED) chaosView.clear()
            if (engine.mode !in listOf(Mode.ROULETTE_ARMED,Mode.SPINNING)) restoreDigitLabels()
        }
        expressionView.visibility = View.VISIBLE
        val display=engine.display()
        expressionView.text = display.current
        subtitle.text = display.previous
        operationView.text = display.operation
        expressionView.textSize = when { engine.mode == Mode.THINKING -> 24f; display.current.length > 18 -> 18f; display.current.length > 11 -> 26f; resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> 32f; else -> 42f }
        expressionScroll.post {
            expressionView.minimumWidth = expressionScroll.width
            expressionScroll.fullScroll(View.FOCUS_RIGHT)
        }
        messageView.text = engine.message
        statusView.text = when(engine.mode) {
            Mode.NORMAL -> if(engine.chaos) "● СТАБИЛЬНО. ПОКА." else "● ОБЫЧНЫЙ РЕЖИМ"
            Mode.LOCKED -> "■ ОШИБКА 33"
            Mode.THINKING -> "◌ ВЕЧНЫЙ РАСЧЁТ"
            Mode.COLLAPSED -> "↓ КРЕПЛЕНИЯ НЕ ВЫДЕРЖАЛИ"
            Mode.ROULETTE_ARMED, Mode.SPINNING -> "★ СУДЬБА ВЫБИРАЕТ ЦИФРУ"
            Mode.WORD_RESULT -> "= РАВНО. ТЕКСТОМ."
            Mode.UNSTABLE -> "⚠ КРЕПЛЕНИЯ ТРЕЩАТ"
            Mode.BLACK_HOLE -> "● ДЕЛЕНИЕ НА НОЛЬ"
        }
        statusView.setTextColor(if(engine.mode == Mode.LOCKED) 0xFFFF8296.toInt() else if(dark) violet else 0xFF604290.toInt())
        val locked = engine.mode in listOf(Mode.LOCKED,Mode.THINKING,Mode.COLLAPSED,Mode.SPINNING,Mode.BLACK_HOLE)
        buttons.forEach {
            it.isEnabled = jokes.eraseStage<3 && engine.mode != Mode.BLACK_HOLE && (!locked || it.tag == "C" || it.tag == "⌫") && (sceneView.scene != SceneView.Scene.FIGHT || it.tag == "C")
            if((engine.mode != Mode.BLACK_HOLE || changed) && jokes.eraseStage<2) it.alpha = if(sceneView.scene==SceneView.Scene.FIGHT && it.tag in listOf("+","−")) 0f else if(it.isEnabled) 1f else .38f
        }
        when(engine.mode) {
            Mode.COLLAPSED -> {
                if (changed) expressionView.post {
                    if(engine.mode == Mode.COLLAPSED) { chaosView.collapse(expressionView); expressionView.visibility = View.INVISIBLE }
                } else expressionView.visibility = View.INVISIBLE
            }
            Mode.THINKING -> {
                expressionView.text = "Думаю… 98%"
                if (changed && active) handler.post(thinkingTick)
            }
            Mode.ROULETTE_ARMED -> if(changed) shuffleDigits()
            Mode.SPINNING -> if(roulette == null && active) showRoulette()
            Mode.NORMAL -> if(engine.chaos && engine.heat >= CalculatorEngine.SHAKE_TAPS) shake()
            Mode.UNSTABLE -> {
                shake()
                if(changed) handler.postDelayed(overloadTick,CalculatorEngine.WARNING_MS)
            }
            Mode.BLACK_HOLE -> if(changed) startBlackHole()
            else -> Unit
        }
        if(engine.mode != Mode.BLACK_HOLE) applyTheatreState()
        lastMode = engine.mode
    }

    /** После комбинации 777 меняем надписи на десяти цифровых клавишах в случайном порядке. */
    private fun shuffleDigits() {
        val shuffled = digitOrder.shuffled()
        digits.forEachIndexed { index, button ->
            button.text = shuffled[index]; button.contentDescription = shuffled[index]
            button.animate().rotationBy(360f).setDuration(500).start()
        }
    }
    /** Возвращаем цифрам исходные надписи и отменяем их поворот. */
    private fun restoreDigitLabels() {
        digits.forEachIndexed { index, button ->
            button.animate().cancel(); button.rotation = 0f
            button.text = button.tag.toString(); button.contentDescription = button.tag.toString()
        }
    }
    /** Запускаем горизонтальную дрожь числа при слишком быстром вводе. */
    private fun shake() {
        if(shaker == null) {
            shaker = ObjectAnimator.ofFloat(expressionView,"translationX",0f,dp(3).toFloat(),-dp(3).toFloat(),0f).apply {
                duration = 90; repeatCount = ValueAnimator.INFINITE; start()
            }
        }
        handler.removeCallbacks(settleShake); handler.postDelayed(settleShake,900)
    }
    /** Останавливаем дрожь и возвращаем текст в нормальное положение. */
    private fun stopShaking() {
        shaker?.cancel(); shaker = null
        if(::expressionView.isInitialized) expressionView.translationX = 0f
        handler.removeCallbacks(settleShake)
    }

    /** Показываем диалог рулетки. После кнопки СТОП выбранная цифра попадёт в движок через rouletteResult(). */
    private fun showRoulette() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)) }
        val currentWheel = RouletteView(this); wheel = currentWheel
        body.addView(currentWheel, LinearLayout.LayoutParams(-1,dp(260)))
        val stop = key("СТОП",lime,Color.BLACK,20f)
        body.addView(stop, LinearLayout.LayoutParams(-1,dp(54)))
        val dialog = AlertDialog.Builder(this).setTitle("777 / Цифра судьбы")
            .setMessage("Нажми СТОП. Цифра под стрелкой попадёт в выражение.")
            .setView(body).setNegativeButton("Очистить") { _, _ -> engine.clear(); render(); save() }
            .setCancelable(false).create()
        roulette = dialog
        stop.setOnClickListener {
            stop.isEnabled = false; stop.text = "Судьба тормозит…"
            currentWheel.stop { result ->
                engine.rouletteResult(result)
                dialog.dismiss(); render(); save()
            }
        }
        dialog.setOnDismissListener { currentWheel.release(); roulette = null; wheel = null }
        dialog.show(); currentWheel.spin()
    }

    /** Показываем последние 30 успешных вычислений; нажатие копирует запись. */
    private fun showHistory() {
        if(jokes.eraseStage==3) return
        resetTheatre(); render()
        resetNewEffects()
        if(history.isEmpty()) { AlertDialog.Builder(this).setTitle("История пуста").setMessage("Здесь будут последние 30 успешных вычислений.").setPositiveButton("Понятно",null).show(); return }
        AlertDialog.Builder(this).setTitle("История · нажми, чтобы скопировать")
            .setItems(history.toTypedArray()) { _, index ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Вычисление",history[index]))
                Toast.makeText(this,"Скопировано",Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Закрыть",null).setNeutralButton("Очистить") { _, _ -> history.clear(); save() }.show()
    }

    /** Открываем настройки темы и виброотклика; после изменения пересоздаём интерфейс. */
    private fun showSettings() {
        if(jokes.eraseStage==3) return
        resetTheatre(); render()
        resetNewEffects()
        AlertDialog.Builder(this).setTitle("Настройки")
            .setMultiChoiceItems(arrayOf("Тёмная тема","Отклик кнопок"), booleanArrayOf(dark,haptics)) { _, index, checked ->
                if(index==0) dark=checked else haptics=checked
            }.setPositiveButton("Готово") { _, _ -> save(); buildUi() }
            .setNeutralButton("Пасхалки") { _, _ -> save(); buildUi(); showHelp() }
            .setOnCancelListener { save(); buildUi() }.show()
    }
    /** Показываем встроенную справку по пасхалкам и управлению. */
    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("Как всё сломать")
            .setMessage("• ⌫ на пустом экране: 3 нажатия стирают подписи, 6 — кнопки, 9 — весь интерфейс. Ластик и конфетти сопровождают стирание; в конце выбери ответ. Только надпись «Верни как было» восстанавливает интерфейс. Невидимые кнопки отключены.\n\n• Деление на ноль открывает чёрную дыру. Когда клавиши исчезнут, зажми C на верёвке, затащи в центр дыры и отпусти: клавиши вылетят обратно.\n\n• В режиме хаоса перетаскивай интерфейс за верхнюю полоску. Буква C остаётся на месте, её фон уезжает вместе с калькулятором. «На место» возвращает всё. Отрицательные ответы больше ничего не двигают.\n\n• Шесть быстрых чередований + и − — цветная драка на 5,2 секунды. Только C прерывает её; остальные клавиши ждут.\n\n• Через 20 секунд бездействия кнопки засыпают. Первое касание будит их и выполняет действие.\n\n• Одна повторяемая цифра надувается; пять % за 3 секунды запускают полёт; шесть = за 3 секунды показывают «равно».\n\n• Максимум 300 символов. После 80 цифр в одном числе или 30 цифр за 3 секунды сначала идёт предупреждение 1,8 секунды. Пауза спасает от падения из-за скорости; ⌫, знак операции или C тоже отменяют предупреждение.\n\n• 33 и операции между двумя тройками блокируют ввод; вычисленный ответ с 33 не блокируется. Удали комбинацию или нажми C.\n\n• 6+7, 6×7, 6÷7 и = — вечное «Думаю».\n\n• 777 перемешивает клавиши: следующая цифра выпадет из рулетки.\n\nВ обычном режиме все приколы отключены. Проценты: 50% → 0.5; 200+10% = 220.")
            .setPositiveButton("Поехали",null).show()
    }

    /** Записываем настройки, выражение, режим, историю и стадию стирания в SharedPreferences. */
    private fun save() {
        prefs.edit().putBoolean("dark",dark).putBoolean("haptics",haptics).putBoolean("chaos",engine.chaos)
            .putString("expression",engine.expression).putString("mode",engine.mode.name)
            .putString("previous",engine.previousExpression).putInt("generated",engine.generatedPrefixLength)
            .putInt("erase_stage",if(jokes.eraseStage==3)3 else 0)
            .putBoolean("evaluated",engine.justEvaluated).putString("history",JSONArray(history).toString()).apply()
    }
    /** Сохраняем ключевые данные во временный Bundle, чтобы не потерять их при пересоздании Activity. */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("expression",engine.expression); outState.putString("mode",engine.mode.name)
        outState.putBoolean("evaluated",engine.justEvaluated)
        outState.putString("previous",engine.previousExpression);outState.putInt("generated",engine.generatedPrefixLength)
        outState.putInt("erase_stage",if(jokes.eraseStage==3)3 else 0)
        super.onSaveInstanceState(outState)
    }
    /** При возвращении приложения продолжаем обновление экрана и безопасно восстанавливаем финальный экран стирания. */
    override fun onResume() {
        super.onResume(); active = true
        if(::expressionView.isInitialized) {
            if(engine.mode == Mode.THINKING) { handler.removeCallbacks(thinkingTick); handler.post(thinkingTick) }
            lastMode = null; render()
            if(jokes.eraseStage==3) {
                sceneView.clear();contentScroll.animate().cancel()
                contentScroll.visibility=View.INVISIBLE;showEnding()
            }
            scheduleIdle()
        }
    }
    /** При уходе в фон останавливаем временные анимации и сохраняем состояние. */
    override fun onPause() {
        val erased=jokes.eraseStage==3
        resetTheatre()
        if(erased) jokes.restoreStage(3)
        resetNewEffects()
        active = false; handler.removeCallbacks(thinkingTick); stopShaking()
        if(engine.mode == Mode.SPINNING) { engine.cancelRoulette(); roulette?.dismiss(); lastMode = null }
        save(); super.onPause()
    }
    /** Окончательно освобождаем таймеры, анимации и диалоги, чтобы не оставлять незавершённую работу после закрытия Activity. */
    override fun onDestroy() {
        resetTheatre()
        resetNewEffects()
        handler.removeCallbacksAndMessages(null); stopShaking(); wheel?.release(); roulette?.dismiss()
        super.onDestroy()
    }
}
