package com.thotapalli.visidock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Motion must preserve real input semantics, including interruption and keyboard submission. */
class MotionInteractionTest {
    @get:Rule val compose=createComposeRule()

    @Test fun cancelledPressDoesNotCommitAndRapidPressesCommitExactlyOnce() {
        var commits=0
        var loading by mutableStateOf(false)
        compose.setContent {VisiDockTheme {
            DockButton(onClick={commits++},loading=loading,modifier=Modifier.testTag("commit")) {Text("Save")}
        }}
        val button=compose.onNodeWithTag("commit")
        button.performTouchInput {down(center);moveTo(Offset(-100f,-100f),delayMillis=180);up()}
        compose.runOnIdle {assertEquals(0,commits)}
        compose.mainClock.autoAdvance=false
        try {
            repeat(3) {button.performTouchInput {click()}}
            compose.runOnIdle {assertEquals(3,commits)}
        } finally {compose.mainClock.autoAdvance=true}
        compose.runOnIdle {loading=true}
        button.assertIsNotEnabled().performTouchInput {click()}
        compose.runOnIdle {assertEquals(3,commits);loading=false}
        button.assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(4,commits)}
    }

    @Test fun physicalButtonsPreserveRequestedSizesAndAccessibleTouchSpace() {
        var presses=0
        compose.setContent {VisiDockTheme {Column {
            DockButton({presses++},Modifier.testTag("normal")) {Text("Save")}
            DockOutlinedButton({presses++},Modifier.width(220.dp).height(64.dp).testTag("large")) {Text("Import")}
            DockFilledIconButton({presses++},Modifier.testTag("icon")) {Text("+")}
        }}}
        // The decorated plate is 40dp; its independent, invisible touch target is 48dp.
        // Asserting the plate at 48dp would reintroduce the mismatched button border.
        compose.onNodeWithTag("normal").assertHeightIsEqualTo(40.dp).assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("icon").assertHeightIsEqualTo(40.dp).assertWidthIsEqualTo(40.dp)
            .assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp)
        compose.onNodeWithTag("large").assertHeightIsEqualTo(64.dp).assertWidthIsEqualTo(220.dp)
        // Edge taps in the invisible touch margin must still reach the control.
        compose.onNodeWithTag("normal").performTouchInput {click(Offset(center.x,-2.dp.toPx()))}
        compose.onNodeWithTag("icon").performTouchInput {click(Offset(center.x,-2.dp.toPx()))}
        compose.runOnIdle {assertEquals(2,presses)}
    }

    @Test fun selectionRowAndPlateShareOneAccessibleStateAndOneCallback() {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var changes=0
        compose.setContent {VisiDockTheme {Column {
            DockSelectionRow("My own card",checked,{checked=it;changes++},Modifier.testTag("selection"),enabled)
            DockCheckbox(checked,{checked=it;changes++},Modifier.testTag("plate"),enabled)
        }}}
        compose.onNodeWithTag("selection").assertIsOff()
        compose.onNodeWithText("My own card").performClick()
        compose.onNodeWithTag("selection").assertIsOn()
        compose.onNodeWithTag("plate").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {assertEquals(2,changes);enabled=false}
        compose.onNodeWithTag("selection").assertIsNotEnabled().performTouchInput {click()}
        compose.onNodeWithTag("plate").assertIsNotEnabled().performTouchInput {click()}
        compose.runOnIdle {assertEquals(2,changes)}
    }

    @Test fun animatedToggleAndFieldRetainCheckedEditingAndImeSemantics() {
        var checked by mutableStateOf(false)
        var text by mutableStateOf("")
        var submitted=0
        compose.setContent {VisiDockTheme {Column {
            DockSwitch(checked,{checked=it},Modifier.testTag("learning"))
            DockOutlinedTextField(text,{text=it},Modifier.testTag("entry"),label={Text("Name")},
                singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={submitted++}))
        }}}
        compose.onNodeWithTag("learning").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("entry").performClick().performTextInput("Mira")
        compose.onNodeWithTag("entry").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Mira")))
        compose.onNodeWithTag("entry").performImeAction()
        compose.runOnIdle {assertEquals("Mira",text);assertEquals(1,submitted)}
    }
}
