package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CaseBrowseGeometryTest {
    @Test fun layerExchangeOccursOnlyWhileOneFaceIsBehindTheLeatherPocket() {
        for(height in listOf(100f,160f,206.1f)) {
            for(progress in listOf(.49f,.4999f,.5f,.5001f,.51f)) {
                val outgoing=CaseBrowseGeometry.pose(progress,height)
                val incoming=CaseBrowseGeometry.pose(progress-1f,height)
                assertTrue("Outgoing face and shadow must clear the lip",CaseBrowseGeometry.topInCase(progress,height)>CaseBrowseGeometry.LIP_TOP+12f)
                assertTrue("Incoming face stays visible",CaseBrowseGeometry.topInCase(progress-1f,height)<CaseBrowseGeometry.LIP_TOP)
                if(progress<.5f) assertTrue(outgoing.layer>incoming.layer)
                if(progress>.5f) assertTrue(outgoing.layer<incoming.layer)
            }
        }
    }
    @Test fun imageWindowChangesOnlyAfterDistantLeavesClearTheNotchAndShadow() {
        for(height in listOf(100f,160f,206.1f)) {
            for(distance in listOf(-2f,-1.5001f,-1.4999f,-1.3f,1.3f,1.4999f,1.5001f,2f)) {
                assertTrue("Decode-window change exposes a rear photo at $distance",
                    CaseBrowseGeometry.topInCase(distance,height)>CaseBrowseGeometry.LIP_TOP+23f)
            }
            // Adjacent parked leaves still retain their original shallow stack edge.
            assertEquals(-17f,CaseBrowseGeometry.pose(1f,height).offsetY,0f)
            assertEquals(-17f,CaseBrowseGeometry.pose(-1f,height).offsetY,0f)
        }
    }

    @Test fun pathIsContinuousAndExactlyReversibleAcrossEveryControlPoint() {
        for(point in listOf(-1.5f,-1.3f,-1f,0f,.42f,.5f,.58f,1f,1.3f,1.5f,2f)) {
            val left=CaseBrowseGeometry.pose(point-.00001f,206f)
            val right=CaseBrowseGeometry.pose(point+.00001f,206f)
            assertTrue(abs(left.offsetY-right.offsetY)<.05f)
            assertTrue(abs(left.offsetX-right.offsetX)<.01f)
            assertTrue(abs(left.scale-right.scale)<.001f)
        }
        val forward=(0..100).map {CaseBrowseGeometry.pose(it/100f,206f)}
        val reverse=(100 downTo 0).map {CaseBrowseGeometry.pose(it/100f,206f)}
        assertEquals(forward,reverse.reversed())
        assertEquals(0f,forward.first().offsetY,0f)
        assertEquals(-17f,forward.last().offsetY,0f)
    }
    @Test fun lateralEdgesStayInsideNormalPhoneCaseWidthsWithoutRotation() {
        for(width in listOf(280f,320f,360f,412f,600f)) {
            val cardWidth=minOf(width-64f,340f)
            for(step in -200..200) {
                val pose=CaseBrowseGeometry.pose(step/100f,cardWidth/1.65f)
                val furthest=abs(pose.offsetX)+cardWidth*pose.scale/2f
                assertTrue("Paper crosses case side walls at $width",furthest<=(width-52f)/2f+.1f)
                assertTrue(pose.scale in .94f..1f)
            }
        }
    }
}
