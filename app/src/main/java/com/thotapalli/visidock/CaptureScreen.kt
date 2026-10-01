package com.thotapalli.visidock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Observer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/** Camera owns its lifecycle; framing is a guide, never a claim of edge detection. */
@Composable fun CaptureScreen(back: Boolean, file: () -> File, onResult: (Boolean) -> Unit, onClose: () -> Unit) {
    val context=LocalContext.current
    val compact=LocalConfiguration.current.screenHeightDp<500
    val haptic=LocalHapticFeedback.current
    val owner=LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {allowed=it}
    val preview=remember { PreviewView(context).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE; setBackgroundColor(0xFF071D49.toInt()) } }
    val capture=remember {ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()}
    var camera by remember {mutableStateOf<Camera?>(null)}
    var provider by remember {mutableStateOf<ProcessCameraProvider?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var taking by remember {mutableStateOf(false)}
    var torch by remember {mutableStateOf(false)}
    var streaming by remember {mutableStateOf(false)}
    var bindAttempt by remember {mutableIntStateOf(0)}
    var focusRequest by remember {mutableIntStateOf(0)}
    var focusState by remember {mutableIntStateOf(0)}
    var focusPoint by remember {mutableStateOf<Offset?>(null)}
    var focusVisible by remember {mutableStateOf(false)}
    val focusAlpha by animateFloatAsState(if(focusVisible) 1f else 0f,DockMotion.spec(180),label="Camera focus")
    val shutterAlpha by animateFloatAsState(if(taking) .26f else 0f,DockMotion.spec(100),label="Shutter acknowledgement")
    val focusRadius by animateFloatAsState(if(focusState==1) 18f else 27f,DockMotion.settle(430f,.74f),label="Optical focus lock")
    val focusColor by animateColorAsState(if(focusState==2) Color(0xFFFFAD9F) else if(focusState==1) Color(0xFF99DDEB) else Color.White,DockMotion.spec(160),label="Focus result")
    val acquisition by animateFloatAsState(if(taking) 1f else 0f,DockMotion.settle(500f,.88f),label="Capture acquisition")
    val streamAlpha by animateFloatAsState(if(streaming) 1f else 0f,DockMotion.spec(240),label="Camera arrival")
    fun focusAt(position:Offset) {
        val active=camera ?: return
        if(taking || !streaming) return
        focusPoint=position;focusVisible=true;focusState=0;focusRequest++
        val request=focusRequest
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val future=active.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(position.x,position.y)).setAutoCancelDuration(3,java.util.concurrent.TimeUnit.SECONDS).build())
        future.addListener({if(request==focusRequest) focusState=if(runCatching {future.get().isFocusSuccessful}.getOrDefault(false)) 1 else 2},ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(focusRequest) {if(focusRequest>0) {focusVisible=true;kotlinx.coroutines.delay(1100);focusVisible=false}}
    DisposableEffect(preview,camera,streaming,taking) {
        preview.setOnTouchListener {view,event ->
            if(event.action==android.view.MotionEvent.ACTION_UP && !taking) {
                focusAt(Offset(event.x,event.y));view.performClick()
            }
            true
        }
        onDispose {preview.setOnTouchListener(null)}
    }
    LaunchedEffect(Unit) {if(!allowed) permission.launch(Manifest.permission.CAMERA)}
    DisposableEffect(owner) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
    DisposableEffect(preview,owner) {
        val observer=Observer<PreviewView.StreamState> {streaming=it==PreviewView.StreamState.STREAMING}
        preview.previewStreamState.observe(owner,observer)
        onDispose {preview.previewStreamState.removeObserver(observer)}
    }
    DisposableEffect(camera,owner) {
        val held=camera
        val observer=Observer<Int> {torch=it==androidx.camera.core.TorchState.ON}
        held?.cameraInfo?.torchState?.observe(owner,observer)
        onDispose {held?.cameraInfo?.torchState?.removeObserver(observer)}
    }
    DisposableEffect(allowed,owner,bindAttempt) {
        var disposed=false
        camera=null;streaming=false
        if(allowed) {
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({if(!disposed) runCatching {
                val value=future.get(); provider=value
                val stream=Preview.Builder().build().also {it.surfaceProvider=preview.surfaceProvider}
                camera=value.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,stream,capture)
            }.onFailure {error="Camera could not open. Close this screen and try importing a photo."}},ContextCompat.getMainExecutor(context))
        }
        onDispose {disposed=true; provider?.unbindAll()}
    }
    BackHandler {if(!taking) onClose()}
    Box(Modifier.fillMaxSize().background(Color(0xFF071D49))) {
        if(allowed) AndroidView({preview},Modifier.fillMaxSize().graphicsLayer {alpha=streamAlpha}.semantics {
            contentDescription="Camera viewfinder"
            customActions=listOf(CustomAccessibilityAction("Focus in center") {focusAt(Offset(preview.width/2f,preview.height/2f));true})
        })
        Canvas(Modifier.fillMaxSize()) {
            // Reserve control space in landscape and on short displays as well.
            val frameWidth=minOf(size.width*.84f,size.height*.46f*1.65f)*(1f-acquisition*.035f)
            val left=(size.width-frameWidth)/2f; val right=left+frameWidth; val height=frameWidth/1.65f
            val top=(size.height-height)/2f; val bottom=top+height
            val ink=Color(0xFF071D49).copy(alpha=.68f)
            drawRect(ink,size=androidx.compose.ui.geometry.Size(size.width,top))
            drawRect(ink,Offset(0f,bottom),androidx.compose.ui.geometry.Size(size.width,size.height-bottom))
            drawRect(ink,Offset(0f,top),androidx.compose.ui.geometry.Size(left,height))
            drawRect(ink,Offset(right,top),androidx.compose.ui.geometry.Size(size.width-right,height))
            val length=(28+acquisition*18).dp.toPx(); val width=(2+acquisition).dp.toPx(); val lime=Color(0xFF99DDEB)
            listOf(Offset(left,top) to Offset(1f,1f),Offset(right,top) to Offset(-1f,1f),Offset(left,bottom) to Offset(1f,-1f),Offset(right,bottom) to Offset(-1f,-1f)).forEach {(p,d)->
                drawLine(lime,p,p+Offset(length*d.x,0f),width); drawLine(lime,p,p+Offset(0f,length*d.y),width)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            focusPoint?.let {point->
                drawCircle(focusColor.copy(alpha=focusAlpha*.15f),(focusRadius+7).dp.toPx(),point)
                drawCircle(focusColor.copy(alpha=focusAlpha),focusRadius.dp.toPx(),point,style=Stroke(1.5.dp.toPx()))
                drawLine(focusColor.copy(alpha=focusAlpha),point-Offset(5.dp.toPx(),0f),point+Offset(5.dp.toPx(),0f),1.dp.toPx())
                drawLine(focusColor.copy(alpha=focusAlpha),point-Offset(0f,5.dp.toPx()),point+Offset(0f,5.dp.toPx()),1.dp.toPx())
            }
            if(shutterAlpha>0f) drawRect(Color(0xFF99DDEB).copy(alpha=shutterAlpha*.5f))
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(if(compact) 12.dp else 24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Surface(color=Color(0xFF071D49).copy(alpha=.84f),shape=RoundedCornerShape(24.dp)) {
            Row(Modifier.fillMaxWidth().padding(6.dp),verticalAlignment=Alignment.CenterVertically) {
                DockIconButton(enabled=!taking,onClick=onClose) {Icon(Icons.Outlined.Close,"Close camera",tint=Color.White)}
                Column(Modifier.weight(1f).padding(start=4.dp)) {
                    Text(if(back) "BACK" else "FRONT",color=Color(0xFF99DDEB),style=MaterialTheme.typography.labelSmall)
                    Text("Frame your card",color=Color.White,style=MaterialTheme.typography.titleMedium)
                }
                if(camera?.cameraInfo?.hasFlashUnit()==true) DockIconToggleButton(torch,{enabled->
                    val future=camera?.cameraControl?.enableTorch(enabled)
                    future?.addListener({runCatching {future.get()}.onFailure {error="The camera light is unavailable. Try again."}},ContextCompat.getMainExecutor(context))
                },enabled=!taking) {Icon(if(torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,"Camera light",tint=if(torch) Color(0xFF99DDEB) else Color.White)}
            }
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(visible=error!=null || !allowed,enter=fadeIn(DockMotion.spec(180))+expandVertically(DockMotion.spec(240)),exit=fadeOut(DockMotion.spec(120))+shrinkVertically(DockMotion.spec(180))) {
            Surface(color=Color(0xFF071D49).copy(alpha=.94f),shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().padding(bottom=if(compact) 6.dp else 16.dp)) {
            Column(Modifier.heightIn(max=if(compact) 110.dp else 220.dp).verticalScroll(rememberScrollState()).padding(16.dp).dockReflow()) {
                Text(error ?: "Allow camera access to photograph your card.",color=Color.White,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                Row {
                    if(!allowed) {
                        DockTextButton({permission.launch(Manifest.permission.CAMERA)}) {Text("Allow camera",color=Color(0xFF99DDEB))}
                        DockTextButton({context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) {Text("App settings",color=Color(0xFF99DDEB))}
                    } else DockTextButton({error=null;bindAttempt++},enabled=!taking) {Text("Try again",color=Color(0xFF99DDEB))}
                }
            }}}
            if(allowed && !streaming && error==null) Row(Modifier.padding(bottom=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp),color=Color(0xFF99DDEB),strokeWidth=2.dp)
                Text("Opening camera…",color=Color.White,style=MaterialTheme.typography.bodySmall)
            }
            if(!compact) Text(if(back) "Back of card · keep all edges in view" else "Front of card · keep all edges in view",color=Color.White,style=MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(if(compact) 6.dp else 20.dp))
            val interactions=remember {MutableInteractionSource()}
            val pressed by interactions.collectIsPressedAsState()
            val shutterScale by animateFloatAsState(if(pressed) .85f else if(taking) .91f else 1f,if(pressed) DockMotion.spec(80) else DockMotion.settle(520f,.66f),label="Shutter travel")
            val ringScale by animateFloatAsState(if(pressed) 1.07f else 1f,DockMotion.settle(400f,.72f),label="Shutter outer ring")
            Box(Modifier.size(if(compact) 80.dp else 100.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize().graphicsLayer {scaleX=ringScale;scaleY=ringScale}) {
                    drawCircle(Color(0xFF99DDEB).copy(alpha=if(streaming) .72f else .3f),size.minDimension/2-2.dp.toPx(),style=Stroke(1.5.dp.toPx()))
                    drawCircle(Color(0xFF99DDEB).copy(alpha=.14f+acquisition*.24f),size.minDimension/2-8.dp.toPx(),style=Stroke(5.dp.toPx()))
                }
            FilledIconButton(enabled=allowed && streaming && camera!=null && !taking,interactionSource=interactions,onClick={
                taking=true;error=null
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                capture.targetRotation=preview.display?.rotation ?: android.view.Surface.ROTATION_0
                runCatching { capture.takePicture(ImageCapture.OutputFileOptions.Builder(file()).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output:ImageCapture.OutputFileResults) {onResult(true)}
                    override fun onError(exception:ImageCaptureException) {taking=false;error="Couldn't capture that photo. Try again.";onResult(false)}
                }) }.onFailure {taking=false;error="Couldn't save the capture. Try again.";onResult(false)}
            },modifier=Modifier.size(if(compact) 60.dp else 78.dp).graphicsLayer {scaleX=shutterScale;scaleY=shutterScale}.semantics {contentDescription=if(taking) "Saving captured photo" else "Capture card"},shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF99DDEB),contentColor=Color(0xFF071D49))) {
                if(taking) CircularProgressIndicator(Modifier.size(32.dp),color=Color(0xFF071D49)) else Icon(Icons.Outlined.CameraAlt,null,Modifier.size(32.dp))
            }
            }
            if(!compact) {Spacer(Modifier.height(12.dp));Text("Tap to focus · edges detected after capture",color=Color.White,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
