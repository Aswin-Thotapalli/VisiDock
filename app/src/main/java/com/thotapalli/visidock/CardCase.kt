@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.thotapalli.visidock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val CaseBrowsePosition=SemanticsPropertyKey<Float>("CaseBrowsePosition")

private val CaseInk=Color(0xFF071D3D)
private val CaseRim=Color(0xFF82BCE7)

/** A bounded, lazy card file. Only the focused card and its immediate neighbours decode images. */
@Composable internal fun CardCase(
    cards:List<Card>, focusedId:String?, onFocused:(String)->Unit, onOpen:(Card)->Unit,
    onFavorite:(Card)->Unit, busy:Boolean, modifier:Modifier=Modifier,ownerName:String="",
    image:@Composable (Card)->Unit,
) {
    if(cards.isEmpty()) return
    val scope=rememberCoroutineScope()
    val pager=rememberPagerState(initialPage=cards.indexOfFirst {it.id==focusedId}.coerceAtLeast(0),pageCount={cards.size})
    val latestCards by rememberUpdatedState(cards)
    val latestOnFocused by rememberUpdatedState(onFocused)
    var publishedId by remember {mutableStateOf(focusedId)}
    var reconciledIds by remember {mutableStateOf(emptyList<String>())}
    var focusRequestEpoch by remember {mutableIntStateOf(0)}
    var reconciling by remember {mutableStateOf(true)}
    fun publish(id:String) {
        publishedId=id
        latestOnFocused(id)
    }
    LaunchedEffect(cards.map {it.id},focusedId) {
        val ids=cards.map {it.id}
        // A parent's echo of our settled selection is an acknowledgement, not a
        // new instruction to scroll. Treating it as a command races pager layout.
        if(ids==reconciledIds && focusedId==publishedId) return@LaunchedEffect
        focusRequestEpoch++
        reconciling=true
        try {
            // External selection (including return from details) waits for a finger
            // gesture to end rather than getting dropped or interrupting that gesture.
            snapshotFlow {pager.isScrollInProgress}.first {!it}
            val wanted=cards.indexOfFirst {it.id==focusedId}.takeIf {it>=0}
                ?: pager.settledPage.coerceIn(cards.indices)
            // Reanchor even at the same numeric index: list deletion can retain
            // a different key at that index until the next pager measurement.
            pager.scrollToPage(wanted)
            reconciledIds=ids
            publish(cards[wanted].id)
        } finally {reconciling=false}
    }
    LaunchedEffect(pager) {
        // Card data edits must not restart the observer with an obsolete page index.
        snapshotFlow {if(pager.isScrollInProgress || reconciling) null else pager.settledPage}
            .distinctUntilChanged().collect {page->page?.let {latestCards.getOrNull(it)}?.let {publish(it.id)}}
    }
    fun browseTo(id:String) {
        scope.launch {
            val requestEpoch=focusRequestEpoch
            reconciling=true
            try {
                val target=latestCards.indexOfFirst {it.id==id}
                if(target<0) return@launch
                pager.animateScrollToPage(target)
                if(requestEpoch==focusRequestEpoch) latestCards.getOrNull(pager.settledPage)?.let {publish(it.id)}
            } finally {if(requestEpoch==focusRequestEpoch) reconciling=false}
        }
    }
    val current=cards[pager.settledPage.coerceIn(cards.indices)]
    // Preview identity follows the dominant visible sheet. Commands still use
    // the settled card and remain disabled until the physical gesture finishes.
    val visiblePage=(if(pager.isScrollInProgress) pager.currentPage else pager.settledPage).coerceIn(cards.indices)
    val displayedCard=cards[visiblePage]
    val actionable=!busy && !pager.isScrollInProgress && !reconciling
    Column(modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(326.dp)) {
            val cardWidth=minOf(maxWidth-64.dp,340.dp)
            val cardHeight=cardWidth/1.65f
            val gutter=(maxWidth-cardWidth)/2
            Canvas(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
                drawCaseWell()
            }
            // Foundation 1.7.6 includes trailing pageSpacing in max-scroll math.
            // Negative spacing therefore makes the last card unreachable. Keep
            // logical pages non-overlapping and overlap only their visual layers.
            HorizontalPager(state=pager,pageSize=PageSize.Fixed(cardWidth),contentPadding=PaddingValues(horizontal=gutter),pageSpacing=0.dp,beyondViewportPageCount=1,key={cards[it].id},modifier=Modifier.fillMaxWidth().height(270.dp).drawWithContent {
                // Side walls contain the parked paper stack. Keep the upper edge
                // open so an extracted card can rise without a horizontal cutoff.
                clipRect(left=26.dp.toPx(),top=-size.height,right=size.width-26.dp.toPx(),bottom=size.height+48.dp.toPx()) {this@drawWithContent.drawContent()}
            }.semantics {contentDescription="Card case";stateDescription="${pager.currentPage+1} of ${cards.size}";this[CaseBrowsePosition]=pager.currentPage+pager.currentPageOffsetFraction}) {page->
                val card=cards[page]
                val distance=(pager.currentPage-page)+pager.currentPageOffsetFraction
                val leaf=CaseBrowseGeometry.pose(distance,cardHeight.value)
                val proximity=leaf.layer
                val touch=remember(card.id) {MutableInteractionSource()}
                val pressed by touch.collectIsPressedAsState()
                val pressure by animateFloatAsState(if(pressed) 1f else 0f,
                    if(pressed) DockMotion.spec(70) else DockMotion.settle(430f,.82f),label="Paper contact")
                Box(Modifier.zIndex(proximity).fillMaxWidth().padding(top=CaseBrowseGeometry.CARD_BASELINE.dp-cardHeight).graphicsLayer {
                    // The layer exchange happens behind the opaque leather pocket,
                    // never while two large photograph faces overlap in open view.
                    translationX=distance*cardWidth.toPx()+leaf.offsetX.dp.toPx()
                    translationY=leaf.offsetY.dp.toPx()
                    scaleX=leaf.scale*(1f-pressure*.006f);scaleY=scaleX
                }.semantics(mergeDescendants=true) { if(page!=pager.currentPage) invisibleToUser() }
                    .clickable(interactionSource=touch,indication=null,enabled=actionable,role=Role.Button,onClickLabel="Pull out ${card.displayLabel}") {if(page==pager.currentPage) onOpen(card) else browseTo(card.id)}) {
                    // The paper edge stays attached to its card as it moves through the case.
                    Box(Modifier.padding(top=4.dp).fillMaxWidth().height(cardHeight+2.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFAABDCF)).materialGrain(alpha=.65f))
                    Surface(Modifier.fillMaxWidth().height(cardHeight).physicalSurface(depthDp=3f+proximity*5f,pressedFraction={pressure},shape=RoundedCornerShape(12.dp),material=PhysicalMaterial.Paper),shape=RoundedCornerShape(12.dp),color=Color(0xFFF0F5FA),tonalElevation=0.dp) {
                        // The window changes at distance1.5; geometry has already
                        // buried this leaf by1.3, so neither admission nor eviction
                        // changes an exposed paper edge. Keep at most three images.
                        if(abs(page-pager.currentPage)<=1) image(card)
                    }
                    Canvas(Modifier.fillMaxWidth().height(cardHeight)) {
                        drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.52f),Color.Transparent,CaseInk.copy(alpha=.13f))),cornerRadius=CornerRadius(12.dp.toPx()),style=Stroke(1.dp.toPx()))
                    }
                }
            }
            // Foreground lip really occludes the lower edges; it is not a background illustration.
            Canvas(Modifier.align(Alignment.BottomCenter).padding(horizontal=16.dp).fillMaxWidth().height(114.dp).testTag("case-lip").pointerInput(Unit) {
                // The opaque shell is a physical boundary, not a transparent hit area.
                awaitPointerEventScope {while(true) {awaitPointerEvent().changes.forEach {it.consume()}}}
            }) {
                drawCaseLip()
            }
            CaseInscription(ownerName,Modifier.align(Alignment.BottomCenter).padding(horizontal=42.dp).padding(bottom=25.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
            DockIconButton(enabled=actionable && pager.settledPage>0,onClick={cards.getOrNull(pager.settledPage-1)?.let {browseTo(it.id)}}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Previous card")}
            AnimatedContent(displayedCard.id,modifier=Modifier.weight(1f),label="Case label",transitionSpec={fadeIn(DockMotion.spec(150)) togetherWith fadeOut(DockMotion.spec(80))}) {id->
                val item=cards.firstOrNull {it.id==id} ?: displayedCard
                Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxWidth()) {
                    Text(item.displayLabel,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(listOf(item.role,item.company).filter(String::isNotBlank).joinToString(" · "),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DockIconButton(enabled=actionable && pager.settledPage<cards.lastIndex,onClick={cards.getOrNull(pager.settledPage+1)?.let {browseTo(it.id)}}) {Icon(Icons.AutoMirrored.Outlined.ArrowForward,"Next card")}
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            DockOutlinedButton(enabled=actionable,onClick={onOpen(current)}) {Icon(Icons.Outlined.OpenInFull,null,Modifier.size(16.dp));Spacer(Modifier.width(8.dp));Text("Open card")}
            DockFilterChip(selected=displayedCard.favorite,onClick={onFavorite(current)},enabled=actionable,label={Text(if(displayedCard.favorite) "Favorited" else "Favorite")})
        }
        Text("${visiblePage+1} / ${cards.size}",Modifier.padding(bottom=16.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun EmptyCardCase(onAdd:()->Unit,ownerName:String="") {
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(326.dp)) {
            Canvas(Modifier.fillMaxSize().padding(horizontal=16.dp)) {drawCaseWell()}
            DockOutlinedButton(onClick=onAdd,modifier=Modifier.align(Alignment.TopCenter).padding(top=128.dp),
                colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFFD5E8F8))) {Text("Add your first card")}
            Canvas(Modifier.align(Alignment.BottomCenter).padding(horizontal=16.dp).fillMaxWidth().height(114.dp)) {drawCaseLip()}
            CaseInscription(ownerName,Modifier.align(Alignment.BottomCenter).padding(horizontal=42.dp).padding(bottom=25.dp))
        }
        Text("Your card case",style=MaterialTheme.typography.titleMedium)
        Text("Scan a card or add its details.",Modifier.padding(top=8.dp,bottom=24.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Recessed tooling: upper cavity shadow and lower reflected edge, not white ink. */
@Composable private fun CaseInscription(ownerName:String,modifier:Modifier=Modifier) {
    val label=remember(ownerName) {caseInscription(ownerName)}
    Box(modifier.fillMaxWidth().height(32.dp).semantics {contentDescription=label}.drawWithCache {
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
            textSize=14.sp.toPx()
        }
        var visibleLabel=label
        val measured=paint.measureText(label)
        val minimumSize=12.sp.toPx()
        if(measured>size.width && measured>0f) {
            val fittedSize=paint.textSize*size.width/measured
            if(fittedSize>=minimumSize) paint.textSize=fittedSize
            else {
                // Leather tooling has a readable minimum. A long profile name
                // becomes a personal first-name imprint, never microscopic type.
                paint.textSize=minimumSize
                val firstName=ownerName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                visibleLabel=caseInscription(firstName)
                if(paint.measureText(visibleLabel)>size.width) {
                    val suffix=if(firstName.endsWith("s",ignoreCase=true)) "’ VisiDock" else "’s VisiDock"
                    val available=(size.width-paint.measureText(suffix)).coerceAtLeast(0f)
                    val shortened=android.text.TextUtils.ellipsize(firstName,android.text.TextPaint(paint),available,android.text.TextUtils.TruncateAt.END).toString()
                    visibleLabel=if(shortened.isBlank()) "VisiDock" else shortened+suffix
                }
            }
        }
        val x=(size.width-paint.measureText(visibleLabel))/2
        val y=(size.height-paint.fontMetrics.ascent-paint.fontMetrics.descent)/2
        val glyphs=android.graphics.Path().apply {paint.getTextPath(visibleLabel,0,visibleLabel.length,x,y,this)}.asComposePath()
        onDrawBehind {
            // Pressed leather loses its raised grain under the die. Narrow cavity
            // edges share the shell's upper-left light, rather than outlining ink.
            translate(left=.25f.dp.toPx(),top=.45f.dp.toPx()) {drawPath(glyphs,Color(0xFF7898AA).copy(alpha=.30f))}
            translate(left=-.18f.dp.toPx(),top=-.35f.dp.toPx()) {drawPath(glyphs,Color(0xFF0B223B).copy(alpha=.68f))}
            drawPath(glyphs,Brush.linearGradient(listOf(Color(0xFF173650),Color(0xFF24455E)),start=Offset(x,y-12.sp.toPx()),end=Offset(x+size.width*.35f,y+3.sp.toPx())))
            // A compressed micro-pore finish remains, without the surrounding pebble relief.
            drawPath(glyphs,SurfaceTextures.paper,alpha=.14f)
        }
    })
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCaseWell() {
                val top=88.dp.toPx();val bottom=size.height-20.dp.toPx()
                // Diffuse navy contact shadow, then a bevelled rear shell and recessed well.
                drawOval(Brush.radialGradient(listOf(CaseInk.copy(alpha=.25f),Color.Transparent),center=Offset(size.width/2,bottom),radius=size.width*.56f),topLeft=Offset(-12.dp.toPx(),bottom-24.dp.toPx()),size=Size(size.width+24.dp.toPx(),48.dp.toPx()))
                // Stacked edge paint and gusset give the shell thickness before its face.
                for(layer in 6 downTo 1) drawRoundRect(Color(0xFF09223E),topLeft=Offset(layer*.28f.dp.toPx(),top+layer*.65f.dp.toPx()),size=Size(size.width-layer*.56f.dp.toPx(),bottom-top),cornerRadius=CornerRadius(25.dp.toPx()))
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFF355E7B),CaseInk,Color(0xFF193B58))),topLeft=Offset(0f,top),size=Size(size.width,bottom-top),cornerRadius=CornerRadius(25.dp.toPx()))
                drawRoundRect(SurfaceTextures.shell,topLeft=Offset(0f,top),size=Size(size.width,bottom-top),cornerRadius=CornerRadius(25.dp.toPx()),alpha=.75f)
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF04152E),Color(0xFF14365C))),topLeft=Offset(9.dp.toPx(),top+9.dp.toPx()),size=Size(size.width-18.dp.toPx(),bottom-top-18.dp.toPx()),cornerRadius=CornerRadius(19.dp.toPx()))
                drawRoundRect(SurfaceTextures.lining,topLeft=Offset(9.dp.toPx(),top+9.dp.toPx()),size=Size(size.width-18.dp.toPx(),bottom-top-18.dp.toPx()),cornerRadius=CornerRadius(19.dp.toPx()),alpha=.65f)
                // Interior occlusion hugs the rolled rim and fades into the lining.
                for(layer in 1..5) drawRoundRect(CaseInk.copy(alpha=.045f),topLeft=Offset((9+layer).dp.toPx(),top+(9+layer).dp.toPx()),size=Size(size.width-(18+layer*2).dp.toPx(),bottom-top-(18+layer*2).dp.toPx()),cornerRadius=CornerRadius(19.dp.toPx()),style=Stroke((7-layer).dp.toPx()))
                drawRoundRect(Brush.linearGradient(listOf(CaseRim.copy(alpha=.75f),CaseRim.copy(alpha=.08f),CaseRim.copy(alpha=.38f))),topLeft=Offset(.5.dp.toPx(),top),size=Size(size.width-1.dp.toPx(),bottom-top),cornerRadius=CornerRadius(25.dp.toPx()),style=Stroke(1.dp.toPx()))
                // A rolled leather edge catches light only on the upper-left side.
                drawLine(Color(0xFF88AFC2).copy(alpha=.38f),Offset(25.dp.toPx(),top+3.dp.toPx()),Offset(size.width-25.dp.toPx(),top+3.dp.toPx()),.8f.dp.toPx())
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCaseLip() {
                val inset=6.dp.toPx();val notch=11.dp.toPx();val rim=Path().apply {
                    moveTo(0f,16.dp.toPx());quadraticBezierTo(0f,0f,20.dp.toPx(),0f)
                    lineTo(size.width*.33f,0f);cubicTo(size.width*.40f,0f,size.width*.40f,notch,size.width*.5f,notch)
                    cubicTo(size.width*.60f,notch,size.width*.60f,0f,size.width*.67f,0f)
                    lineTo(size.width-20.dp.toPx(),0f);quadraticBezierTo(size.width,0f,size.width,16.dp.toPx())
                    lineTo(size.width,size.height-24.dp.toPx());quadraticBezierTo(size.width,size.height-4.dp.toPx(),size.width-24.dp.toPx(),size.height-4.dp.toPx())
                    lineTo(24.dp.toPx(),size.height-4.dp.toPx());quadraticBezierTo(0f,size.height-4.dp.toPx(),0f,size.height-24.dp.toPx());close()
                }
                // Multiple painted edge layers make a real silhouette below the face.
                translate(top=3.dp.toPx()) {drawPath(rim,Color(0xFF061A31))}
                translate(top=1.5f.dp.toPx()) {drawPath(rim,Color(0xFF27435A))}
                drawPath(rim,Brush.linearGradient(listOf(Color(0xFF365E79),Color(0xFF234968),Color(0xFF132F4B)),start=Offset(0f,0f),end=Offset(size.width,size.height)))
                drawPath(rim,SurfaceTextures.shell,alpha=.95f)
                drawPath(rim,Brush.verticalGradient(listOf(Color(0xFF93B2C2).copy(alpha=.55f),CaseInk.copy(alpha=.60f))),style=Stroke(1.2f.dp.toPx()))
                // Thread lies in a pressed channel, inset from the burnished edge.
                val seam=Path().apply {
                    moveTo(10.dp.toPx(),25.dp.toPx());lineTo(10.dp.toPx(),size.height-27.dp.toPx())
                    quadraticBezierTo(10.dp.toPx(),size.height-15.dp.toPx(),25.dp.toPx(),size.height-15.dp.toPx())
                    lineTo(size.width-25.dp.toPx(),size.height-15.dp.toPx())
                    quadraticBezierTo(size.width-10.dp.toPx(),size.height-15.dp.toPx(),size.width-10.dp.toPx(),size.height-27.dp.toPx())
                    lineTo(size.width-10.dp.toPx(),25.dp.toPx())
                }
                drawPath(seam,CaseInk.copy(alpha=.42f),style=Stroke(2.4f.dp.toPx()))
                translate(top=.65f.dp.toPx()) {drawPath(seam,Color(0xFF567A90).copy(alpha=.25f),style=Stroke(.7f.dp.toPx()))}
                drawPath(seam,Color(0xFF8BABB8).copy(alpha=.47f),style=Stroke(.75f.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(2.5f.dp.toPx(),3.2f.dp.toPx()))))
                // Tooling along the opening replaces the unrelated luminous hardware bar.
                val opening=Path().apply {
                    moveTo(24.dp.toPx(),5.dp.toPx());lineTo(size.width*.33f,5.dp.toPx())
                    cubicTo(size.width*.4f,5.dp.toPx(),size.width*.4f,notch+5.dp.toPx(),size.width*.5f,notch+5.dp.toPx())
                    cubicTo(size.width*.6f,notch+5.dp.toPx(),size.width*.6f,5.dp.toPx(),size.width*.67f,5.dp.toPx())
                    lineTo(size.width-24.dp.toPx(),5.dp.toPx())
                }
                drawPath(opening,CaseInk.copy(alpha=.34f),style=Stroke(1.1f.dp.toPx()))
}
