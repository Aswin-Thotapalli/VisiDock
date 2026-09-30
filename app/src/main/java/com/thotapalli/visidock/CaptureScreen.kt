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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    LaunchedEffect(Unit) {if(!allowed) permission.launch(Manifest.permission.CAMERA)}
    DisposableEffect(owner) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
    DisposableEffect(allowed,owner) {
        var disposed=false
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
        if(allowed) AndroidView({preview},Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            // Reserve control space in landscape and on short displays as well.
            val frameWidth=minOf(size.width*.84f,size.height*.46f*1.65f)
            val left=(size.width-frameWidth)/2f; val right=left+frameWidth; val height=frameWidth/1.65f
            val top=(size.height-height)/2f; val bottom=top+height
            val ink=Color(0xFF071D49).copy(alpha=.68f)
            drawRect(ink,size=androidx.compose.ui.geometry.Size(size.width,top))
            drawRect(ink,Offset(0f,bottom),androidx.compose.ui.geometry.Size(size.width,size.height-bottom))
            drawRect(ink,Offset(0f,top),androidx.compose.ui.geometry.Size(left,height))
            drawRect(ink,Offset(right,top),androidx.compose.ui.geometry.Size(size.width-right,height))
            val length=28.dp.toPx(); val width=3.dp.toPx(); val lime=Color(0xFFB8F36B)
            listOf(Offset(left,top) to Offset(1f,1f),Offset(right,top) to Offset(-1f,1f),Offset(left,bottom) to Offset(1f,-1f),Offset(right,bottom) to Offset(-1f,-1f)).forEach {(p,d)->
                drawLine(lime,p,p+Offset(length*d.x,0f),width); drawLine(lime,p,p+Offset(0f,length*d.y),width)
            }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(enabled=!taking,onClick=onClose) {Icon(Icons.Outlined.Close,"Close camera",tint=Color.White)}
                Text(if(back) "The other side." else "A connection worth keeping.",Modifier.weight(1f),color=Color.White,style=MaterialTheme.typography.titleMedium)
                if(camera?.cameraInfo?.hasFlashUnit()==true) IconToggleButton(torch,{torch=it;camera?.cameraControl?.enableTorch(it)}) {Icon(if(torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,"Camera light",tint=Color(0xFFB8F36B))}
            }
            Spacer(Modifier.weight(1f))
            error?.let {Text(it,color=Color.White)}
            if(!allowed) {
                Text("Camera access lets you scan cards here.",color=Color.White)
                Row {
                    TextButton({permission.launch(Manifest.permission.CAMERA)}) {Text("Allow camera",color=Color(0xFFB8F36B))}
                    TextButton({context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) {Text("App settings",color=Color(0xFFB8F36B))}
                }
            }
            Text(if(back) "Back of card · keep all edges in view" else "Front of card · keep all edges in view",color=Color.White,style=MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            FilledIconButton(enabled=allowed && camera!=null && !taking,onClick={
                taking=true;error=null
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                capture.targetRotation=preview.display?.rotation ?: android.view.Surface.ROTATION_0
                runCatching { capture.takePicture(ImageCapture.OutputFileOptions.Builder(file()).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output:ImageCapture.OutputFileResults) {taking=false;onResult(true)}
                    override fun onError(exception:ImageCaptureException) {taking=false;error="Couldn't capture that photo. Try again.";onResult(false)}
                }) }.onFailure {taking=false;error="Couldn't save the capture. Try again.";onResult(false)}
            },modifier=Modifier.size(80.dp),shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFFB8F36B),contentColor=Color(0xFF071D49))) {
                if(taking) CircularProgressIndicator(Modifier.size(32.dp),color=Color(0xFF071D49)) else Icon(Icons.Outlined.CameraAlt,"Capture card",Modifier.size(32.dp))
            }
            Spacer(Modifier.height(12.dp)); Text("Steady hands. Sharp details.",color=Color.White,style=MaterialTheme.typography.bodySmall)
        }
    }
}
