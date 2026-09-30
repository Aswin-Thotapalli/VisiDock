package com.thotapalli.visidock

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

class VaultInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun waitForCollection() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cards").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    @Test fun createEditAndDeleteConnection() {
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Full name").performTextInput("Test Connection")
        compose.onNodeWithText("Company").performTextInput("Test Company")
        compose.onNodeWithText("Save card").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Edit card").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Edit card").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Full name").performTextReplacement("Updated Connection")
        compose.onNodeWithText("Save changes").performClick()
        try { compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Edit card").fetchSemanticsNodes().isNotEmpty() } }
        catch(e: Throwable) { compose.onRoot().printToLog("VisiDockSaveState"); throw e }
        compose.onNodeWithText("Delete card").performScrollTo().performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cards").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cards").assertIsDisplayed()
        // The confirmed-save banner may still name the person; only a collection row
        // represents a card that survived deletion.
        compose.onNode(hasText("Updated Connection") and hasClickAction()).assertDoesNotExist()
    }
    @Test fun draftSurvivesActivityRecreation() {
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Full name").performTextInput("Draft Person")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Draft Person").assertExists()
        compose.onNodeWithContentDescription("Cancel editing").performClick()
        compose.onNodeWithText("Discard changes").performClick()
        compose.onNodeWithText("Cards").assertIsDisplayed()
    }
    @Test fun companyOnlyCardCanBeSavedWithoutInventingAPerson() {
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Company").performScrollTo().performTextInput("Company-only Studio")
        compose.onNodeWithText("Save card").assertIsEnabled().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Edit card").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Edit card").performClick()
        compose.onNode(hasText("Full name") and hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        compose.onNode(hasText("Company-only Studio") and hasSetTextAction()).assertExists()
    }
    @Test fun favoritesAreExplicitAndSearchCanBeCleared() {
        compose.onNodeWithContentDescription("Favorite Rohan Mehta").performClick()
        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText("Rohan Mehta").assertExists()
        compose.onNodeWithText("Search names, companies or notes").performTextInput("Ananya")
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Rohan Mehta").assertExists()
    }
    @Test fun threePhoneNumbersStaySeparateAfterSaveAndEdit() {
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Full name").performTextInput("Multiple Phones")
        compose.onNodeWithText("Number 1").performScrollTo().performTextInput("+91 98765 43210")
        compose.onNodeWithText("Label 1 (optional)").performScrollTo().performTextInput("Mobile")
        compose.onNodeWithText("Add number").performScrollTo().performClick()
        compose.onNodeWithText("Number 2").performScrollTo().performTextInput("+91 80 2345 6789")
        compose.onNodeWithText("Add number").performScrollTo().performClick()
        compose.onNodeWithText("Number 3").performScrollTo().performTextInput("+44 20 1234 5678")
        compose.onNodeWithText("Save card").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Edit card").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Call Mobile +91 98765 43210").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Call phone 2 +91 80 2345 6789").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Call phone 3 +44 20 1234 5678").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Edit card").performClick()
        compose.onNodeWithText("Number 2").performScrollTo().assertTextContains("+91 80 2345 6789")
        compose.onNodeWithContentDescription("Remove phone 2").performScrollTo().performClick()
        compose.onNodeWithText("Number 2").performScrollTo().assertTextContains("+44 20 1234 5678")
        compose.onNodeWithText("Save changes").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Edit card").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Call phone 2 +44 20 1234 5678").performScrollTo().assertExists()
        compose.onNodeWithText("+91 80 2345 6789").assertDoesNotExist()
    }
    @Test fun bundledModelFindsRelatedLanguageWithoutNetwork() = runBlocking {
        val engine=SemanticSearch(compose.activity)
        try {
            val cards=listOf(
                Card(id="factory",name="Maya",role="Industrial production manager",notes="Runs an automobile assembly plant"),
                Card(id="music",name="Ravi",role="Music teacher",notes="Teaches piano lessons to children")
            )
            val results=engine.search(cards,"person working in a car factory")
            assertTrue("Bundled ONNX model must actually run",results.semantic)
            assertFalse(results.unavailable)
            assertEquals("factory",results.cards.firstOrNull()?.id)
        } finally {engine.close()}
    }
}
