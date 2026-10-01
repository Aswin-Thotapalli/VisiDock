package com.thotapalli.visidock

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/** Reset the demo fixture before activity launch, never within a recreation assertion. */
class IsolatedDemoDraftRule:ExternalResource() {
    override fun before() {
        check(BuildConfig.DEMO) {"Only the demo account may be reset by UI tests."}
        runBlocking {DraftStore(InstrumentationRegistry.getInstrumentation().targetContext).clear("demo")}
    }
}
