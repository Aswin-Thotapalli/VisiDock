package com.thotapalli.visidock

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorFeaturesTest {
    @get:Rule val compose=createComposeRule()
    @Test fun repeatedEmailRowsKeepEachAddressIndependent() {
        var values by mutableStateOf(listOf("a@example.org"))
        compose.setContent {VisiDockTheme {ChannelEditor("Email",values,false) {values=it}}}
        compose.onNodeWithText("Add another email").performClick()
        compose.onNodeWithText("Email 2").performTextInput("b@example.org")
        compose.runOnIdle {assertEquals(listOf("a@example.org","b@example.org"),values)}
        compose.onAllNodesWithText("Remove")[0].performClick()
        compose.runOnIdle {assertEquals(listOf("b@example.org"),values)}
    }
    @Test fun sourceReviewUsesActualFrontAndBackText() {
        val region=OcrRegion("Mira Shah",.1f,.1f,.8f,.2f,0)
        val state=VaultState(draft=Card(id="person",name="Mira Shah"),fieldSources=listOf(FieldSource(0,"name","Mira Shah",listOf("F1"),listOf(region),true)))
        compose.setContent {VisiDockTheme {FieldEvidence(state,"name")}}
        compose.onNodeWithText("Show name on the card").performClick()
        compose.onNodeWithText("Source for name").assertIsDisplayed()
        compose.onNodeWithText("Mira Shah").assertIsDisplayed()
        compose.onNodeWithText("Front").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Source for name").assertDoesNotExist()
    }
    @Test fun privateSharingFieldsRequireExplicitSelection() {
        var fields by mutableStateOf(ShareFields())
        compose.setContent {VisiDockTheme {ShareFieldControls(fields) {fields=it}}}
        compose.runOnIdle {assertTrue(!fields.notes && !fields.address)}
        compose.onAllNodes(isToggleable())[5].performClick()
        compose.runOnIdle {assertTrue(fields.notes);assertTrue(!fields.address)}
    }
}
