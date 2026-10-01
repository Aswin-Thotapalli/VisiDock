package com.thotapalli.visidock

/** Presentation timings are independent of inference. Fast OCR may queue a
 * completion, but must never initialize the visible card on its back. */
internal object ScanChoreography {
    const val SWEEP_MILLIS=4000
    const val TURN_MILLIS=720
    const val HANDOFF_MILLIS=850
    fun canLeaveFront(processingSide:Int,hasBack:Boolean)=processingSide>=if(hasBack) 1 else 2
    fun canLeaveBack(processingSide:Int)=processingSide>=2
    fun revealRegion(regionTop:Float,sweep:Float):Float =
        ((sweep-regionTop.coerceIn(0f,1f))/.12f).coerceIn(0f,1f)
}
