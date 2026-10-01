package com.thotapalli.visidock

import kotlin.math.abs

internal data class CaseLeafPose(val offsetX:Float,val offsetY:Float,val scale:Float,val layer:Float)

/** A reversible sheet exchange through the case's opaque front pocket.
 * At the layer crossing the positive-distance leaf is entirely behind leather.
 * Distance is the only input state: interrupted/reversed drags retrace the same
 * continuous trajectory, without velocity-sign changes or a mid-gesture reset. */
internal object CaseBrowseGeometry {
    const val CARD_BASELINE=228f
    const val LIP_TOP=212f
    private fun smoothstep(start:Float,end:Float,value:Float):Float {
        val t=((value-start)/(end-start)).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }
    fun pose(distance:Float,cardHeight:Float):CaseLeafPose {
        val depth=abs(distance).coerceIn(0f,1f)
        val descent=smoothstep(0f,.42f,distance)*(1f-smoothstep(.58f,1f,distance))
        // 12dp extends beyond the pocket's geometric threshold, accounting for
        // the slight scale/paper-edge change and the leaf's soft contact shadow.
        val pocketTravel=cardHeight+12f
        // The three-image decode window changes at |distance|=1.5. Retired and
        // newly admitted leaves must already be below the pocket then, including
        // its center notch and soft shadow. They emerge only inside distance1.3,
        // giving the next photograph 0.2 page of preparation before it is exposed.
        val distantBurial=smoothstep(1f,1.3f,abs(distance))*(cardHeight+28f)
        return CaseLeafPose(-distance.coerceIn(-1f,1f)*12f,
            -depth*17f+descent*pocketTravel+distantBurial,1f-.055f*depth,1f-depth)
    }
    fun topInCase(distance:Float,cardHeight:Float)=CARD_BASELINE-cardHeight+pose(distance,cardHeight).offsetY
}
