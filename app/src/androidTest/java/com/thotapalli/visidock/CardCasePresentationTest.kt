package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
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
        compose.setContent {VisiDockTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {Box(Modifier.fillMaxSize()) {
            CardCase(cards,focused,{focused=it},{opened=it.id},{chosen->cards=cards.map {if(it.id==chosen.id) it.copy(favorite=!it.favorite) else it}},false,ownerName="Aswin",image={Text("Photo ${it.id}")})
        }}}}
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

    @Test fun partialBrowseAndReverseExchangeLayersBehindTheCaseLip() {
        val cards=listOf(Card(id="a",name="Ada"),Card(id="b",name="Bea"),Card(id="c",name="Cora"))
        var focused by mutableStateOf<String?>("a")
        var opened:String?=null
        var pixelsPerDp=1f
        var backdrop=0
        val colors=mapOf("a" to Color(0xFFFF7043),"b" to Color(0xFF14B88B),"c" to Color(0xFF438CF0))
        compose.setContent {VisiDockTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {Box(Modifier.fillMaxSize()) {
            pixelsPerDp=LocalDensity.current.density
            backdrop=MaterialTheme.colorScheme.background.toArgb()
            CardCase(cards,focused,{focused=it},{opened=it.id},{},false,modifier=Modifier.width(320.dp),ownerName="Aswin",image={card->
                Box(Modifier.fillMaxSize().background(colors.getValue(card.id)),contentAlignment=Alignment.Center) {
                    Text("${card.name} · physical sheet",color=Color.White)
                }
            })
        }}}}
        val case=compose.onNodeWithContentDescription("Card case")
        case.assertWidthIsEqualTo(320.dp)
        // Surface propagates minimum constraints; the fixture Box intentionally
        // releases them. Derive the gesture from the measured case nonetheless,
        // rather than assuming a requested width became the actual layout width.
        val caseWidth=case.fetchSemanticsNode().boundsInRoot.width
        val cardWidth=minOf(caseWidth-64f*pixelsPerDp,340f*pixelsPerDp)
        val cardHeight=cardWidth/1.65f
        fun paintedTop(distance:Float):Float {
            val pose=CaseBrowseGeometry.pose(distance,cardHeight/pixelsPerDp)
            return CaseBrowseGeometry.CARD_BASELINE*pixelsPerDp-cardHeight+pose.offsetY*pixelsPerDp+
                (cardHeight+6f*pixelsPerDp)*(1f-pose.scale)/2f
        }
        val rearProbe=(paintedTop(1f)+paintedTop(-.55f))/2f
        val faceProbe=CaseBrowseGeometry.CARD_BASELINE*pixelsPerDp-cardHeight*.55f
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        fun position()=case.fetchSemanticsNode().config[CaseBrowsePosition]
        fun moveTo(progress:Float) {
            val before=position()
            case.performTouchInput {moveBy(Offset((before-progress)*cardWidth,0f),delayMillis=100)}
            compose.waitForIdle()
            assertEquals(progress,position(),.025f)
        }
        fun capture(label:String,expectBea:Boolean=false) {
            val bounds=case.fetchSemanticsNode().boundsInRoot
            val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
            try {
                if(expectBea) {
                    val pixel=bitmap.getPixel(bounds.center.x.toInt(),(bounds.top+faceProbe).toInt())
                    assertEquals("The same incoming face must remain visible through the layer exchange",0xFF14B88B.toInt(),pixel)
                    // Page2 starts decoding at this crossing. Its former white
                    // placeholder and blue photograph must BOTH stay below leather.
                    val rear=bitmap.getPixel(bounds.center.x.toInt(),(bounds.top+rearProbe).toInt())
                    assertTrue("A distant undecoded/decoded sheet is exposed at the window boundary",
                        kotlin.math.abs(android.graphics.Color.red(rear)-android.graphics.Color.red(backdrop))<40 &&
                        kotlin.math.abs(android.graphics.Color.green(rear)-android.graphics.Color.green(backdrop))<40 &&
                        kotlin.math.abs(android.graphics.Color.blue(rear)-android.graphics.Color.blue(backdrop))<40)
                }
                val target=File(context.getExternalFilesDir(null),"ui-review/case-browse-$label.png").apply {parentFile?.mkdirs()}
                target.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            } finally {bitmap.recycle()}
        }
        case.performTouchInput {down(Offset(width*.85f,height*.3f));moveBy(Offset(-32*pixelsPerDp,0f),delayMillis=100)}
        moveTo(.25f);capture("forward-quarter")
        moveTo(.45f);capture("before-layer-exchange",true)
        moveTo(.55f)
        compose.onNodeWithText("Bea",substring=false).assertIsDisplayed()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onNodeWithText("Open card").assertIsNotEnabled()
        compose.runOnIdle {assertEquals("a",focused);assertEquals(null,opened)}
        capture("after-layer-exchange",true)
        moveTo(.75f);capture("forward-three-quarters")
        moveTo(.55f);capture("reverse-before-exchange",true)
        moveTo(.45f)
        compose.onNodeWithText("Ada",substring=false).assertIsDisplayed()
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithText("Open card").assertIsNotEnabled()
        capture("reverse-after-exchange",true)
        moveTo(.25f);capture("reverse-quarter")
        moveTo(0f);case.performTouchInput {up()}
        compose.waitForIdle()
        compose.runOnIdle {assertEquals("a",focused)}
        compose.onNodeWithText("Open card").performClick()
        compose.runOnIdle {assertEquals("a",opened)}
    }

    @Test fun narrowLargeTextKeepsActionsReachableAndImageBudgetBounded() {
        val cards=(1..20).map {Card(id="$it",name="Person $it")}
        var focused by mutableStateOf<String?>("10")
        var opened:String?=null
        val decoded=mutableSetOf<String>()
        compose.setContent {VisiDockTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {Box(Modifier.fillMaxSize()) {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    CardCase(cards,focused,{focused=it},{opened=it.id},{},false,image={card->
                        DisposableEffect(card.id) {decoded.add(card.id);onDispose {decoded.remove(card.id)}}
                        Text("Photo ${card.id}")
                    })
                }
            }
        }}}}
        compose.onNodeWithContentDescription("Card case").assertWidthIsEqualTo(320.dp)
        compose.onNodeWithText("Open card").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals("10",opened);assertTrue(decoded.size<=3)}
        compose.onNodeWithText("Favorite",substring=false).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Next card").performScrollTo().assertIsEnabled()
    }
}
