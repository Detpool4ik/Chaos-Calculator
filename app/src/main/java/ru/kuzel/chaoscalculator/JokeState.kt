package ru.kuzel.chaoscalculator

/** Небольшие счётчики для новых сцен. Чистый Kotlin, без анимаций и Android. */
class JokeState {
    // 0 — целый интерфейс; 1 — убираем подписи; 2 — убираем кнопки; 3 — всё исчезло.
    var eraseStage = 0
        private set
    private var emptyDeletes = 0
    private var lastOperator = ""
    private var switches = 0
    private var lastSwitch = 0L
    private var firstSwitch = 0L

    /** Полностью сбрасываем счётчики стирания и драки клавиш. */
    fun clear() { restoreInterface(); resetFight() }
    /** Возвращаем нулевую стадию стирания интерфейса. */
    fun restoreInterface() { emptyDeletes = 0; eraseStage = 0 }
    /** Восстанавливаем стадию стирания 0–3 из сохранённого состояния. */
    fun restoreStage(stage: Int) { eraseStage = stage.coerceIn(0, 3); emptyDeletes = eraseStage * 3 }
    /** Сбрасываем счётчик чередования + и −. */
    private fun resetFight() { lastOperator = ""; switches = 0; lastSwitch = 0L; firstSwitch = 0L }

    /** Обрабатываем очередное нажатие для пасхалок: на пустом поле ⌫ повышает стадию стирания, а быстрое чередование +/− запускает драку. Возвращает true при запуске драки. */
    fun input(label: String, empty: Boolean, now: Long, enabled: Boolean): Boolean {
        if (eraseStage == 3) return false
        if (!enabled) { restoreInterface(); resetFight(); return false }
        if (label == "⌫" && empty) {
            emptyDeletes++
            eraseStage = (emptyDeletes / 3).coerceAtMost(3)
        } else restoreInterface()
        if (label !in listOf("+", "−")) { resetFight(); return false }
        if (label == lastOperator || now - lastSwitch > 900L || now - firstSwitch > 3000L || now < lastSwitch) {
            switches = 0; firstSwitch = now
        }
        switches++; lastSwitch = now; lastOperator = label
        if (switches >= 6) { resetFight(); return true }
        return false
    }

}
