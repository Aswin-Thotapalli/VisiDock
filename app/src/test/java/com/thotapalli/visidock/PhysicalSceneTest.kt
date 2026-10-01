package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class PhysicalSceneTest {
    @Test fun pressingClosesTheAirGapAndShadowWithoutInvertingTheSurface() {
        PhysicalMaterial.entries.forEach {material->
            val samples=(0..10).map {PhysicalMotion.surface(8f,it/10f,material)}
            samples.zipWithNext().forEach {(before,after)->
                assertTrue(after.height<=before.height)
                assertTrue(after.shadowOffset<=before.shadowOffset)
                assertTrue(after.shadowSpread<=before.shadowSpread)
                assertTrue(after.scale<=before.scale)
            }
            assertTrue(samples.last().height>0f)
            assertTrue(samples.last().scale>.9f)
            assertEquals(samples.first(),PhysicalMotion.surface(8f,-5f,material))
            assertEquals(samples.last(),PhysicalMotion.surface(8f,5f,material))
            assertEquals(0f,PhysicalMotion.surface(-2f,1f,material).height,0f)
        }
        assertTrue(PhysicalMotion.surface(8f,1f,PhysicalMaterial.Leather).scale<PhysicalMotion.surface(8f,1f,PhysicalMaterial.Metal).scale)
    }

    @Test fun parallelStackDepthIsSymmetricAndBoundedForLargeCollections() {
        for(distance in listOf(.1f,.5f,1f,2f,1000f)) {
            assertEquals(PhysicalMotion.cardScale(distance),PhysicalMotion.cardScale(-distance),0f)
            assertTrue(PhysicalMotion.cardScale(distance) in .94f..1f)
        }
        assertEquals(1f,PhysicalMotion.cardScale(0f),0f)
    }

    @Test fun personalizationPreservesTheNameAndHandlesPossessives() {
        assertEquals("Aswin’s VisiDock",caseInscription("  Aswin  "))
        assertEquals("James’ VisiDock",caseInscription("James"))
        assertEquals("Ana María’s VisiDock",caseInscription("Ana   María"))
        assertEquals("VisiDock",caseInscription(" \n "))
    }
}
