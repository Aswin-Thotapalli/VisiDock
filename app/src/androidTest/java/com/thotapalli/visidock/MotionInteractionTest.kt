package com.thotapalli.visidock

import androidx.compose.foundation.layout.Column
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
