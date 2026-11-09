package ru.kuzel.chaoscalculator

import org.junit.Assert.*
import org.junit.Test

class RequirementsTest {
    private fun type(e: CalculatorEngine,s: String) { s.forEachIndexed { i,c ->
        when { c.isDigit()->e.digit(c,i*600L);c=='.'->e.decimal();else->e.operator(c) }
    } }
    @Test fun result33AndItsContinuationAreNotTyped33() {
        val e=CalculatorEngine();type(e,"11×3");e.equals()
        assertEquals("33",e.answer);assertEquals(Mode.NORMAL,e.mode)
        type(e,"+2");e.equals();assertEquals("35",e.answer);assertEquals(Mode.NORMAL,e.mode)
        e.clear();type(e,"133");assertEquals(Mode.LOCKED,e.mode)
    }
    @Test fun savedGeneratedPrefixDoesNotLockAfterRotation() {
        val e=CalculatorEngine();type(e,"11×3");e.equals();e.operator('+')
        val restored=CalculatorEngine()
        restored.restore(e.expression,e.mode.name,e.justEvaluated,e.previousExpression,e.generatedPrefixLength)
        restored.digit('2',3000);restored.equals();assertEquals("35",restored.answer)
        assertEquals(Mode.NORMAL,restored.mode)
    }
    @Test fun oldLockedResultIsMigratedAndChaosToggleDoesNotRelock() {
        val e=CalculatorEngine();e.restore("33","LOCKED",true)
        assertEquals(Mode.NORMAL,e.mode);e.setChaos(false);e.setChaos(true)
        assertEquals(Mode.NORMAL,e.mode)
    }
    @Test fun literalPairStillLocksAfterAComputedPrefix() {
        val e=CalculatorEngine();type(e,"11×3");e.equals();type(e,"+3×3")
        assertEquals(Mode.LOCKED,e.mode)
    }
    @Test fun displaySeparatesHistoryOperationAndOperand() {
        val e=CalculatorEngine();type(e,"5+10×2")
        assertEquals(CalculatorEngine.Display("5 + 10 ×","×","2"),e.display())
        e.equals();assertEquals(CalculatorEngine.Display("5 + 10 × 2","","25"),e.display())
        e.operator('+');assertEquals(CalculatorEngine.Display("25 +","+","0"),e.display())
        e.digit('2',8000);e.equals();assertEquals("27",e.answer)
        e.clear();assertEquals(CalculatorEngine.Display("","","0"),e.display())
    }
    @Test fun lastEraseIsLatchedUntilExplicitRestore() {
        val s=JokeState();repeat(9){s.input("⌫",true,it*100L,true)}
        for(k in listOf("8","C","=","+","⌫"))s.input(k,true,2000,true)
        assertEquals(3,s.eraseStage);s.restoreInterface();assertEquals(0,s.eraseStage)
    }
}
