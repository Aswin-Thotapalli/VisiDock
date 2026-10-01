package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CardCasePresentationTest {
    @get:Rule val compose=createComposeRule()

    @Test fun browsingFavoriteDeletionAndExternalReturnPreserveTheSelectedCard() {
        var cards by mutableStateOf(listOf(Card(id="a",name="Ada"),Card(id="b",name="Bea"),Card(id="c",name="Cora")))
        var focused by mutableStateOf<String?>("b")
        var opened:String?=null
        compose.setContent {VisiDockTheme {
            CardCase(cards,focused,{focused=it},{opened=it.id},{chosen->cards=cards.map {if(it.id==chosen.id) it.copy(favorite=!it.favorite) else it}},false,ownerName="Aswin",image={Text("Photo ${it.id}")})
        }}
        compose.onNodeWithContentDescription("Aswin’s VisiDock").assertExists()
        val capture=compose.onRoot().captureToImage().asAndroidBitmap()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val target=File(context.getExternalFilesDir(null),"ui-review/personal-case-imprint.png").apply {parentFile?.mkdirs()}
        try {target.outputStream().use {assertTrue(capture.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {capture.recycle()}

        compose.onNodeWithText("Open card").performClick()
        compose.runOnIdle {assertEquals("b",opened)}
        compose.onNodeWithText("Favorite",substring=false).performClick()
        compose.runOnIdle {assertEquals("b",focused);assertTrue(cards.first {it.id=="b"}.favorite)}
        // Removing an earlier page changes the index, but not the selected identity.
        compose.runOnIdle {cards=cards.filterNot {it.id=="a"}}
        compose.onNodeWithText("Open card").performClick()
        compose.runOnIdle {assertEquals("b",opened);assertEquals("b",focused)}
        compose.onNodeWithContentDescription("Next card").assertIsEnabled().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle {assertEquals("c",focused)}
        compose.onNodeWithText("Open card").performClick()
        compose.runOnIdle {assertEquals("c",opened);focused="b"}
        compose.waitForIdle()
        compose.onNodeWithText("Open card").performClick()
        compose.runOnIdle {assertEquals("b",opened)}
        // Tapping the opaque case shell must not pull out the obscured card.
        compose.runOnIdle {opened=null}
        compose.onNodeWithTag("case-lip").performTouchInput {click()}
        compose.runOnIdle {assertEquals(null,opened)}
    }

    @Test fun narrowLargeTextKeepsActionsReachableAndImageBudgetBounded() {
        val cards=(1..20).map {Card(id="$it",name="Person $it")}
        var focused by mutableStateOf<String?>("10")
        var opened:String?=null
        val decoded=mutableSetOf<String>()
        compose.setContent {VisiDockTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    CardCase(cards,focused,{focused=it},{opened=it.id},{},false,image={card->
                        DisposableEffect(card.id) {decoded.add(card.id);onDispose {decoded.remove(card.id)}}
                        Text("Photo ${card.id}")
                    })
                }
            }
        }}
        compose.onNodeWithText("Open card").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals("10",opened);assertTrue(decoded.size<=3)}
        compose.onNodeWithText("Favorite",substring=false).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Next card").performScrollTo().assertIsEnabled()
    }
}
