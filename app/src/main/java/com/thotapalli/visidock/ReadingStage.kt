package com.thotapalli.visidock

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.abs

/** An ordered visual consumer of processing events. Initial composition always
 * establishes the front, even when processing has already advanced to both sides. */
@Composable internal fun ReadingStage(
    card:Card,vm:VaultViewModel,front:String,back:String?,side:Int,phase:String,active:Boolean,
    regions:List<OcrRegion> = emptyList(),analysisReady:Boolean=false,onPresented:()->Unit={},onCancel:()->Unit,
) {
    BackHandler(enabled=active,onBack=onCancel)
    val latestActive by rememberUpdatedState(active)
    val latestSide by rememberUpdatedState(side)
    val complete by rememberUpdatedState(onPresented)
    val frontState=cardPhoto(card,vm,false,front)
    val backState=if(back!=null) cardPhoto(card,vm,true,back) else null
    val readyFront by rememberUpdatedState(frontState)
    val readyBack by rememberUpdatedState(backState)
    val enabled=ValueAnimator.areAnimatorsEnabled()
    val angle=remember(front,back) {Animatable(0f)}
    val beam=remember(front,back) {Animatable(-.08f)}
    val handoff=remember(front,back) {Animatable(0f)}
    var face by remember(front,back) {mutableIntStateOf(0)}
    var turning by remember(front,back) {mutableStateOf(false)}
    var scanned by remember(front,back) {mutableStateOf(false)}
    val shownBack=angle.value>90f
    LaunchedEffect(front,back,enabled) {
        snapshotFlow {readyFront.loading}.first {!it}
        // Wait for a rendered frame before moving: no back face on initial entry.
        withFrameNanos { }
        beam.animateTo(1.08f,tween(if(enabled) ScanChoreography.SWEEP_MILLIS else 0,easing=LinearEasing))
        while(!ScanChoreography.canLeaveFront(latestSide,back!=null)) {
            if(!enabled) {snapshotFlow {ScanChoreography.canLeaveFront(latestSide,back!=null)}.first {it};break}
            beam.snapTo(-.08f)
            beam.animateTo(1.08f,tween(ScanChoreography.SWEEP_MILLIS,easing=LinearEasing))
        }
        if(back!=null) {
            snapshotFlow {readyBack?.loading==false}.first {it}
            turning=true
            angle.animateTo(180f,tween(if(enabled) ScanChoreography.TURN_MILLIS else 0,easing=CubicBezierEasing(.3f,0f,.2f,1f)))
            face=1;turning=false;beam.snapTo(-.08f)
            beam.animateTo(1.08f,tween(if(enabled) ScanChoreography.SWEEP_MILLIS else 0,easing=LinearEasing))
            while(!ScanChoreography.canLeaveBack(latestSide)) {
                if(!enabled) {snapshotFlow {ScanChoreography.canLeaveBack(latestSide)}.first {it};break}
                beam.snapTo(-.08f)
                beam.animateTo(1.08f,tween(ScanChoreography.SWEEP_MILLIS,easing=LinearEasing))
            }
        }
        scanned=true
        handoff.animateTo(1f,tween(if(enabled) ScanChoreography.HANDOFF_MILLIS else 0,easing=CubicBezierEasing(.22f,0f,.16f,1f)))
        if(latestActive) complete()
    }
    val accent=MaterialTheme.colorScheme.primary
    val visiblePhoto=if(shownBack) backState?.bitmap else frontState.bitmap
    val visibleRegions=regions.filter {it.side==if(shownBack) 1 else 0}
    Surface(Modifier.fillMaxSize().testTag("reading-stage"),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement=Arrangement.Top,horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Icon(if(scanned) Icons.Outlined.Done else Icons.Outlined.DocumentScanner,null,tint=accent)
                Spacer(Modifier.width(10.dp));Text(if(scanned) "Scans ready" else "Reading card",style=MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f));DockIconButton(onClick={if(active) onCancel()},enabled=active) {Icon(Icons.Outlined.Close,"Cancel reading")}
            }
            Spacer(Modifier.height(48.dp))
            // The reading cradle remains in the screen plane while the paper turns.
            Box(Modifier.widthIn(max=600.dp).fillMaxWidth().drawBehind {
                val radius=size.width*.62f
                val center=Offset(size.width*.5f,size.height*.56f)
                drawRect(Brush.radialGradient(listOf(accent.copy(alpha=.15f*(1f-handoff.value)),Color.Transparent),center,radius),
                    center-Offset(radius,radius),Size(radius*2,radius*2))
            }) {
                // Project the turning leaf onto the supporting plane. Its cast shadow
                // narrows edge-on but never rotates out of the screen with the photograph.
                Box(Modifier.matchParentSize().graphicsLayer {
                    scaleX=abs(cos(Math.toRadians(angle.value.toDouble())).toFloat()).coerceAtLeast(.05f)*(1f-handoff.value*.035f)
                    scaleY=1f-handoff.value*.035f
                }.physicalSurface(depthDp=12f+abs(sin(Math.toRadians(angle.value.toDouble())).toFloat())*16f+handoff.value*10f,
                    shape=RoundedCornerShape(14.dp),material=PhysicalMaterial.Paper,drawBevel=false))
                Box(cardImageTransition(front,shownBack).fillMaxWidth().aspectRatio(1.65f).graphicsLayer {
                    val edge=sin(Math.toRadians(angle.value.toDouble())).toFloat()
                    rotationY=angle.value;translationY=(-edge*16f-handoff.value*24f)*density
                    scaleX=1f+edge*.025f-handoff.value*.035f;scaleY=scaleX
                    cameraDistance=20*density
                }.physicalSurface(depthDp=1f,shape=RoundedCornerShape(14.dp),material=PhysicalMaterial.Paper,castShadow=false)
                    .background(MaterialTheme.colorScheme.surfaceContainer,RoundedCornerShape(14.dp))) {
                    Box(Modifier.fillMaxSize().graphicsLayer {rotationY=if(shownBack) 180f else 0f;shape=RoundedCornerShape(14.dp);clip=true}) {
                        visiblePhoto?.let {Image(it.asImageBitmap(),if(shownBack) "Back of card being read" else "Front of card being read",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
                        if(!scanned) Canvas(Modifier.fillMaxSize().testTag("reading-light").semantics {stateDescription="${visibleRegions.size} regions on ${if(shownBack) "back" else "front"}"}) {
                            val y=size.height*beam.value
                            val light=Color(0xFF9AE7FF)
                            if(!turning) {
                                // Real recognized lines, mapped through the same Fit geometry as the photo.
                                visiblePhoto?.let {photo->
                                    val ratio=min(size.width/photo.width,size.height/photo.height)
                                    val w=photo.width*ratio;val h=photo.height*ratio
                                    val ox=(size.width-w)/2;val oy=(size.height-h)/2
                                    visibleRegions.forEach {region->
                                        val response=ScanChoreography.revealRegion((oy+region.top*h)/size.height,beam.value)
                                        if(response>0f) {
                                            val origin=Offset(ox+region.left*w,oy+region.top*h)
                                            val extent=Size((region.right-region.left)*w,(region.bottom-region.top)*h)
                                            drawRoundRect(light.copy(alpha=response*.13f),origin,extent,CornerRadius(2.dp.toPx()))
                                            drawRoundRect(light.copy(alpha=response*.62f),origin,extent,CornerRadius(2.dp.toPx()),style=Stroke(.7.dp.toPx()))
                                        }
                                    }
                                }
                                drawRect(Brush.verticalGradient(listOf(Color.Transparent,accent.copy(alpha=.08f),light.copy(alpha=.25f),Color.Transparent),startY=y-size.height*.3f,endY=y+10.dp.toPx()))
                                drawLine(light.copy(alpha=.16f),Offset(0f,y),Offset(size.width,y),12.dp.toPx())
                                drawLine(light.copy(alpha=.42f),Offset(0f,y),Offset(size.width,y),4.dp.toPx())
                                drawLine(Color(0xFFE0F8FF),Offset(0f,y),Offset(size.width,y),1.dp.toPx())
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(scanned,enter=expandVertically(DockMotion.spec(450))+fadeIn(DockMotion.spec(300)),exit=fadeOut()) {
                Column(Modifier.fillMaxWidth().physicalSurface(depthDp=3f,shape=RoundedCornerShape(18.dp),material=PhysicalMaterial.Paper)
                    .background(MaterialTheme.colorScheme.surfaceContainer,RoundedCornerShape(18.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(analysisReady) "Check the details, then save" else "Preparing your review",style=MaterialTheme.typography.titleMedium)
                    Text(if(analysisReady) "Your photographs and details stay together." else "Checking the text against ${if(back!=null) "both photographs" else "the photograph"}.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!analysisReady) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            AnimatedContent(Triple(scanned,turning,face),label="Reading phase") {state->
                Text(if(state.first) {if(back!=null) "Organizing details from both sides" else "Organizing the details"}
                    else if(state.second) "Turning to the back" else if(state.third==0) "Reading the front" else "Reading the back",
                    modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,style=MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(10.dp))
            Text(if(!scanned) "Reading printed text on this side" else phase,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign=TextAlign.Center,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            Spacer(Modifier.height(8.dp));Text("Processed privately on your phone",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp));DockTextButton(onClick={if(active) onCancel()},enabled=active) {Text("Cancel reading")}
        }
    }
}
