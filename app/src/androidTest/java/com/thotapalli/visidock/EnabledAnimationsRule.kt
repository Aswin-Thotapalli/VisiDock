package com.thotapalli.visidock

import android.animation.ValueAnimator
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Test Lab disables system animations by default. Enable them only for tests
 * that inspect intermediate poses, then restore the device's exact settings. */
class EnabledAnimationsRule:TestRule {
    override fun apply(base:Statement,description:Description)=object:Statement() {
        override fun evaluate() {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
            val names=listOf("animator_duration_scale","transition_animation_scale","window_animation_scale")
            val previous=names.associateWith {shell("settings get global $it")}
            previous.values.forEach {check(it=="null" || it.toFloatOrNull()!=null) {"Unexpected animation setting"}}
            fun awaitAnimator(enabled:Boolean) {
                val deadline=SystemClock.uptimeMillis()+5000
                while(ValueAnimator.areAnimatorsEnabled()!=enabled && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(25)
                assertTrue("System animation setting must take effect before proceeding",ValueAnimator.areAnimatorsEnabled()==enabled)
            }
            try {
                names.forEach {shell("settings put global $it 1")}
                awaitAnimator(true)
                base.evaluate()
            } finally {
                previous.forEach {(name,value)->shell(if(value=="null") "settings delete global $name" else "settings put global $name $value")}
                awaitAnimator(previous.getValue("animator_duration_scale").toFloatOrNull()!=0f)
            }
        }
    }
}
