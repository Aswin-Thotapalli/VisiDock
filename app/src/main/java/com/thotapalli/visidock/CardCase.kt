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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

private val CaseInk=Color(0xFF071D3D)
private val CaseRim=Color(0xFF82BCE7)

/** A bounded, lazy card file. Only the focused card and its immediate neighbours decode images. */
@Composable internal fun CardCase(
    cards:List<Card>, focusedId:String?, onFocused:(String)->Unit, onOpen:(Card)->Unit,
    onFavorite:(Card)->Unit, busy:Boolean, modifier:Modifier=Modifier,
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
            HorizontalPager(state=pager,pageSize=PageSize.Fixed(cardWidth),contentPadding=PaddingValues(horizontal=gutter),pageSpacing=0.dp,beyondViewportPageCount=1,key={cards[it].id},modifier=Modifier.fillMaxWidth().height(270.dp).semantics {contentDescription="Card case";stateDescription="${pager.currentPage+1} of ${cards.size}"}) {page->
                val card=cards[page]
                val distance=(pager.currentPage-page)+pager.currentPageOffsetFraction
                val proximity=1f-abs(distance).coerceIn(0f,1f)
                Box(Modifier.zIndex(proximity).fillMaxWidth().padding(top=228.dp-cardHeight).graphicsLayer {
                    translationX=distance*cardWidth.toPx()*.48f
                    translationY=(1f-proximity)*45.dp.toPx()
                    rotationY=distance.coerceIn(-1.5f,1.5f)*-18f
                    rotationZ=distance.coerceIn(-1.5f,1.5f)*-3f
                    scaleX=.88f+.12f*proximity;scaleY=scaleX
                    cameraDistance=18*density
                }.semantics(mergeDescendants=true) { if(page!=pager.currentPage) invisibleToUser() }
                    .clickable(enabled=actionable,onClickLabel="Pull out ${card.displayLabel}") {if(page==pager.currentPage) onOpen(card) else browseTo(card.id)}) {
                    // The paper edge stays attached to its card as it moves through the case.
                    Box(Modifier.padding(top=4.dp).fillMaxWidth().height(cardHeight+2.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFAABDCF)).materialGrain(alpha=.65f))
                    Surface(Modifier.fillMaxWidth().height(cardHeight),shape=RoundedCornerShape(12.dp),color=Color(0xFFF0F5FA),tonalElevation=0.dp) {
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
            Text("VISIDOCK",Modifier.align(Alignment.BottomCenter).padding(bottom=23.dp),style=MaterialTheme.typography.labelSmall,color=Color(0xFFA9C7DE))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
            DockIconButton(enabled=actionable && pager.settledPage>0,onClick={cards.getOrNull(pager.settledPage-1)?.let {browseTo(it.id)}}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Previous card")}
            AnimatedContent(current.id,modifier=Modifier.weight(1f),label="Case label",transitionSpec={fadeIn(DockMotion.spec(150)) togetherWith fadeOut(DockMotion.spec(80))}) {id->
                val item=cards.firstOrNull {it.id==id} ?: current
                Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxWidth()) {
                    Text(item.displayLabel,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(listOf(item.role,item.company).filter(String::isNotBlank).joinToString(" · "),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DockIconButton(enabled=actionable && pager.settledPage<cards.lastIndex,onClick={cards.getOrNull(pager.settledPage+1)?.let {browseTo(it.id)}}) {Icon(Icons.AutoMirrored.Outlined.ArrowForward,"Next card")}
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            DockOutlinedButton(enabled=actionable,onClick={onOpen(current)}) {Icon(Icons.Outlined.OpenInFull,null,Modifier.size(16.dp));Spacer(Modifier.width(8.dp));Text("Open card")}
            DockFilterChip(selected=current.favorite,onClick={onFavorite(current)},enabled=actionable,label={Text(if(current.favorite) "Favorited" else "Favorite")})
        }
        Text("${pager.settledPage+1} / ${cards.size}",Modifier.padding(bottom=16.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun EmptyCardCase(onAdd:()->Unit) {
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(326.dp)) {
            Canvas(Modifier.fillMaxSize().padding(horizontal=16.dp)) {drawCaseWell()}
            DockOutlinedButton(onClick=onAdd,modifier=Modifier.align(Alignment.TopCenter).padding(top=128.dp),
                colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFFD5E8F8))) {Text("Add your first card")}
            Canvas(Modifier.align(Alignment.BottomCenter).padding(horizontal=16.dp).fillMaxWidth().height(114.dp)) {drawCaseLip()}
            Text("VISIDOCK",Modifier.align(Alignment.BottomCenter).padding(bottom=23.dp),style=MaterialTheme.typography.labelSmall,color=Color(0xFFA9C7DE))
        }
        Text("Your card case",style=MaterialTheme.typography.titleMedium)
        Text("Scan a card or add its details.",Modifier.padding(top=8.dp,bottom=24.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCaseWell() {
                val top=88.dp.toPx();val bottom=size.height-20.dp.toPx()
                // Diffuse navy contact shadow, then a bevelled rear shell and recessed well.
                drawOval(Brush.radialGradient(listOf(CaseInk.copy(alpha=.25f),Color.Transparent),center=Offset(size.width/2,bottom),radius=size.width*.56f),topLeft=Offset(-12.dp.toPx(),bottom-24.dp.toPx()),size=Size(size.width+24.dp.toPx(),48.dp.toPx()))
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFF315582),CaseInk,Color(0xFF193B65))),topLeft=Offset(0f,top),size=Size(size.width,bottom-top),cornerRadius=CornerRadius(25.dp.toPx()))
                drawRoundRect(SurfaceTextures.shell,topLeft=Offset(0f,top),size=Size(size.width,bottom-top),cornerRadius=CornerRadius(25.dp.toPx()),alpha=.75f)
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF04152E),Color(0xFF14365C))),topLeft=Offset(9.dp.toPx(),top+9.dp.toPx()),size=Size(size.width-18.dp.toPx(),bottom-top-18.dp.toPx()),cornerRadius=CornerRadius(19.dp.toPx()))
                drawRoundRect(SurfaceTextures.lining,topLeft=Offset(9.dp.toPx(),top+9.dp.toPx()),size=Size(size.width-18.dp.toPx(),bottom-top-18.dp.toPx()),cornerRadius=CornerRadius(19.dp.toPx()),alpha=.65f)
                drawRoundRect(Brush.linearGradient(listOf(CaseRim.copy(alpha=.75f),CaseRim.copy(alpha=.08f),CaseRim.copy(alpha=.38f))),topLeft=Offset(.5.dp.toPx(),top),size=Size(size.width-1.dp.toPx(),bottom-top),cornerRadius=CornerRadius(25.dp.toPx()),style=Stroke(1.dp.toPx()))
                // Fine seam lines are fixed material detail, not a continuously running effect.
                for(i in 1..4) drawLine(Color(0xFF5285AC).copy(alpha=.15f),Offset(10.dp.toPx(),top+(10+i*3).dp.toPx()),Offset(size.width-10.dp.toPx(),top+(10+i*3).dp.toPx()),.5.dp.toPx())
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
                drawPath(rim,Brush.linearGradient(listOf(Color(0xFF365F8E),Color(0xFF173B67),Color(0xFF0B254B)),start=Offset(0f,0f),end=Offset(size.width,size.height)))
                drawPath(rim,SurfaceTextures.shell,alpha=.75f)
                drawPath(rim,Brush.verticalGradient(listOf(CaseRim.copy(alpha=.70f),CaseInk.copy(alpha=.15f))),style=Stroke(1.dp.toPx()))
                drawLine(Color(0xFF6BA5C4).copy(alpha=.2f),Offset(24.dp.toPx(),size.height-inset-7.dp.toPx()),Offset(size.width-24.dp.toPx(),size.height-inset-7.dp.toPx()),.6.dp.toPx())
                // A machined cyan accent and its recessed groove locate the opening.
                drawRoundRect(CaseInk.copy(alpha=.6f),Offset(size.width*.43f,30.dp.toPx()),Size(size.width*.14f,5.dp.toPx()),CornerRadius(3.dp.toPx()))
                drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF426B97),Color(0xFF91E3DF),Color(0xFF426B97))),Offset(size.width*.445f,31.dp.toPx()),Size(size.width*.11f,1.dp.toPx()),CornerRadius(1.dp.toPx()))
}
