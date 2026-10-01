package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class ScanChoreographyTest {
    @Test fun processingCannotAdvancePastUnreadSides() {
        assertFalse(ScanChoreography.canLeaveFront(0,true))
        assertTrue(ScanChoreography.canLeaveFront(1,true))
        assertFalse(ScanChoreography.canLeaveFront(1,false))
        assertTrue(ScanChoreography.canLeaveFront(2,false))
        assertFalse(ScanChoreography.canLeaveBack(1))
        assertTrue(ScanChoreography.canLeaveBack(2))
    }
    @Test fun evidenceRespondsOnlyAfterTheLightReachesItsSourcePosition() {
        assertEquals(0f,ScanChoreography.revealRegion(.7f,.3f),.0001f)
        assertEquals(.5f,ScanChoreography.revealRegion(.7f,.76f),.0001f)
        assertEquals(1f,ScanChoreography.revealRegion(.7f,1f),.0001f)
    }
}
