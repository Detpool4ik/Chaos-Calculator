package ru.kuzel.chaoscalculator

import org.junit.Assert.*
import org.junit.Test

class ScenesTest {
    @Test fun pauseSavesFastInputDuringWarning() {
        val e=CalculatorEngine(); repeat(30){e.digit('1',it*90L)}
        assertEquals(Mode.UNSTABLE,e.mode)
        e.finishOverload(4410); assertEquals(Mode.NORMAL,e.mode)
        assertEquals(30,e.expression.length)
    }
    @Test fun continuingToTypeDuringWarningCollapsesOnlyAfterDelay() {
        val e=CalculatorEngine(); repeat(30){e.digit('1',it*90L)}
        e.digit('1',4300); e.finishOverload(4409); assertEquals(Mode.UNSTABLE,e.mode)
        e.finishOverload(4410); assertEquals(Mode.COLLAPSED,e.mode)
    }
    @Test fun backspaceAndOperatorCancelWarning() {
        for(key in listOf("⌫","+")) {
            val e=CalculatorEngine();repeat(81){e.digit('1',it*700L)}
            if(key=="⌫")e.delete() else e.operator('+')
            e.finishOverload(999999);assertEquals(Mode.NORMAL,e.mode)
        }
    }
    @Test fun ordinaryModeAcceptsThreeHundredDigitsAndStopsAtBoundary() {
        val e=CalculatorEngine(); e.setChaos(false)
        repeat(350){e.digit('9',it*10L)}
        assertEquals(300,e.expression.length);assertEquals(Mode.NORMAL,e.mode)
        e.equals();assertEquals(300,e.answer.length)
        e.digit('2',10000);assertEquals("2",e.expression)
    }
    @Test fun signAndRouletteRespectMaximumLength() {
        val e=CalculatorEngine();e.setChaos(false)
        e.restore("9".repeat(300),"NORMAL",false);e.changeSign()
        assertEquals("9".repeat(300),e.expression)
        e.setChaos(true);e.restore("1".repeat(297)+"777","ROULETTE_ARMED",false)
        e.digit('8',0);assertEquals(Mode.ROULETTE_ARMED,e.mode)
        assertEquals(300,e.expression.length)
    }
    @Test fun largeIntegersDoNotLosePrecision() {
        val n="9".repeat(79)
        assertEquals("1"+"0".repeat(79),Evaluator.evaluate("$n+1").toPlainString())
    }
    @Test fun hugeResultDoesNotDestroyInput() {
        val e=CalculatorEngine();e.setChaos(false)
        e.restore("9".repeat(150)+"×"+"9".repeat(149),"NORMAL",false)
        e.equals();val result=e.expression
        e.changeSign();assertTrue(e.expression.length<=300)
        assertEquals(299,result.length)
    }
    @Test fun zeroDivisionHoleOnlyInChaosAndClearRecovers() {
        for(enabled in listOf(true,false)) {
            val e=CalculatorEngine(); e.setChaos(enabled); e.digit('8',0);e.operator('÷');e.digit('0',600);e.equals()
            assertEquals(if(enabled) Mode.BLACK_HOLE else Mode.NORMAL,e.mode)
            e.clear();assertEquals(Mode.NORMAL,e.mode);assertEquals("",e.expression)
        }
    }
    @Test fun zeroDivisionHoleCanBeDisabledOrEdited() {
        val e=CalculatorEngine();e.restore("8÷0","BLACK_HOLE",false)
        e.delete();assertEquals("8÷",e.expression);assertEquals(Mode.NORMAL,e.mode)
        e.restore("8÷0","BLACK_HOLE",false);e.setChaos(false);assertEquals(Mode.NORMAL,e.mode)
    }
    @Test fun warningDoesNotRestoreAsPermanentLock() {
        val e=CalculatorEngine();e.restore("1".repeat(81),"UNSTABLE",false);assertEquals(Mode.NORMAL,e.mode)
    }
    @Test fun emptyBackspaceErasesInThreeStages() {
        val s=JokeState()
        for(i in 1..9) {s.input("⌫",true,i*100L,true);assertEquals(i/3,s.eraseStage)}
        s.restoreInterface();assertEquals(0,s.eraseStage)
    }
    @Test fun deletingRealDigitsDoesNotEraseInterface() {
        val s=JokeState();repeat(20){s.input("⌫",false,it*100L,true)};assertEquals(0,s.eraseStage)
    }
    @Test fun typingOrDisablingChaosRestoresErasedLabels() {
        val s=JokeState();repeat(6){s.input("⌫",true,it*100L,true)}
        s.input("8",true,1000,true);assertEquals(0,s.eraseStage)
        repeat(9){s.input("⌫",true,it*100L,false)};assertEquals(0,s.eraseStage)
    }
    @Test fun sixAlternatingSignsStartFight() {
        val s=JokeState();val keys=listOf("+","−","+","−","+","−")
        keys.forEachIndexed{i,k->assertEquals(i==5,s.input(k,false,i*200L,true))}
    }
    @Test fun SameSignPauseOrAnotherKeyPreventsFight() {
        val s=JokeState()
        repeat(10){assertFalse(s.input("+",false,it*100L,true))}
        s.input("−",false,2000,true);s.input("8",false,2100,true)
        assertFalse(s.input("+",false,2200,true))
        repeat(10){assertFalse(s.input(if(it%2==0) "+" else "−",false,4000+it*1000L,true))}
    }
    @Test fun noFightInNormalMode() {
        val s=JokeState();repeat(10) {
            assertFalse(s.input(if(it%2==0) "+" else "−",false,it*100L,false))
        }
    }
}
