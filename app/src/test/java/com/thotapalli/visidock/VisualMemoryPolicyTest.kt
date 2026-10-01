package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class VisualMemoryPolicyTest {
    private val gib=VisualMemoryPolicy.GIB
    private val weights=2588147712L
    private fun allows(total:Long=8*gib,available:Long=4*gib,warm:Boolean=false,
                       images:Int=1,low:Boolean=false,lowRam:Boolean=false,threshold:Long=gib/4)=
        VisualMemoryPolicy.allows(total,available,low,lowRam,weights,warm,images,threshold)

    @Test fun observedSmallDeviceCannotStartOrRetainTheModel() {
        assertFalse(allows(total=7*gib/2,available=2*gib))
        assertFalse(allows(total=7*gib/2,available=2*gib,warm=true))
        assertFalse(allows(lowRam=true))
    }

    @Test fun capableDeviceNeedsWeightsAndRuntimeHeadroomBeforeColdStart() {
        assertFalse(allows(available=2*gib))
        assertFalse(allows(available=weights+gib-1))
        assertTrue(allows(available=weights+gib))
        assertTrue(allows(total=12*gib,available=6*gib,images=2))
    }

    @Test fun WarmReuseStillNeedsInferenceReserveAndSystemPressureAlwaysWins() {
        assertTrue(allows(available=gib,warm=true))
        assertFalse(allows(available=gib-1,warm=true))
        assertFalse(allows(available=4*gib,warm=true,low=true))
        assertFalse(allows(available=gib,warm=true,threshold=3*gib/4))
    }

    @Test fun changedEngineSignatureMustUseColdBudgetAndTwoSidesNeedExtraReserve() {
        assertTrue(allows(available=2*gib,warm=true))
        assertFalse(allows(available=2*gib,warm=false))
        assertFalse(allows(available=weights+gib,images=2))
        assertTrue(allows(available=weights+gib+gib/4,images=2))
        assertFalse(allows(available=gib,warm=true,images=2))
    }
}
