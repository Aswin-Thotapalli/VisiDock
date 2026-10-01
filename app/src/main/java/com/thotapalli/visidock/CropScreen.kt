@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.roundToInt

/** Disposal never waits on the UI thread or recycles a bitmap still used by a worker. */
private class CropBitmapLease(private val bitmap:Bitmap) {
    private var readers=0
    private var closed=false
    @Synchronized fun acquire():Bitmap? {if(closed) return null;readers++;return bitmap}
    @Synchronized fun release() {readers--;if(closed && readers==0) bitmap.recycle()}
    @Synchronized fun close() {closed=true;if(readers==0) bitmap.recycle()}
}

@Composable fun CropScreen(path:String,back:Boolean,busy:Boolean,error:String?,onUse:(List<Float>)->Unit,onCancel:()->Unit,onRetake:()->Unit) {
    val compact=LocalConfiguration.current.screenHeightDp<500
    val contextDensity=LocalDensity.current.density
    var bitmap by remember(path) {mutableStateOf<Bitmap?>(null)}
    var imageOwner by remember(path) {mutableStateOf<CropBitmapLease?>(null)}
    var loadError by remember(path) {mutableStateOf<String?>(null)}
    var points by rememberSaveable(path) {mutableStateOf(floatArrayOf(0f,0f,1f,0f,1f,1f,0f,1f))}
    var initialized by rememberSaveable(path) {mutableStateOf(false)}
    var detected by rememberSaveable(path) {mutableStateOf(false)}
    var uncertain by rememberSaveable(path) {mutableStateOf(false)}
    var adjusting by rememberSaveable(path) {mutableStateOf(false)}
    var proposal by remember(path) {mutableStateOf<List<Float>?>(null)}
    var cropped by remember(path) {mutableStateOf<Bitmap?>(null)}
    var croppedFor by remember(path) {mutableStateOf<List<Float>?>(null)}
    var selected by rememberSaveable(path) {mutableIntStateOf(0)}
    var dragging by remember(path) {mutableStateOf(false)}
    var followFinger by remember(path) {mutableStateOf(false)}
    val haptic=LocalHapticFeedback.current
    val handleLift by animateFloatAsState(if(dragging) 1f else 0f,DockMotion.settle(480f,.78f),label="Crop handle elevation")
    val renderedPoints=points.mapIndexed {index,value->animateFloatAsState(value,if(followFinger) DockMotion.spec(0) else DockMotion.settle(520f,.84f),label="Crop corner $index")}
    val previewArrival=remember(path) {Animatable(0f)}
    val sourcePose by remember(path) {derivedStateOf {previewArrival.value<=.0001f}}
    val projectiveMatrix=remember(path) {android.graphics.Matrix()}
    val projectivePaint=remember(path) {android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)}
    var inspectorHeight by remember {mutableIntStateOf(0)}
    var morphStartCorners by remember(path) {mutableStateOf<List<Float>?>(null)}
    var morphStartProgress by remember(path) {mutableFloatStateOf(0f)}
    var morphEndProgress by remember(path) {mutableFloatStateOf(1f)}
    val selectionArrival=remember(path) {Animatable(1f)}
    val confirmation by animateFloatAsState(if(busy) 1f else 0f,DockMotion.spec(180),label="Crop confirmation handoff")
    var size by remember {mutableStateOf(IntSize.Zero)}
    LaunchedEffect(path) {
        var pending:Bitmap?=null
        try {
            val result=withContext(Dispatchers.IO) {val photo=ImageCropper.decode(File(path),1600).also {pending=it};photo to ImageCropper.detectWithConfidence(photo)}
            imageOwner=CropBitmapLease(result.first);bitmap=result.first;proposal=result.second?.points;pending=null
            if(!initialized) {points=(result.second?.points ?: listOf(0f,0f,1f,0f,1f,1f,0f,1f)).toFloatArray();detected=result.second!=null;uncertain=(result.second?.confidence ?: 0f)<.82f;adjusting=!detected || uncertain;initialized=true}
        }
        catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(e:Exception) {loadError="This photo could not be opened. Choose another photo."}
        finally {pending?.recycle()}
    }
    DisposableEffect(imageOwner) {val held=imageOwner;onDispose {held?.close()}}
    LaunchedEffect(imageOwner,points.toList(),adjusting) {
        val owner=imageOwner
        val corners=points.toList()
        if(!adjusting && owner!=null && ImageCropper.valid(corners)) {
            val source=owner.acquire() ?: return@LaunchedEffect
            var pending:Bitmap?=null
            try {
                val rendered=withContext(Dispatchers.Default) {ImageCropper.warp(source,corners).also {pending=it}}
                croppedFor=corners;cropped=rendered;pending=null
            } catch(e:kotlinx.coroutines.CancellationException) {throw e}
            catch(e:Exception) {loadError="This crop could not be previewed. Adjust the corners and try again."}
            finally {pending?.recycle();owner.release()}
        }
    }
    DisposableEffect(cropped) {val held=cropped;onDispose {held?.recycle()}}
    LaunchedEffect(adjusting,croppedFor,points.toList()) {
        if(adjusting) previewArrival.animateTo(0f,DockMotion.spec(260))
        else if(cropped!=null && croppedFor==points.toList()) previewArrival.animateTo(1f,DockMotion.spec(360))
    }
    LaunchedEffect(selected) {selectionArrival.snapTo(0f);selectionArrival.animateTo(1f,DockMotion.settle(510f,.73f))}
    LaunchedEffect(dragging,followFinger,points.toList()) {
        if(!dragging && followFinger) {
            val released=points.toList()
            // Keep drawing the final finger pose until the zero-duration animation targets catch up.
            snapshotFlow {renderedPoints.map {it.value}}.first {it==released}
            followFinger=false
        }
    }
    BackHandler {if(!busy) onCancel()}
    val cornerNames=listOf("Top left","Top right","Bottom right","Bottom left")
    fun move(dx:Float,dy:Float) {points=points.copyOf().also {it[selected*2]=(it[selected*2]+dx).coerceIn(0f,1f);it[selected*2+1]=(it[selected*2+1]+dy).coerceIn(0f,1f)}}
    fun morphCorners(progress:Float):List<Float> {
        val start=morphStartCorners ?: points.toList()
        val span=morphEndProgress-morphStartProgress
        val fraction=if(kotlin.math.abs(span)<.0001f) 1f else ((progress-morphStartProgress)/span).coerceIn(0f,1f)
        return points.indices.map {i->start[i]+(points[i]-start[i])*fraction}
    }
    fun showPreview(preview:Boolean) {
        val progress=previewArrival.value.coerceIn(0f,1f)
        val displayed=if(adjusting && progress==0f) {if(followFinger) points.toList() else renderedPoints.map {it.value.coerceIn(0f,1f)}} else morphCorners(progress)
        morphStartCorners=displayed.takeIf {ImageCropper.valid(it)} ?: points.toList()
        morphStartProgress=progress;morphEndProgress=if(preview) 1f else 0f
        adjusting=!preview
    }
    val valid=ImageCropper.valid(points.toList())
    val edgeColor by animateColorAsState(if(valid) Color(0xFF99DDEB) else Color(0xFFFFAD9F),DockMotion.spec(140),label="Crop validity")
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TopAppBar(title={Text(if(back) "Crop back" else "Crop card",maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={DockIconButton(enabled=!busy,onClick=onCancel) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Cancel crop")}},actions={
            DockButton(onClick={onUse(points.toList())},enabled=!busy && bitmap!=null && valid,modifier=Modifier.padding(end=12.dp)) {
                if(busy) CircularProgressIndicator(Modifier.size(16.dp).padding(end=4.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary)
                Text(if(busy) "Cropping…" else "Use card")
            }
        })
        if(!compact) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density=LocalDensity.current
            val layoutDirection=LocalLayoutDirection.current
            val textMeasurer=rememberTextMeasurer()
            val instructionStyle=MaterialTheme.typography.bodyMedium
            val instructions=remember {listOf("Move the corners onto the card edges.","Card edges found. Check the crop, then continue.","Check your crop before continuing.","Possible card edges found. Check the four corners.","Card edges could not be found. Place the four corners manually.")}
            val instructionHeight=remember(maxWidth,density,layoutDirection,instructionStyle,textMeasurer) {
                val width=with(density) {(maxWidth-40.dp).roundToPx().coerceAtLeast(1)}
                val pixels=instructions.maxOf {textMeasurer.measure(AnnotatedString(it),style=instructionStyle,constraints=Constraints(maxWidth=width)).size.height}
                with(density) {pixels.toDp()}+16.dp
            }
            Box(Modifier.fillMaxWidth().height(instructionHeight),contentAlignment=Alignment.CenterStart) {
                AnimatedContent(targetState=adjusting,transitionSpec={fadeIn(DockMotion.spec(180)) togetherWith fadeOut(DockMotion.spec(90))},label="Crop instructions") {adjust ->
                    Text(instructions[if(adjust && !detected) 4 else if(adjust && uncertain) 3 else if(adjust) 0 else if(detected) 1 else 2],Modifier.padding(horizontal=20.dp,vertical=8.dp),style=instructionStyle)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            DockFilterChip(selected=!adjusting,onClick={showPreview(true)},enabled=!busy && bitmap!=null && valid,label={Text("Cropped preview")},modifier=Modifier.weight(1f),leadingIcon={Icon(Icons.Outlined.Crop,null,Modifier.size(18.dp))})
            DockFilterChip(selected=adjusting,onClick={showPreview(false)},enabled=!busy && bitmap!=null,label={Text("Adjust corners")},modifier=Modifier.weight(1f),leadingIcon={Icon(Icons.Outlined.Tune,null,Modifier.size(18.dp))})
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal=12.dp).onSizeChanged {size=it},contentAlignment=Alignment.Center) {
            val photo=bitmap
            if(photo==null) {
                if(loadError==null) Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.CropFree,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
                    CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
                    Text("Finding the card edges…",style=MaterialTheme.typography.bodyMedium)
                } else Text(loadError!!,Modifier.padding(16.dp))
            } else {
                val sourceImage=remember(photo) {photo.asImageBitmap()}
                // The source workspace stays fixed while the inspector contracts. Its image never jumps
                // when switching modes; only the selected quadrilateral travels to the preview rectangle.
                fun imageRect():Pair<Offset,Offset> {
                    val expanded=(if(compact) 112 else 224)*contextDensity
                    val measuredInspector=if(inspectorHeight>0) inspectorHeight.toFloat() else expanded
                    val height=(size.height-(expanded-measuredInspector).coerceAtLeast(0f)).coerceAtLeast(1f)
                    val scale=minOf(size.width.toFloat()/photo.width,height/photo.height)
                    val extent=Offset(photo.width*scale,photo.height*scale)
                    return Offset((size.width-extent.x)/2,(height-extent.y)/2) to extent
                }
                Canvas(Modifier.fillMaxSize().semantics {
                    contentDescription=if(!adjusting && croppedFor==points.toList()) "Cropped card preview" else "Crop image. Four adjustable corners; use the corner controls below as an alternative to dragging."
                    stateDescription=if(adjusting) "${cornerNames[selected]} corner selected. Horizontal ${(points[selected*2]*100).roundToInt()} percent, vertical ${(points[selected*2+1]*100).roundToInt()} percent." else if(croppedFor!=points.toList()) "Preparing cropped preview" else "Straightened card"
                }.onKeyEvent {event->
                    if(busy || !adjusting || event.type!=KeyEventType.KeyDown) false else when(event.key) {
                        Key.DirectionLeft->{move(-.005f,0f);true};Key.DirectionRight->{move(.005f,0f);true}
                        Key.DirectionUp->{move(0f,-.005f);true};Key.DirectionDown->{move(0f,.005f);true}
                        else->false
                    }
                }.focusable(enabled=!busy && adjusting).pointerInput(path,photo,busy,adjusting,sourcePose) {
                    if(!busy && adjusting && sourcePose) detectDragGestures(onDragStart={position ->
                        val (origin,extent)=imageRect()
                        val displayed=if(followFinger) points.toList() else renderedPoints.map {it.value.coerceIn(0f,1f)}
                        selected=(0..3).minBy {i->(origin+Offset(displayed[i*2]*extent.x,displayed[i*2+1]*extent.y)-position).getDistanceSquared()}
                        points=displayed.toFloatArray()
                        followFinger=true;dragging=true;haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },onDragEnd={dragging=false;haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)},onDragCancel={dragging=false}) {change,delta ->
                        change.consume();val (_,extent)=imageRect()
                        if(extent.x>0 && extent.y>0) move(delta.x/extent.x,delta.y/extent.y)
                    }
                }) {
                    val (origin,extent)=imageRect()
                    if(extent.x<=0 || extent.y<=0) return@Canvas
                    val progress=previewArrival.value.coerceIn(0f,1f)
                    val selectedGeometry=if(adjusting && progress==0f) {
                        if(followFinger) points.toList() else renderedPoints.map {it.value.coerceIn(0f,1f)}
                    } else morphCorners(progress)
                    val sourceCorners=(0..3).map {i->origin+Offset(selectedGeometry[i*2]*extent.x,selectedGeometry[i*2+1]*extent.y)}
                    if(progress<1f) {
                        projectiveMatrix.setRectToRect(android.graphics.RectF(0f,0f,photo.width.toFloat(),photo.height.toFloat()),android.graphics.RectF(origin.x,origin.y,origin.x+extent.x,origin.y+extent.y),android.graphics.Matrix.ScaleToFit.FILL)
                        drawIntoCanvas {canvas ->
                            projectivePaint.alpha=((1f-progress)*255).roundToInt()
                            try {canvas.nativeCanvas.drawBitmap(photo,projectiveMatrix,projectivePaint)} finally {projectivePaint.alpha=255}
                        }
                        val mask=Path().apply {
                            fillType=PathFillType.EvenOdd;addRect(androidx.compose.ui.geometry.Rect(origin,androidx.compose.ui.geometry.Size(extent.x,extent.y)))
                            moveTo(sourceCorners[0].x,sourceCorners[0].y);sourceCorners.drop(1).forEach {lineTo(it.x,it.y)};close()
                        }
                        drawPath(mask,Color(0xFF071D49).copy(alpha=.72f*(1f-progress)))
                    }
                    fun sourceEdge(a:Int,b:Int)=kotlin.math.hypot((points[a*2]-points[b*2])*photo.width,(points[a*2+1]-points[b*2+1])*photo.height)
                    val readyCrop=cropped?.takeIf {croppedFor==points.toList()}
                    val ratio=readyCrop?.let {it.width.toFloat()/it.height} ?: (maxOf(sourceEdge(0,1),sourceEdge(3,2)).coerceAtLeast(1f)/maxOf(sourceEdge(0,3),sourceEdge(1,2)).coerceAtLeast(1f)).coerceIn(.05f,20f)
                    val availableWidth=(size.width-40.dp.toPx()).coerceAtLeast(1f)
                    val availableHeight=(size.height-40.dp.toPx()).coerceAtLeast(1f)
                    val width=minOf(availableWidth,availableHeight*ratio)*(1f-confirmation*.025f)
                    val height=width/ratio
                    val targetOrigin=Offset((size.width-width)/2,(size.height-height)/2-confirmation*6.dp.toPx())
                    val rectangle=listOf(targetOrigin,targetOrigin+Offset(width,0f),targetOrigin+Offset(width,height),targetOrigin+Offset(0f,height))
                    val corners=sourceCorners.indices.map {i->sourceCorners[i]+(rectangle[i]-sourceCorners[i])*progress}
                    val border=Path().apply {moveTo(corners[0].x,corners[0].y);corners.drop(1).forEach {lineTo(it.x,it.y)};close()}
                    if(progress>0f) {
                        drawPath(border,Color(0xFF071D49).copy(alpha=.035f*progress),style=Stroke(12.dp.toPx()))
                        drawPath(border,Color(0xFF071D49).copy(alpha=.055f*progress),style=Stroke(5.dp.toPx()))
                    }
                    val source=FloatArray(8) {i->selectedGeometry[i]*(if(i%2==0) photo.width else photo.height)}
                    val destination=FloatArray(8) {i->if(i%2==0) corners[i/2].x else corners[i/2].y}
                    if(ImageCropper.valid(selectedGeometry) && projectiveMatrix.setPolyToPoly(source,0,destination,0,4)) {
                        drawIntoCanvas {canvas->
                            val clip=android.graphics.Path().apply {moveTo(corners[0].x,corners[0].y);corners.drop(1).forEach {lineTo(it.x,it.y)};close()}
                            val native=canvas.nativeCanvas;val checkpoint=native.save()
                            try {native.clipPath(clip);native.drawBitmap(photo,projectiveMatrix,projectivePaint)} finally {native.restoreToCount(checkpoint)}
                        }
                    }
                    drawPath(border,edgeColor.copy(alpha=1f-progress*.8f),style=Stroke(2.dp.toPx()))
                    val controlsAlpha=if(adjusting) 1f-progress else 0f
                    if(handleLift>0f && controlsAlpha>0f) for(t in listOf(1f/3,2f/3)) {
                        drawLine(Color.White.copy(alpha=.28f*handleLift*controlsAlpha),corners[0]+(corners[3]-corners[0])*t,corners[1]+(corners[2]-corners[1])*t,1.dp.toPx())
                        drawLine(Color.White.copy(alpha=.28f*handleLift*controlsAlpha),corners[0]+(corners[1]-corners[0])*t,corners[3]+(corners[2]-corners[3])*t,1.dp.toPx())
                    }
                    if(controlsAlpha>0f) corners.forEachIndexed {i,p ->
                        val lift=if(i==selected) handleLift else 0f
                        if(i==selected) drawCircle(Color(0xFF99DDEB).copy(alpha=.18f*controlsAlpha),(15+selectionArrival.value*4+lift*9).dp.toPx(),p)
                        drawCircle(Color(0xFF071D49).copy(alpha=controlsAlpha),(12+lift*3).dp.toPx(),p)
                        drawCircle((if(i==selected) Color(0xFF99DDEB) else Color.White).copy(alpha=controlsAlpha),(8+lift*2).dp.toPx(),p)
                        if(i==selected) drawCircle(Color.White.copy(alpha=controlsAlpha),2.dp.toPx(),p)
                    }
                    if(handleLift>.01f && controlsAlpha>0f) {
                        val radius=minOf(48.dp.toPx(),size.height*.3f);val center=Offset(if(points[selected*2]>.5f) radius+18.dp.toPx() else size.width-radius-18.dp.toPx(),radius+18.dp.toPx())
                        val magnifier=Path().apply {addOval(androidx.compose.ui.geometry.Rect(center-Offset(radius,radius),center+Offset(radius,radius)))}
                        drawCircle(Color(0xFF071D49).copy(alpha=.2f*handleLift),radius+7.dp.toPx(),center)
                        clipPath(magnifier) {
                            drawRect(Color(0xFF071D49))
                            val scale=maxOf(extent.x/photo.width,extent.y/photo.height)*2.6f
                            val enlarged=IntSize((photo.width*scale).roundToInt().coerceAtLeast(1),(photo.height*scale).roundToInt().coerceAtLeast(1))
                            val offset=IntOffset((center.x-points[selected*2]*enlarged.width).roundToInt(),(center.y-points[selected*2+1]*enlarged.height).roundToInt())
                            drawImage(sourceImage,dstOffset=offset,dstSize=enlarged,alpha=handleLift)
                            drawLine(Color(0xFF99DDEB),center-Offset(9.dp.toPx(),0f),center+Offset(9.dp.toPx(),0f),1.dp.toPx())
                            drawLine(Color(0xFF99DDEB),center-Offset(0f,9.dp.toPx()),center+Offset(0f,9.dp.toPx()),1.dp.toPx())
                        }
                        drawCircle(Color(0xFF99DDEB).copy(alpha=handleLift),radius,center,style=Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow,RoundedCornerShape(topStart=24.dp,topEnd=24.dp)).layout {measurable,constraints ->
            val expanded=(if(compact) 112.dp else 224.dp).roundToPx()
            val collapsed=(if(compact) 56.dp else 80.dp).roundToPx()
            val height=(expanded+(collapsed-expanded)*previewArrival.value.coerceIn(0f,1f)).roundToInt().coerceIn(constraints.minHeight,constraints.maxHeight)
            val placeable=measurable.measure(constraints.copy(minHeight=height,maxHeight=height))
            layout(placeable.width,height) {placeable.placeRelative(0,0)}
        }.onSizeChanged {inspectorHeight=it.height}.verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            (error ?: loadError)?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
            if(!valid) Text("Corners must form a four-sided outline without crossing.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            if(adjusting) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                cornerNames.forEachIndexed {i,name-> DockFilterChip(selected=i==selected,onClick={selected=i},enabled=!busy,label={Text("${i+1}")},modifier=Modifier.semantics {contentDescription="Select $name corner"})}
            }
            AnimatedContent(selected,transitionSpec={fadeIn(DockMotion.spec(160)) togetherWith fadeOut(DockMotion.spec(80))},label="Selected corner") {corner->Text("${cornerNames[corner]} corner",style=MaterialTheme.typography.labelLarge)}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                DockIconButton(enabled=!busy,onClick={move(-.005f,0f)}) {Icon(Icons.Outlined.KeyboardArrowLeft,"Move corner left")}
                DockIconButton(enabled=!busy,onClick={move(0f,-.005f)}) {Icon(Icons.Outlined.KeyboardArrowUp,"Move corner up")}
                DockIconButton(enabled=!busy,onClick={move(0f,.005f)}) {Icon(Icons.Outlined.KeyboardArrowDown,"Move corner down")}
                DockIconButton(enabled=!busy,onClick={move(.005f,0f)}) {Icon(Icons.Outlined.KeyboardArrowRight,"Move corner right")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                DockTextButton(enabled=!busy,modifier=Modifier.weight(1.5f),onClick={points=(proposal ?: listOf(0f,0f,1f,0f,1f,1f,0f,1f)).toFloatArray();loadError=null}) {Icon(Icons.Outlined.RestartAlt,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(if(proposal!=null) "Reset to detected edges" else "Use whole image")}
                DockTextButton(enabled=!busy,modifier=Modifier.weight(1f),onClick=onRetake) {Text("Retake photo")}
            }
            } else DockTextButton(enabled=!busy,onClick=onRetake) {Icon(Icons.Outlined.CameraAlt,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("Retake photo")}
        }
    }
}
