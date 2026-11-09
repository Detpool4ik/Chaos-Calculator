package ru.kuzel.chaoscalculator

import org.junit.Assert.*
import org.junit.Test

class NewEffectsTest {
    private fun type(e: CalculatorEngine, text: String) {
        text.forEachIndexed { index, c ->
            when { c.isDigit() -> e.digit(c,index*600L); c=='.' -> e.decimal(); else -> e.operator(c) }
        }
    }
    @Test fun sameDigitInflatesAndStaysBounded() {
        val taps=TapSequence()
        assertEquals(1f,taps.record("8",0,true).scale,0f)
        assertTrue(taps.record("8",200,true).scale>1f)
        var scale=0f
        for(i in 2..50) scale=taps.record("8",i*200L,true).scale
        assertEquals(2.2f,scale,0f)
    }
    @Test fun switchingDigitsOrPausingResetsInflation() {
        val t=TapSequence(); t.record("2",0,true); t.record("2",100,true)
        assertEquals(1f,t.record("1",200,true).scale,0f)
        t.record("1",300,true)
        assertEquals(1f,t.record("1",1300,true).scale,0f)
    }
    @Test fun fivePercentsFlyOnce() {
        val t=TapSequence()
        for(i in 0..3) assertFalse(t.record("%",i*200L,true).percentFlight)
        assertTrue(t.record("%",800,true).percentFlight)
        assertFalse(t.record("%",1000,true).percentFlight)
    }
    @Test fun percentBurstHasTotalTimeLimit() {
        val t=TapSequence()
        for(i in 0..4) assertFalse(t.record("%",i*800L,true).percentFlight)
    }
    @Test fun sixEqualsProduceWordAndOtherKeyBreaksStreak() {
        val t=TapSequence()
        for(i in 0..4) assertFalse(t.record("=",i*200L,true).equalsWord)
        assertTrue(t.record("=",1000,true).equalsWord)
        t.record("=",1100,true); t.record("+",1200,true)
        assertFalse(t.record("=",1300,true).equalsWord)
    }
    @Test fun disabledChaosNeverGrowsOrFliesOrShowsWord() {
        for (key in listOf("8","%","=")) {
            val t=TapSequence()
            for(i in 0..30) assertEquals(TapEffect(),t.record(key,i*100L,false))
        }
    }
    @Test fun simplePercentage() {
        val e=CalculatorEngine(); type(e,"50"); e.percent(); e.equals(); assertEquals("0.5",e.answer)
    }
    @Test fun contextualAdditionAndSubtractionPercentages() {
        for((text,answer) in listOf("200+10" to "220","200−10" to "180","100+100+10" to "220")) {
            val e=CalculatorEngine(); type(e,text); e.percent(); e.equals(); assertEquals(text,answer,e.answer)
        }
    }
    @Test fun multiplicationAndDivisionPercentages() {
        for((text,answer) in listOf("200×10" to "20","200÷10" to "2000")) {
            val e=CalculatorEngine(); type(e,text); e.percent(); e.equals(); assertEquals(text,answer,e.answer)
        }
    }
    @Test fun negativeAndRepeatedPercentages() {
        val e=CalculatorEngine(); type(e,"−50"); e.percent(); assertEquals("−0.5",e.expression)
        e.percent(); assertEquals("−0.005",e.expression)
    }
    @Test fun percentOnEmptyAndIncompleteExpressionsDoesNotCrash() {
        val e=CalculatorEngine(); e.percent(); assertEquals("0",e.expression)
        e.operator('+'); e.percent(); assertEquals("0+",e.expression)
    }
    @Test fun wordOnEmptyScreenIsNotZeroAndCanBeCleared() {
        val e=CalculatorEngine(); e.showEqualsWord(); assertEquals(Mode.WORD_RESULT,e.mode)
        assertEquals("равно",e.answer); assertEquals("",e.expression); assertNull(e.equals())
        e.clear(); assertEquals(Mode.NORMAL,e.mode); assertEquals("",e.answer)
    }
    @Test fun freshDigitAfterWordStartsNewCalculation() {
        val e=CalculatorEngine(); type(e,"2+2"); e.equals(); e.showEqualsWord()
        e.digit('5',9000); assertEquals("5",e.expression); e.equals(); assertEquals("5",e.answer)
    }
    @Test fun operatorAfterWordUsesPreviousNumericValue() {
        val e=CalculatorEngine(); type(e,"2+2"); e.equals(); e.showEqualsWord()
        e.operator('×'); e.digit('2',9000); e.equals(); assertEquals("8",e.answer)
    }
    @Test fun minusAfterWordOnEmptyScreenStartsNegativeNumber() {
        val e=CalculatorEngine(); e.showEqualsWord(); e.operator('−'); e.digit('5',1000)
        e.equals(); assertEquals("−5",e.answer)
    }
    @Test fun disablingChaosRestoresNumericAnswer() {
        val e=CalculatorEngine(); type(e,"5"); e.equals(); e.showEqualsWord(); e.setChaos(false)
        assertEquals(Mode.NORMAL,e.mode); assertEquals("5",e.answer)
        e.showEqualsWord(); assertEquals(Mode.NORMAL,e.mode)
    }
    @Test fun wordPersistsAcrossRecreation() {
        val e=CalculatorEngine(); e.restore("4",Mode.WORD_RESULT.name,true)
        assertEquals("равно",e.answer); assertEquals("4",e.expression); assertEquals(Mode.WORD_RESULT,e.mode)
        e.delete(); assertEquals(Mode.NORMAL,e.mode); assertEquals("",e.expression)
    }
    @Test fun existingBlockingEffectsTakePriority() {
        for(text in listOf("33","6+7","777")) {
            val e=CalculatorEngine(); type(e,text); if(text=="6+7") e.equals()
            val oldMode=e.mode; val oldText=e.expression
            e.showEqualsWord(); e.percent()
            assertEquals(oldMode,e.mode); assertEquals(oldText,e.expression)
        }
    }
    @Test fun longPercentageInputStaysBounded() {
        val e=CalculatorEngine(); e.setChaos(false); type(e,"1")
        repeat(200){ e.percent() }
        assertTrue(e.expression.length<=CalculatorEngine.MAX_LENGTH)
        assertEquals(Mode.NORMAL,e.mode)
    }
}
