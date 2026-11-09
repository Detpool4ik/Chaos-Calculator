package ru.kuzel.chaoscalculator

import java.math.BigDecimal
import java.math.MathContext
import java.util.ArrayDeque

/** Логика не зависит от Android: её можно проверить обычными unit-тестами. */
enum class Mode { NORMAL, LOCKED, THINKING, COLLAPSED, ROULETTE_ARMED, SPINNING, WORD_RESULT, UNSTABLE, BLACK_HOLE }

class DivideByZeroException : IllegalArgumentException("На ноль делить нельзя. Даже здесь.")

class CalculatorEngine {
    // Текущая строка ввода, например «5+10×2». Только методы движка могут её изменять.
    var expression = ""
        private set
    // Выражение до последнего = и длина вычисленного (не набранного) префикса.
    var previousExpression = ""
        private set
    var generatedPrefixLength = 0
        private set
    var answer = ""
        private set
    var message = "Всё под контролем. Пока."
        private set
    // Состояние калькулятора: обычный ввод, блокировка, рулетка, чёрная дыра и т. п.
    var mode = Mode.NORMAL
        private set
    // Флаг пасхалок. При выключенном хаосе арифметика продолжает работать.
    var chaos = true
        private set
    var justEvaluated = false
        private set
    var heat = 0
        private set
    // Храним времена последних нажатий для проверки 14/30 быстрых цифр.
    private val taps = ArrayDeque<Long>()
    private var overloadDeadline = 0L
    private var lastDigitTime = 0L
    private var longOverload = false

    companion object {
        const val FAST_TAPS = 30
        const val SHAKE_TAPS = 14
        const val WINDOW_MS = 3000L
        const val WARNING_MS = 1800L
        const val LONG_NUMBER = 80
        const val MAX_LENGTH = 300
        private val threePair = Regex("(?<![0-9.])3[+×÷−]3(?![0-9.])")
    }

    /** Включаем/выключаем режим хаоса и сбрасываем старые эффекты. Числовое выражение при этом не стирается. */
    fun setChaos(enabled: Boolean) {
        cancelOverload()
        dismissWord()
        chaos = enabled
        mode = Mode.NORMAL
        taps.clear()
        heat = 0
        message = if (enabled) "Хаос включён. Ты сам этого хотел." else "Обычный калькулятор. Можно доверять."
        if (enabled) checkSpecials()
    }

    /** Обработка клавиши C: полностью очищаем ввод, результат и состояния ошибок. */
    fun clear() {
        cancelOverload()
        expression = ""
        previousExpression = ""
        generatedPrefixLength = 0
        answer = ""
        justEvaluated = false
        mode = Mode.NORMAL
        taps.clear()
        heat = 0
        message = if (chaos) "Улик нет. Можно начинать заново." else "Готов к вычислениям."
    }

    /** Обработка ⌫: убираем последний символ, сбрасываем ошибку/блокировку и перепроверяем запрещённые комбинации. */
    fun delete() {
        if (mode == Mode.SPINNING) return
        cancelOverload()
        expression = expression.dropLast(1)
        generatedPrefixLength = minOf(generatedPrefixLength, expression.length)
        previousExpression = ""
        answer = ""
        justEvaluated = false
        mode = Mode.NORMAL
        taps.clear()
        heat = 0
        message = "Удалено. Дышим спокойно."
        checkSpecials()
    }

    /** Добавляем введённую цифру; проверяем предел длины, комбинацию 33, рулетку и скорость набора (перегрузку). now — время касания в миллисекундах. */
    fun digit(digit: Char, now: Long) {
        require(digit in '0'..'9')
        dismissWord()
        if (mode == Mode.ROULETTE_ARMED) {
            if (expression.length >= MAX_LENGTH) { message = "Максимум — $MAX_LENGTH символов в выражении."; return }
            mode = Mode.SPINNING
            message = "Следующую цифру решает судьба."
            return
        }
        if (mode !in listOf(Mode.NORMAL, Mode.UNSTABLE)) return
        if (justEvaluated) { expression = ""; previousExpression = ""; generatedPrefixLength = 0; justEvaluated = false }
        if (expression.length >= MAX_LENGTH) { message = "Максимум — $MAX_LENGTH символов в выражении."; return }
        expression += digit
        answer = ""
        checkSpecials()
        if (!chaos || mode !in listOf(Mode.NORMAL, Mode.UNSTABLE)) return
        lastDigitTime = now
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first > WINDOW_MS) taps.removeFirst()
        heat = taps.size
        val current = expression.substringAfterLast('+').substringAfterLast('−')
            .substringAfterLast('×').substringAfterLast('÷')
        if (heat >= FAST_TAPS || current.count { it.isDigit() } > LONG_NUMBER) {
            longOverload = current.count { it.isDigit() } > LONG_NUMBER
            if (mode != Mode.UNSTABLE) overloadDeadline = now + WARNING_MS
            mode = Mode.UNSTABLE
            message = if (longOverload) "Число тяжёлое! Удали лишние цифры или нажми знак операции."
                else "Крепления трещат! Сделай паузу, чтобы спасти цифры."
        } else if (heat >= SHAKE_TAPS) {
            message = "Помедленнее. Крепления уже трещат."
        }
    }

    /** Проверяется после предупреждения. Пауза при быстром вводе предотвращает падение. */
    fun finishOverload(now: Long) {
        if (mode != Mode.UNSTABLE || now < overloadDeadline) return
        if (longOverload || now - lastDigitTime < 900L) {
            mode = Mode.COLLAPSED
            message = "Цифры не выдержали. Нажми C, убери эту кашу."
        } else {
            cancelOverload()
            message = "Успели. Цифры остаются на своих местах."
        }
    }

    /** Сбрасываем состояние перегрузки, время предупреждения и счётчик быстрых нажатий. */
    fun cancelOverload() {
        if (mode == Mode.UNSTABLE) mode = Mode.NORMAL
        overloadDeadline = 0L; longOverload = false; taps.clear(); heat = 0
    }

    /** После остановки рулетки вставляем выпавшую цифру в выражение ровно один раз. */
    fun rouletteResult(value: Int) {
        if (mode != Mode.SPINNING) return
        if (expression.length < MAX_LENGTH) expression += value.coerceIn(0, 9).toString()
        answer = ""
        mode = Mode.NORMAL
        taps.clear()
        heat = 0
        message = "Выпало $value. Претензии — к судьбе."
        // Не активируем те же 777 повторно. Новая последовательность запускает новую рулетку.
        checkSpecials(roulette = false)
    }

    /** Если диалог рулетки закрыт без выбора, возвращаем режим ожидания следующей цифры. */
    fun cancelRoulette() {
        if (mode == Mode.SPINNING) { mode = Mode.ROULETTE_ARMED; message = "777 ждёт следующую цифру." }
    }

    /** Добавляем знак +, −, × или ÷; если в конце уже есть знак, заменяем его, а не создаём два подряд. */
    fun operator(op: Char) {
        cancelOverload()
        dismissWord()
        if (mode !in listOf(Mode.NORMAL, Mode.ROULETTE_ARMED)) return
        require(op in "+−×÷")
        if (expression.isEmpty()) { justEvaluated = false; if (op == '−') expression = "−"; return }
        if (expression == "−") return
        if (justEvaluated) generatedPrefixLength = expression.length
        previousExpression = ""
        if (expression.last() in "+−×÷") expression = expression.dropLast(1) + op
        else if (expression.length < MAX_LENGTH) expression += op
        justEvaluated = false
        answer = ""
        // Рулетка остаётся вооружённой и после знака операции.
    }

    /** Добавляем десятичную точку; в одном числе разрешена только одна точка. */
    fun decimal() {
        cancelOverload()
        dismissWord()
        if (mode != Mode.NORMAL) return
        if (justEvaluated) { expression = ""; previousExpression = ""; generatedPrefixLength = 0; justEvaluated = false }
        if (expression.length >= MAX_LENGTH - 1) return
        val current = expression.takeLastWhile { it.isDigit() || it == '.' }
        if ('.' in current) return
        expression += if (current.isEmpty()) "0." else "."
        answer = ""
    }

    /** Меняем знак значения всего выражения через вычисление и отрицание результата. */
    fun changeSign() {
        cancelOverload()
        dismissWord()
        if (mode != Mode.NORMAL || expression.isEmpty()) return
        // Меняем знак всего значения, не усложняя ввод скобками.
        try {
            val next = format(Evaluator.evaluate(expression).negate()).replace('-', '−')
            if (next.length > MAX_LENGTH) { message = "Для знака минус не хватает места: максимум $MAX_LENGTH символов."; return }
            expression = next
            generatedPrefixLength = next.length
            previousExpression = ""
            justEvaluated = false
            answer = ""
            checkSpecials()
        } catch (_: IllegalArgumentException) { message = "Сначала закончи выражение." }
    }

    /** Процент текущего числа. После +/− берём процент от левой части. */
    fun percent() {
        cancelOverload()
        dismissWord()
        if (mode != Mode.NORMAL) return
        if (expression.isEmpty()) expression = "0"
        val tail = expression.takeLastWhile { it.isDigit() || it == '.' }
        if (tail.isEmpty()) { message = "Сначала введи число для процента."; return }
        try {
            val prefix = expression.dropLast(tail.length)
            var value = tail.toBigDecimal().movePointLeft(2)
            if (prefix.length > 1 && prefix.last() in "+−") {
                value = Evaluator.evaluate(prefix.dropLast(1)).multiply(value)
            }
            val next = prefix + format(value).replace('-', '−')
            if (next.length > MAX_LENGTH) { message = "Для такого маленького процента уже не хватает строки."; return }
            expression = next
            generatedPrefixLength = next.length
            previousExpression = ""
            answer = ""
            justEvaluated = false
            taps.clear(); heat = 0
            message = "Процент посчитан. Пока без фокусов."
            checkSpecials()
        } catch (_: IllegalArgumentException) { message = "Сначала закончи выражение." }
    }

    /** Слово отображается отдельно: текст никогда не попадает в арифметический парсер. */
    fun showEqualsWord() {
        if (!chaos || mode !in listOf(Mode.NORMAL, Mode.WORD_RESULT)) return
        mode = Mode.WORD_RESULT
        answer = "равно"
        justEvaluated = true
        taps.clear(); heat = 0
        message = "Ты просил равно. Вот тебе равно."
    }

    /** Возвращаем обычное числовое состояние после текстовой пасхалки. */
    private fun dismissWord() {
        if (mode == Mode.WORD_RESULT) {
            mode = Mode.NORMAL
            answer = expression
            message = "Ладно, возвращаемся к цифрам."
        }
    }

    /** Возвращает запись истории только при успешном вычислении. */
    fun equals(): String? {
        cancelOverload()
        if (mode == Mode.WORD_RESULT) return null
        if (mode !in listOf(Mode.NORMAL, Mode.ROULETTE_ARMED)) return null
        if (expression.isEmpty()) return null
        if (chaos && Regex("0*6(?:\\.0+)?[+×÷]0*7(?:\\.0+)?").matches(expression)) {
            mode = Mode.THINKING
            message = "Думаю… Не торопи."
            answer = ""
            return null
        }
        return try {
            val original = expression
            val calculated = format(Evaluator.evaluate(expression)).replace('-', '−')
            require(calculated.length <= MAX_LENGTH) { "Ответ длиннее $MAX_LENGTH символов. Уменьши числа." }
            previousExpression = original
            answer = calculated
            expression = answer
            generatedPrefixLength = answer.length
            justEvaluated = true
            mode = Mode.NORMAL
            taps.clear()
            heat = 0
            message = "Посчитано. Даже правильно."
            checkSpecials()
            "$original = $answer"
        } catch (e: IllegalArgumentException) {
            answer = ""
            if (e is DivideByZeroException && chaos) mode = Mode.BLACK_HOLE
            message = e.message ?: "Проверь выражение."
            null
        }
    }

    /** Возвращаем сохранённое выражение и режим после поворота/перезапуска. Ввод очищается от неожиданных символов. */
    fun restore(text: String, savedMode: String, evaluated: Boolean, previous: String = "", generated: Int = if (evaluated) text.length else 0) {
        expression = text.take(MAX_LENGTH).filter { it.isDigit() || it in ".+−×÷" }
        previousExpression = previous.take(MAX_LENGTH).filter { it.isDigit() || it in ".+−×÷" }
        generatedPrefixLength = generated.coerceIn(0, expression.length)
        justEvaluated = evaluated
        answer = if (evaluated) expression else ""
        mode = if (chaos) runCatching { Mode.valueOf(savedMode) }.getOrDefault(Mode.NORMAL) else Mode.NORMAL
        if (mode == Mode.SPINNING) mode = Mode.ROULETTE_ARMED
        if (mode == Mode.UNSTABLE) mode = Mode.NORMAL
        if (mode == Mode.WORD_RESULT) { answer = "равно"; justEvaluated = true }
        // Миграция старого сохранения: результат 33 больше не является блокировкой.
        if (mode == Mode.LOCKED && evaluated) mode = Mode.NORMAL
        message = when (mode) {
            Mode.WORD_RESULT -> "Ты просил равно. Вот тебе равно."
            Mode.LOCKED -> "33. Дальше сам. Удали тройку или нажми C."
            Mode.THINKING -> "Думаю… Не торопи."
            Mode.COLLAPSED -> "Цифры не выдержали. Нажми C."
            Mode.ROULETTE_ARMED -> "777: следующая цифра — из рулетки."
            Mode.BLACK_HOLE -> "Деление на ноль открыло дыру. C держится на верёвке!"
            else -> "Продолжаем с того же места."
        }
    }

    /** Проверяем специальные комбинации только в ручном вводе. Вычисленный результат (например, 11×3=33) не должен включать блокировку. */
    private fun checkSpecials(roulette: Boolean = true) {
        if (!chaos || justEvaluated) return
        // Важно: проверяем только набранный пользователем хвост, а не автоматически полученный результат.
        val typed = expression.drop(generatedPrefixLength)
        when {
            isForbidden(typed) -> lock()
            roulette && typed.endsWith("777") -> {
                mode = Mode.ROULETTE_ARMED
                message = "777. Кнопки перепутаны. Нажми любую цифру."
            }
        }
    }

    data class Display(val previous: String, val operation: String, val current: String)

    /** Разделяем данные для трёх строк дисплея: прошлое выражение, знак операции, текущее число/ответ. */
    fun display(): Display {
        fun spaced(value: String) = value.replace(Regex("([+−×÷])"), " $1 ").trim()
        if (justEvaluated) return Display(spaced(previousExpression), "", if (mode == Mode.WORD_RESULT) "равно" else expression.ifEmpty { "0" })
        val operation = expression.indices.lastOrNull { i ->
            i > 0 && expression[i] in "+−×÷" && expression[i-1] !in "+−×÷"
        } ?: -1
        return if (operation < 0) Display("", "", expression.ifEmpty { "0" })
        else Display(spaced(expression.take(operation+1)), expression[operation].toString(), expression.drop(operation+1).ifEmpty { "0" })
    }

    /** Проверяем запрещённые комбинации: две тройки подряд или 3 со знаком операции и ещё 3. */
    private fun isForbidden(text: String) = "33" in text || threePair.containsMatchIn(text)
    /** Переводим калькулятор в режим блокировки из-за введённых троек. */
    private fun lock() { mode = Mode.LOCKED; message = "33. Дальше сам. Удали тройку или нажми C." }
    /** Убираем лишние нули после десятичной точки и получаем обычную запись числа без экспоненты. */
    private fun format(number: BigDecimal): String = number.stripTrailingZeros().toPlainString()
}

/** Рекурсивный разбор: умножение и деление выполняются раньше сложения. */
object Evaluator {
    // До 80 значащих цифр при делении; BigDecimal нужен, чтобы избежать типичных погрешностей Double.
    private val precision = MathContext(80)

    /** Вычисляем арифметическое выражение без округления обычным Float/Double: BigDecimal и разбор с приоритетом ×/÷. */
    fun evaluate(expression: String): BigDecimal {
        val source = expression.replace('−', '-').replace('×', '*').replace('÷', '/')
        var index = 0
        /** Внутренняя функция парсера: читает очередное число, включая возможный минус в начале. */
        fun number(): BigDecimal {
            val start = index
            if (index < source.length && source[index] == '-') index++
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
            return source.substring(start, index).toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Закончи выражение — после знака нужна цифра.")
        }
        /** Внутренняя функция парсера: сначала выполняет цепочку умножений и делений. */
        fun product(): BigDecimal {
            var result = number()
            while (index < source.length && source[index] in "*/") {
                val operation = source[index++]
                val next = number()
                if (operation == '/' && next.compareTo(BigDecimal.ZERO) == 0)
                    throw DivideByZeroException()
                result = if (operation == '*') result.multiply(next) else result.divide(next, precision)
            }
            return result
        }
        // Приоритет операций обеспечивается порядком: product() целиком вычисляет × и ÷ до обработки + и −.
        var result = product()
        while (index < source.length && source[index] in "+-") {
            val operation = source[index++]
            val next = product()
            result = if (operation == '+') result.add(next) else result.subtract(next)
        }
        require(index == source.length) { "Неизвестный символ в выражении." }
        return result
    }
}
