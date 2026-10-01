package com.thotapalli.visidock

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

class VaultInteractionTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val isolated = org.junit.rules.RuleChain.outerRule(IsolatedDemoDraftRule()).around(compose)
    @Before fun waitForCollection() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cards").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("List", substring=false).performClick()
        compose.waitForIdle()
    }

    private fun openAcknowledgedCardFromCase(label:String) {
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Card case").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(10000) {compose.onAllNodesWithText("Card saved").fetchSemanticsNodes().isEmpty()}
        compose.waitForIdle()
        compose.onNodeWithText("Case", substring=false).assertIsSelected()
        compose.onNodeWithContentDescription("Card case").assertIsDisplayed()
        // The saved card must be physically present in the case, not merely named
        // by a receipt. Open card must then open that focused card.
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasContentDescription("Card case"))).assertIsDisplayed()
        compose.onNodeWithText("Open card").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Edit card").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
        compose.onNodeWithText(label).assertExists()
    }

    @Test fun createEditAndDeleteConnection() {
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Full name").performTextInput("Test Connection")
        compose.onNodeWithText("Company").performTextInput("Test Company")
        compose.onNodeWithText("Save card").performClick()
        openAcknowledgedCardFromCase("Test Connection")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Edit card").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Full name").performTextReplacement("Updated Connection")
        compose.onNodeWithText("Save changes").performClick()
        openAcknowledgedCardFromCase("Updated Connection")
        compose.onNodeWithText("Delete card").performScrollTo().performClick()
        compose.onNodeWithText("Remove card").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cards").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cards").assertIsDisplayed()
        // The confirmed-save banner may still name the person; only a collection row
        // represents a card that survived deletion.
        compose.onNode(hasText("Updated Connection") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Sync & recovery").performScrollTo().performClick()
        compose.onNodeWithText("Recovery bin · 1").performScrollTo().performClick()
        compose.onNodeWithText("Updated Connection").assertIsDisplayed()
        compose.onNodeWithText("Restore").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Updated Connection").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Collection").performClick()
        compose.onNodeWithText("List",substring=false).performClick()
        compose.onNodeWithText("List",substring=false).assertIsSelected()
        // Restore appends the card after the three demo cards. Its lazy row is
        // not composed at the top of the collection; scroll the list to create
        // that row rather than trying to scroll an absent semantic node.
        // Filtering also runs off the Compose clock, so await the restored count.
        compose.waitUntil(10000) {compose.onAllNodesWithText("4 cards").fetchSemanticsNodes().isNotEmpty()}
        val restored=hasText("Updated Connection") and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(restored)
        compose.onNode(restored).assertIsDisplayed().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Edit card").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Updated Connection").assertExists()
        compose.onNodeWithText("Test Company").assertExists()
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
        openAcknowledgedCardFromCase("Company-only Studio")
        compose.onNodeWithContentDescription("Edit card").performClick()
        compose.onNode(hasText("Full name") and hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        compose.onNode(hasText("Company-only Studio") and hasSetTextAction()).assertExists()
    }
    @Test fun favoritesAreExplicitAndSearchCanBeCleared() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Favorite Rohan Mehta"))
        // The image makes a card taller than the available viewport. Finding its
        // lazy-list item does not expose the star at the bottom of that item.
        val favorite=compose.onNodeWithContentDescription("Favorite Rohan Mehta")
        favorite.performScrollTo()
        val list=compose.onNode(hasScrollToIndexAction())
        val offset=favorite.fetchSemanticsNode().boundsInRoot.center.y-list.fetchSemanticsNode().boundsInRoot.center.y
        list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {scroll->scroll(0f,offset)}
        favorite.assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10000) {
            // Saving may reorder the demo repository's cards; find the confirmed
            // selected control even if its row moved outside composition.
            runCatching {list.performScrollToNode(hasContentDescription("Remove Rohan Mehta from favorites"))}.isSuccess
        }
        compose.onNodeWithContentDescription("Remove Rohan Mehta from favorites").assertIsEnabled()
        compose.onNodeWithText("Favorites").performClick()
        compose.onNode(hasText("Favorites") and isSelectable()).assertIsSelected()
        // Filtering runs on Dispatchers.Default. Compose idling alone can still see
        // the old one-card result; await the rendered result, not a timing delay.
        // Ananya starts favorited, so Rohan must produce exactly two favorites.
        compose.waitUntil(10000) {compose.onAllNodesWithText("2 cards").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Rohan Mehta"))
        compose.onNodeWithText("Rohan Mehta").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Rohan Mehta from favorites").assertIsEnabled()
        compose.onNodeWithText("Search names, companies or notes").performTextInput("Ananya")
        // Exercise an actual completed search before clearing, not just a text edit.
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("1 card").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Searching on your device…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Ananya Rao"))
        compose.onNodeWithText("Ananya Rao").assertIsDisplayed()
        compose.onNodeWithText("Rohan Mehta").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("2 cards").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Rohan Mehta"))
        compose.onNodeWithText("Rohan Mehta").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Rohan Mehta from favorites").assertIsEnabled()
    }
    @Test fun openingAndReturningToCardPreservesCollectionScroll() {
        val list=compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(1)
        compose.waitForIdle()
        val before=list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Collection must be scrolled before opening a card",before>0f)
        compose.onNode(hasText("Rohan Mehta") and hasClickAction()).performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Back to collection").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Back to collection").performClick()
        compose.waitUntil(10000) {compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
        val after=compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("Returning should preserve the exact collection position",before,after,.001f)
    }
    @Test fun threePhoneNumbersStaySeparateAfterSaveAndEdit() {
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Full name").performTextInput("Multiple Phones")
        compose.onNodeWithText("Number 1").performScrollTo().performTextInput("+91 98765 43210")
        compose.onNodeWithText("Label 1 (optional)").performScrollTo().performTextInput("Mobile")
        compose.onNodeWithText("Add number").performScrollTo().performClick()
        compose.onNodeWithText("Number 2").assertIsFocused()
        compose.onNodeWithText("Number 2").performScrollTo().performTextInput("+91 80 2345 6789")
        compose.onNodeWithText("Add number").performScrollTo().performClick()
        compose.onNodeWithText("Number 3").assertIsFocused()
        compose.onNodeWithText("Number 3").performScrollTo().performTextInput("+44 20 1234 5678")
        compose.onNodeWithText("Save card").performClick()
        openAcknowledgedCardFromCase("Multiple Phones")
        compose.onNodeWithContentDescription("Call Mobile +91 98765 43210").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Call phone 2 +91 80 2345 6789").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Call phone 3 +44 20 1234 5678").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Edit card").performClick()
        compose.onNodeWithText("Number 2").performScrollTo().assertTextContains("+91 80 2345 6789")
        compose.onNodeWithContentDescription("Remove phone 2").performScrollTo().performClick()
        compose.onNodeWithText("Number 2").performScrollTo().assertTextContains("+44 20 1234 5678")
        compose.onNodeWithText("Save changes").performClick()
        openAcknowledgedCardFromCase("Multiple Phones")
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
    @Test fun invalidWebsiteSaveRevealsFieldAndRetainsDraftUntilCorrected() {
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Full name").performTextInput("Validation Person")
        compose.onNodeWithText("Website 1").performScrollTo().performTextInput("person@example.test")
        compose.onNodeWithText("Save card").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Enter a website here; email addresses belong above.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Website 1").assertIsFocused().performTextReplacement("https://example.test")
        compose.onNodeWithText("Save card").performClick()
        openAcknowledgedCardFromCase("Validation Person")
        compose.onNodeWithText("Validation Person").assertExists()
    }
    @Test fun secondaryEmailRejectsSaveAndFocusesOnlyAfterExplicitSave() {
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Full name").performTextInput("Repeated channel person")
        compose.onNodeWithText("Email 1").performScrollTo().performTextInput("valid@example.test")
        compose.onNodeWithText("Add another email").performScrollTo().performClick()
        compose.onNodeWithText("Email 2").performScrollTo().performTextInput("missing-at.example.test")
        compose.onNodeWithText("Save card").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Email 2").assertIsFocused().performTextReplacement("second@example.test")
        compose.onNodeWithText("Save card").performClick()
        openAcknowledgedCardFromCase("Repeated channel person")
        compose.onNodeWithText("second@example.test").performScrollTo().assertIsDisplayed()
    }
}
