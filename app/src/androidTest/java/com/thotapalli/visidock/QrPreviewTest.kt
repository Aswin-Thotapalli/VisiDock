package com.thotapalli.visidock

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QrPreviewTest {
    @get:Rule val compose=createComposeRule()
    @Test fun websitePreviewDoesNotOpenUntilExplicitConfirmation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // An action-only filter rejects intents containing a URI, so it cannot
        // observe ACTION_VIEW for an HTTPS website. Match both action and data.
        val filter=IntentFilter(Intent.ACTION_VIEW).apply {addDataScheme("https");addDataAuthority("example.com",null)}
        val monitor=Instrumentation.ActivityMonitor(filter,null,true)
        instrumentation.addMonitor(monitor)
        try {
            var dismissed=false
            compose.setContent {VisiDockTheme {QrPayloadPreview(QrPayload.Link("https://example.com/team"),{dismissed=true},{error("A link is not a contact")})}}
            compose.onNodeWithText("https://example.com/team").assertIsDisplayed()
            compose.runOnIdle {assertEquals(0,monitor.hits);assertFalse(dismissed)}
            compose.onNodeWithText("Open website").performClick()
            compose.runOnIdle {assertEquals(1,monitor.hits);assertTrue(dismissed)}
        } finally {instrumentation.removeMonitor(monitor)}
    }
    @Test fun contactQrRequiresReviewBeforeItLeavesThePreview() {
        var selected:Card?=null
        val source=Card(name="Mira Rao",email="mira@example.com")
        compose.setContent {VisiDockTheme {QrPayloadPreview(QrPayload.Contact(source),{}, {selected=it})}}
        compose.runOnIdle {assertNull(selected)}
        compose.onNodeWithText("Review contact").performClick()
        compose.runOnIdle {assertEquals(source,selected)}
    }
}
