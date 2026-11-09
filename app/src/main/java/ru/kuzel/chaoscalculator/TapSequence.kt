package ru.kuzel.chaoscalculator

data class TapEffect(val scale: Float = 1f, val percentFlight: Boolean = false, val equalsWord: Boolean = false)

/** Счётчик только последовательных нажатий. Никаких таймеров и Android-зависимостей. */
class TapSequence {
    companion object {
        const val PAUSE_MS = 900L
        const val BURST_MS = 3000L
        const val PERCENT_TAPS = 5
        const val EQUALS_TAPS = 6
    }
    private var key = ""
    private var count = 0
    private var last = 0L
    private var start = 0L

    /** Обнуляем серию повторяющихся нажатий. */
    fun reset() { key = ""; count = 0; last = 0; start = 0 }

    /** Обновляем серию нажатий и возвращаем описание эффекта: масштаб кнопки, полёт процента или слово «равно». */
    fun record(label: String, now: Long, enabled: Boolean): TapEffect {
        val digit = label.length == 1 && label[0] in '0'..'9'
        if (!enabled || (!digit && label != "%" && label != "=")) { reset(); return TapEffect() }
        if (key != label || now < last || now - last > PAUSE_MS || (!digit && now - start > BURST_MS)) {
            count = 0; start = now
        }
        key = label; last = now; count++
        return when {
            digit -> TapEffect(scale = (1f + (count - 1) * .18f).coerceAtMost(2.2f))
            label == "%" && count >= PERCENT_TAPS -> { count = 0; start = now; TapEffect(percentFlight = true) }
            label == "=" && count >= EQUALS_TAPS -> { count = 0; start = now; TapEffect(equalsWord = true) }
            else -> TapEffect()
        }
    }
}
