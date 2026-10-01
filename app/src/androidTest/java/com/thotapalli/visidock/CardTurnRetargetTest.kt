package com.thotapalli.visidock

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CardTurnRetargetTest {
    @get:Rule(order=0) val animations=EnabledAnimationsRule()
    @get:Rule(order=1) val compose=createComposeRule()

    @Test fun rapidReversalPreservesPoseAndMomentumThenSettlesOnRequestedFace() {
        assertTrue("Motion coverage requires enabled system animations",android.animation.ValueAnimator.areAnimatorsEnabled())
        lateinit var turn:CardTurnState
        compose.setContent {
            val scope=rememberCoroutineScope()
            turn=remember {CardTurnState(false,scope) {}}
            Text("Card motion")
        }
        compose.mainClock.autoAdvance=false
        try {
            compose.runOnIdle {turn.flip()}
            compose.mainClock.advanceTimeBy(80)
            var interrupted=0f
            compose.runOnIdle {
                interrupted=turn.angle
                assertTrue(interrupted>0f && interrupted<180f)
                turn.flip()
                assertEquals("Retargeting must not jump to another pose",interrupted,turn.angle,.001f)
            }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle {
                assertTrue("The card should brake its existing forward momentum before reversing",turn.angle>interrupted)
            }
            compose.mainClock.advanceTimeBy(1500)
            compose.runOnIdle {assertEquals(0f,turn.angle,.01f);assertFalse(turn.targetBack)}
        } finally {compose.mainClock.autoAdvance=true}
    }
}
