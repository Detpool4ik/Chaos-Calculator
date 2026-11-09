package ru.kuzel.chaoscalculator

import org.junit.Assert.*
import org.junit.Test

class CalculatorEngineTest {
    private fun type(engine: CalculatorEngine, input: String, interval: Long = 600) {
        input.forEachIndexed { i, c ->
            when {
                c.isDigit() -> engine.digit(c, i * interval)
                c == '.' -> engine.decimal()
                else -> engine.operator(c)
            }
        }
    }
    @Test fun respectsPrecedence() { assertEquals("14", Evaluator.evaluate("2+3×4").toPlainString()) }
    @Test fun decimalIsExact() { assertEquals("0.3", Evaluator.evaluate("0.1+0.2").toPlainString()) }
    @Test fun negativeNumbers() { assertEquals("-6", Evaluator.evaluate("−2×3").toPlainString()) }
    @Test fun divisionHasEightySignificantDigits() { assertEquals("0."+"3".repeat(80), Evaluator.evaluate("1÷3").toPlainString()) }
    @Test fun divideByZeroReturnsMessageWithoutCrash() {
        val e = CalculatorEngine(); type(e,"8÷0"); assertNull(e.equals()); assertTrue(e.message.contains("ноль"))
    }
    @Test fun incompleteExpressionDoesNotCrash() {
        val e = CalculatorEngine(); type(e,"8+"); assertNull(e.equals()); assertEquals(Mode.NORMAL,e.mode)
    }
    @Test fun forbidsRepeatedDecimal() {
        val e = CalculatorEngine(); type(e,"1.2.3"); assertEquals("1.23",e.expression)
    }
    @Test fun operatorCanBeReplaced() {
        val e = CalculatorEngine(); type(e,"8+×2"); e.equals(); assertEquals("16",e.answer)
    }
    @Test fun digitAfterEqualsStartsNewNumber() {
        val e = CalculatorEngine(); type(e,"2+2"); e.equals(); e.digit('8',9000); assertEquals("8",e.expression)
    }
    @Test fun operatorAfterEqualsContinuesAnswer() {
        val e = CalculatorEngine(); type(e,"2+2"); e.equals(); e.operator('×'); e.digit('2',9000); e.equals(); assertEquals("8",e.answer)
    }
    @Test fun twoThreesLockImmediatelyAndDeleteRecovers() {
        for (s in listOf("33","3+3","3−3","3×3","3÷3","133")) {
            val e=CalculatorEngine(); type(e,s); assertEquals(s,Mode.LOCKED,e.mode)
            val text=e.expression; e.digit('4',9999); e.operator('+'); e.decimal(); e.changeSign(); e.equals()
            assertEquals(text,e.expression)
            e.delete(); assertEquals(Mode.NORMAL,e.mode)
        }
    }
    @Test fun resultContaining33DoesNotLock() {
        val e=CalculatorEngine(); type(e,"30+3"); e.equals(); assertEquals("33",e.answer); assertEquals(Mode.NORMAL,e.mode)
    }
    @Test fun ordinaryOperandsContainingSingleThreeAreNotPair() {
        val e=CalculatorEngine(); type(e,"13+3"); assertEquals(Mode.NORMAL,e.mode); e.equals(); assertEquals("16",e.answer)
    }
    @Test fun sixSevenWaitOnlyOnEquals() {
        for(s in listOf("6+7","6×7","6÷7","06+07")) {
            val e=CalculatorEngine(); type(e,s); assertEquals(Mode.NORMAL,e.mode)
            assertNull(e.equals()); assertEquals(Mode.THINKING,e.mode)
            e.delete(); assertEquals(Mode.NORMAL,e.mode)
        }
    }
    @Test fun sixMinusSevenWorks() {
        val e=CalculatorEngine(); type(e,"6−7"); e.equals(); assertEquals("−1",e.answer)
    }
    @Test fun twelveRapidDigitsNoLongerCollapse() {
        val e=CalculatorEngine(); type(e,"121212121212",100); assertEquals(Mode.NORMAL,e.mode)
        e.clear(); assertEquals(Mode.NORMAL,e.mode); assertEquals("",e.expression)
    }
    @Test fun slowInputDoesNotCollapseUntilLengthLimit() {
        val e=CalculatorEngine(); type(e,"12".repeat(40),600); assertEquals(Mode.NORMAL,e.mode)
        e.digit('2',49000); assertEquals(Mode.UNSTABLE,e.mode)
        e.finishOverload(50000); assertEquals(Mode.UNSTABLE,e.mode)
        e.finishOverload(50800); assertEquals(Mode.COLLAPSED,e.mode)
    }
    @Test fun rouletteReplacesRequestedDigitWithWheelValueExactlyOnce() {
        val e=CalculatorEngine(); type(e,"777"); assertEquals(Mode.ROULETTE_ARMED,e.mode)
        e.digit('9',3000); assertEquals(Mode.SPINNING,e.mode); assertEquals("777",e.expression)
        e.rouletteResult(4); assertEquals("7774",e.expression); assertEquals(Mode.NORMAL,e.mode)
        e.digit('1',4000); assertEquals("77741",e.expression)
    }
    @Test fun rouletteSurvivesOperatorAndCanBeCancelled() {
        val e=CalculatorEngine(); type(e,"777+"); e.digit('2',4000); assertEquals(Mode.SPINNING,e.mode)
        e.cancelRoulette(); assertEquals(Mode.ROULETTE_ARMED,e.mode)
        e.digit('8',5000); e.rouletteResult(0); assertEquals("777+0",e.expression)
    }
    @Test fun normalModeHasNoEasterEggs() {
        val e=CalculatorEngine(); e.setChaos(false); type(e,"33+6×7",10); e.equals()
        assertEquals("75",e.answer); assertEquals(Mode.NORMAL,e.mode)
        e.clear(); type(e,"777777777777777777",10); assertEquals(Mode.NORMAL,e.mode)
    }
    @Test fun chaosOffUnblocksWithoutLosingInput() {
        val e=CalculatorEngine(); type(e,"3+3"); e.setChaos(false); e.equals(); assertEquals("6",e.answer)
    }
    @Test fun recreationRestoresLockedAndWaitingStates() {
        for(mode in listOf(Mode.LOCKED,Mode.THINKING,Mode.COLLAPSED,Mode.ROULETTE_ARMED)) {
            val e=CalculatorEngine(); e.restore("33",mode.name,false); assertEquals(mode,e.mode)
            e.clear(); assertEquals(Mode.NORMAL,e.mode)
        }
        val e=CalculatorEngine(); e.restore("777",Mode.SPINNING.name,false); assertEquals(Mode.ROULETTE_ARMED,e.mode)
    }
    @Test fun leadingDecimalAndSign() {
        val e=CalculatorEngine(); type(e,".5+1"); e.changeSign(); assertEquals("−1.5",e.expression)
    }
}
