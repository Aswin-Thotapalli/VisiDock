package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class CardTurnPhysicsTest {
    @Test fun shortSlowDragReturnsToOriginalFace() {
        assertEquals(0f,CardTurnPhysics.target(0f,40f,0f),.001f)
        assertEquals(180f,CardTurnPhysics.target(180f,145f,0f),.001f)
    }
    @Test fun distanceAndReleaseVelocityCanCompleteTheTurn() {
        assertEquals(180f,CardTurnPhysics.target(0f,110f,0f),.001f)
        assertEquals(180f,CardTurnPhysics.target(0f,45f,600f),.001f)
        assertEquals(0f,CardTurnPhysics.target(180f,130f,-600f),.001f)
    }
    @Test fun BothDirectionsUseTheActualFaceAndCannotSkipCards() {
        assertEquals(-180f,CardTurnPhysics.target(0f,-125f,0f),.001f)
        assertTrue(CardTurnPhysics.isBack(-180f))
        assertFalse(CardTurnPhysics.isBack(360f))
        assertEquals(180f,CardTurnPhysics.target(0f,140f,100_000f),.001f)
    }
}
