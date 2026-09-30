@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable fun CropScreen(path:String,back:Boolean,busy:Boolean,error:String?,onUse:(List<Float>)->Unit,onCancel:()->Unit,onRetake:()->Unit) {
    var bitmap by remember(path) {mutableStateOf<Bitmap?>(null)}
    var loadError by remember(path) {mutableStateOf<String?>(null)}
    var points by rememberSaveable(path) {mutableStateOf(floatArrayOf(.08f,.12f,.92f,.12f,.92f,.88f,.08f,.88f))}
    var selected by rememberSaveable(path) {mutableIntStateOf(0)}
    var size by remember {mutableStateOf(IntSize.Zero)}
    LaunchedEffect(path) {
        try {bitmap=withContext(Dispatchers.IO) {ImageCropper.decode(File(path),1600)}}
        catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(e:Exception) {loadError="This photo could not be opened. Choose another photo."}
    }
    DisposableEffect(bitmap) {val held=bitmap;onDispose {held?.recycle()}}
    BackHandler {if(!busy) onCancel()}
    val cornerNames=listOf("Top left","Top right","Bottom right","Bottom left")
    fun move(dx:Float,dy:Float) {points=points.copyOf().also {it[selected*2]=(it[selected*2]+dx).coerceIn(0f,1f);it[selected*2+1]=(it[selected*2+1]+dy).coerceIn(0f,1f)}}
    val valid=ImageCropper.valid(points.toList())
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TopAppBar(title={Text(if(back) "Crop back" else "Crop card",maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(enabled=!busy,onClick=onCancel) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Cancel crop")}},actions={
            DockButton(onClick={onUse(points.toList())},enabled=!busy && bitmap!=null && valid,modifier=Modifier.padding(end=12.dp)) {Text(if(busy) "Cropping…" else "Use card")}
        })
        Text("Place each corner on the card edge. Only the selected card area will be saved.",Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal=12.dp),contentAlignment=Alignment.Center) {
            val photo=bitmap
            if(photo==null) {if(loadError==null) CircularProgressIndicator() else Text(loadError!!,Modifier.padding(16.dp))}
            else {
                fun imageRect():Pair<Offset,Offset> {
                    val scale=minOf(size.width.toFloat()/photo.width,size.height.toFloat()/photo.height)
                    val extent=Offset(photo.width*scale,photo.height*scale)
                    return Offset((size.width-extent.x)/2,(size.height-extent.y)/2) to extent
                }
                Canvas(Modifier.fillMaxSize().onSizeChanged {size=it}.semantics {contentDescription="Crop image. Four adjustable corners; use the corner controls below as an alternative to dragging."}.pointerInput(path,photo,busy) {
                    if(!busy) detectDragGestures(onDragStart={position ->
                        val (origin,extent)=imageRect()
                        selected=(0..3).minBy {i->(origin+Offset(points[i*2]*extent.x,points[i*2+1]*extent.y)-position).getDistanceSquared()}
                    }) {change,delta ->
                        change.consume();val (_,extent)=imageRect()
                        if(extent.x>0 && extent.y>0) move(delta.x/extent.x,delta.y/extent.y)
                    }
                }) {
                    val (origin,extent)=imageRect()
                    if(extent.x<=0 || extent.y<=0) return@Canvas
                    drawImage(photo.asImageBitmap(),dstOffset=IntOffset(origin.x.roundToInt(),origin.y.roundToInt()),dstSize=IntSize(extent.x.roundToInt().coerceAtLeast(1),extent.y.roundToInt().coerceAtLeast(1)))
                    val corners=(0..3).map {i->origin+Offset(points[i*2]*extent.x,points[i*2+1]*extent.y)}
                    val mask=Path().apply {fillType=PathFillType.EvenOdd;addRect(androidx.compose.ui.geometry.Rect(origin,androidx.compose.ui.geometry.Size(extent.x,extent.y)));moveTo(corners[0].x,corners[0].y);corners.drop(1).forEach {lineTo(it.x,it.y)};close()}
                    drawPath(mask,Color(0xFF071D49).copy(alpha=.72f))
                    val border=Path().apply {moveTo(corners[0].x,corners[0].y);corners.drop(1).forEach {lineTo(it.x,it.y)};close()}
                    drawPath(border,if(valid) Color(0xFFB8F36B) else Color(0xFFFFAD9F),style=Stroke(2.dp.toPx()))
                    corners.forEachIndexed {i,p ->drawCircle(Color(0xFF071D49),12.dp.toPx(),p);drawCircle(if(i==selected) Color(0xFFB8F36B) else Color.White,8.dp.toPx(),p)}
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max=220.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            (error ?: loadError)?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(!valid) Text("Corners must form a four-sided outline without crossing.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                cornerNames.forEachIndexed {i,name-> FilterChip(selected=i==selected,onClick={selected=i},enabled=!busy,label={Text("${i+1}")},modifier=Modifier.semantics {contentDescription="Select $name corner"})}
            }
            Text("${cornerNames[selected]} corner",style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                IconButton(enabled=!busy,onClick={move(-.005f,0f)}) {Icon(Icons.Outlined.KeyboardArrowLeft,"Move corner left")}
                IconButton(enabled=!busy,onClick={move(0f,-.005f)}) {Icon(Icons.Outlined.KeyboardArrowUp,"Move corner up")}
                IconButton(enabled=!busy,onClick={move(0f,.005f)}) {Icon(Icons.Outlined.KeyboardArrowDown,"Move corner down")}
                IconButton(enabled=!busy,onClick={move(.005f,0f)}) {Icon(Icons.Outlined.KeyboardArrowRight,"Move corner right")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(enabled=!busy,onClick={points=floatArrayOf(.08f,.12f,.92f,.12f,.92f,.88f,.08f,.88f)}) {Text("Reset corners")}
                TextButton(enabled=!busy,onClick=onRetake) {Text("Retake photo")}
            }
        }
    }
}
