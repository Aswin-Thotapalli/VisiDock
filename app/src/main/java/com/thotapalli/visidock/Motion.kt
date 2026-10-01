@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.thotapalli.visidock

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.round
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Shared motion grammar. System animator scale also governs Compose's animation clock. */
object DockMotion {
    val easing=CubicBezierEasing(0.2f,0f,0f,1f)
    fun duration(ms:Int)=if(ValueAnimator.areAnimatorsEnabled()) ms else 0
    fun <T> spec(ms:Int=240):FiniteAnimationSpec<T> = tween(duration(ms),easing=easing)
    fun <T> settle(stiffness:Float=380f,damping:Float=.86f):FiniteAnimationSpec<T> =
        if(ValueAnimator.areAnimatorsEnabled()) spring(dampingRatio=damping,stiffness=stiffness) else tween(0)
}

/** Release decisions use a short velocity projection, limited to one adjacent face per gesture. */
internal object CardTurnPhysics {
    fun target(start:Float,angle:Float,velocity:Float):Float {
        val origin=round(start/180f)
        val predicted=angle+velocity.coerceIn(-1200f,1200f)*.12f
        return round(predicted/180f).coerceIn(origin-1f,origin+1f)*180f
    }
    fun isBack(angle:Float):Boolean = ((angle%360f+360f)%360f) in 90f..270f
}

/** The card follows the finger directly; a new gesture interrupts the current physical settle. */
@Stable internal class CardTurnState(initialBack:Boolean,private val scope:CoroutineScope,private val onTarget:(Boolean)->Unit) {
    private val animation=Animatable(if(initialBack) 180f else 0f)
    private var liveAngle by mutableFloatStateOf(animation.value)
    private var startAngle=animation.value
    private var settleJob:Job?=null
    var dragging by mutableStateOf(false)
        private set
    var targetBack by mutableStateOf(initialBack)
        private set
    val angle:Float get()=if(dragging) liveAngle else animation.value
    val showingBack:Boolean get()=CardTurnPhysics.isBack(angle)
    fun beginDrag() {
        settleJob?.cancel()
        startAngle=angle;liveAngle=startAngle;dragging=true
    }
    fun drag(degrees:Float) {if(dragging) liveAngle=(liveAngle+degrees).coerceIn(startAngle-179f,startAngle+179f)}
    fun release(velocity:Float=0f) {
        val boundedVelocity=velocity.coerceIn(-1200f,1200f)
        settle(CardTurnPhysics.target(startAngle,angle,boundedVelocity),boundedVelocity)
    }
    fun flip() {
        val current=angle
        val base=round(current/180f)*180f
        val desiredBack=!targetBack
        val target=listOf(base,base+180f,base-180f).filter {CardTurnPhysics.isBack(it)==desiredBack}.minBy {kotlin.math.abs(it-current)}
        // Retarget a moving card without abruptly discarding its momentum.
        settle(target,if(dragging) 0f else animation.velocity)
    }
    private fun settle(target:Float,velocity:Float) {
        val from=angle
        settleJob?.cancel()
        targetBack=CardTurnPhysics.isBack(target);onTarget(targetBack)
        settleJob=scope.launch {
            animation.snapTo(from)
            dragging=false
            animation.animateTo(target,DockMotion.settle(stiffness=290f,damping=.82f),initialVelocity=velocity)
        }
    }
}

fun Modifier.dockReflow()=animateContentSize(DockMotion.spec(220))

private val motionShadow=Color(0xFF123366)
private enum class ControlResponse { Surface, Outline, Link, Icon, Toggle, Navigation }

/** A single response handles press, canceled press, keyboard focus, hover and disabled changes. */
fun Modifier.dockPress(interactions:MutableInteractionSource,shape:Shape?=null)=composed {
    dockControl(interactions,shape=shape ?: MaterialTheme.shapes.medium)
}

private fun Modifier.dockControl(
    interactions:MutableInteractionSource,
    enabled:Boolean=true,
    shape:Shape=CircleShape,
    compression:Float=.035f,
    depth:Float=0f,
    response:ControlResponse=ControlResponse.Surface
)=composed {
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val pressure=animateFloatAsState(if(pressed && enabled) 1f else 0f,
        if(pressed) DockMotion.spec(80) else DockMotion.settle(650f,.74f),label="Contact pressure")
    val attention=animateFloatAsState(if(enabled && (focused || hovered)) 1f else 0f,
        DockMotion.spec(150),label="Control focus")
    val highlight=MaterialTheme.colorScheme.primary
    graphicsLayer {
        val p=pressure.value
        when(response) {
            ControlResponse.Link,ControlResponse.Navigation -> {scaleX=1f;scaleY=1f;translationY=0f}
            ControlResponse.Toggle -> {scaleX=1f+p*.025f;scaleY=1f-p*.025f}
            else -> {scaleX=1f-p*compression;scaleY=1f-p*compression;translationY=p*.8f*density-attention.value*.6f*density}
        }
        // Android 26/27 cannot tint native shadows; avoid their black fallback entirely.
        shadowElevation=if(enabled && android.os.Build.VERSION.SDK_INT>=28)
            ((depth*(1f-p.coerceIn(0f,1f)))+attention.value*1.5f)*density else 0f
        ambientShadowColor=motionShadow;spotShadowColor=motionShadow
        this.shape=shape;clip=false
    }.drawWithCache {
        val outline=shape.createOutline(size,layoutDirection,this)
        onDrawWithContent {
            drawContent()
            val pressureAmount=pressure.value.coerceIn(0f,1f)
            val emphasis=(pressureAmount+attention.value).coerceIn(0f,1f)
            if(response==ControlResponse.Link) {
                if(emphasis>.001f) {
                    val half=size.width*.27f*emphasis
                    drawLine(highlight.copy(alpha=emphasis*.65f),
                        androidx.compose.ui.geometry.Offset(size.width/2f-half,size.height-7.dp.toPx()),
                        androidx.compose.ui.geometry.Offset(size.width/2f+half,size.height-7.dp.toPx()),1.dp.toPx())
                }
            } else {
                // Outlined controls reinforce their perimeter; solid plates catch a soft top light.
                val alpha=pressureAmount*(if(response==ControlResponse.Outline) .35f else .13f)+attention.value*.22f
                if(alpha>.001f) drawOutline(outline,highlight.copy(alpha=alpha),style=Stroke(1.dp.toPx()))
                if(response==ControlResponse.Surface && depth>0f && pressureAmount>.001f)
                    drawOutline(outline,androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                        Color.White.copy(alpha=pressureAmount*.25f),Color.Transparent)),style=Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable private fun dockClick(onClick:()->Unit,haptic:Boolean):()->Unit {
    val feedback=LocalHapticFeedback.current
    return {
        if(haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/** Material retains semantics, focus, ripples and minimum touch targets; motion adds physical feedback. */
@Composable fun DockButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:ButtonColors=ButtonDefaults.buttonColors(),haptic:Boolean=true,loading:Boolean=false,content:@Composable RowScope.()->Unit) {
    val source=remember {MutableInteractionSource()}
    Button(onClick=dockClick(onClick,haptic),modifier=modifier.dockControl(source,enabled && !loading,compression=.04f,depth=1.5f),
        enabled=enabled && !loading,interactionSource=source,colors=colors,
        elevation=ButtonDefaults.buttonElevation(0.dp,0.dp,0.dp,0.dp,0.dp)) {
        androidx.compose.animation.AnimatedVisibility(loading,
            enter=androidx.compose.animation.fadeIn(DockMotion.spec(120))+androidx.compose.animation.expandHorizontally(DockMotion.spec(180)),
            exit=androidx.compose.animation.fadeOut(DockMotion.spec(100))+androidx.compose.animation.shrinkHorizontally(DockMotion.spec(150))) {
            Row {CircularProgressIndicator(Modifier.size(14.dp),color=LocalContentColor.current,strokeWidth=1.5.dp);Spacer(Modifier.width(8.dp))}
        }
        content()
    }
}

@Composable fun DockOutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:ButtonColors=ButtonDefaults.outlinedButtonColors(),haptic:Boolean=true,content:@Composable RowScope.()->Unit) {
    val source=remember {MutableInteractionSource()}
    OutlinedButton(onClick=dockClick(onClick,haptic),modifier=modifier.dockControl(source,enabled,compression=.018f,response=ControlResponse.Outline),
        enabled=enabled,interactionSource=source,colors=colors,content=content)
}

@Composable fun DockTextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:ButtonColors=ButtonDefaults.textButtonColors(),haptic:Boolean=true,content:@Composable RowScope.()->Unit) {
    val source=remember {MutableInteractionSource()}
    TextButton(onClick=dockClick(onClick,haptic),modifier=modifier.dockControl(source,enabled,response=ControlResponse.Link),
        enabled=enabled,interactionSource=source,colors=colors,content=content)
}

@Composable fun DockIconButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:IconButtonColors=IconButtonDefaults.iconButtonColors(),haptic:Boolean=true,content:@Composable ()->Unit) {
    val source=remember {MutableInteractionSource()}
    IconButton(onClick=dockClick(onClick,haptic),modifier=modifier.dockControl(source,enabled,compression=.09f,response=ControlResponse.Icon),
        enabled=enabled,interactionSource=source,colors=colors,content=content)
}

@Composable fun DockFilledIconButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=CircleShape,colors:IconButtonColors=IconButtonDefaults.filledIconButtonColors(),haptic:Boolean=true,content:@Composable ()->Unit) {
    val source=remember {MutableInteractionSource()}
    FilledIconButton(onClick=dockClick(onClick,haptic),modifier=modifier.dockControl(source,enabled,shape,compression=.07f,depth=2f),
        enabled=enabled,interactionSource=source,shape=shape,colors=colors,content=content)
}

@Composable fun DockIconToggleButton(checked:Boolean,onCheckedChange:(Boolean)->Unit,modifier:Modifier=Modifier,
    enabled:Boolean=true,colors:IconToggleButtonColors=IconButtonDefaults.iconToggleButtonColors(),haptic:Boolean=true,content:@Composable ()->Unit) {
    val source=remember {MutableInteractionSource()}
    val feedback=LocalHapticFeedback.current
    val selected=animateFloatAsState(if(checked) 1f else 0f,DockMotion.settle(480f,.8f),label="Icon selection")
    IconToggleButton(checked=checked,onCheckedChange={if(haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove);onCheckedChange(it)},
        modifier=modifier.dockControl(source,enabled,compression=.08f,response=ControlResponse.Icon),enabled=enabled,colors=colors,interactionSource=source) {
        Box(Modifier.graphicsLayer {scaleX=1f+selected.value*.045f;scaleY=scaleX},contentAlignment=androidx.compose.ui.Alignment.Center) {content()}
    }
}

@Composable fun DockFilterChip(selected:Boolean,onClick:()->Unit,label:@Composable ()->Unit,modifier:Modifier=Modifier,
    enabled:Boolean=true,leadingIcon:(@Composable ()->Unit)?=null,trailingIcon:(@Composable ()->Unit)?=null,haptic:Boolean=true) {
    val source=remember {MutableInteractionSource()}
    val selection=animateFloatAsState(if(selected) 1f else 0f,DockMotion.settle(520f,.84f),label="Chip selection")
    FilterChip(selected=selected,onClick=dockClick(onClick,haptic),label=label,modifier=modifier.graphicsLayer {translationY=-selection.value*density}
        .dockControl(source,enabled,MaterialTheme.shapes.small,compression=.025f,response=ControlResponse.Outline),
        enabled=enabled,leadingIcon=leadingIcon,trailingIcon=trailingIcon,interactionSource=source)
}

@Composable fun DockAssistChip(onClick:()->Unit,label:@Composable ()->Unit,modifier:Modifier=Modifier,
    enabled:Boolean=true,leadingIcon:(@Composable ()->Unit)?=null,trailingIcon:(@Composable ()->Unit)?=null,haptic:Boolean=true) {
    val source=remember {MutableInteractionSource()}
    AssistChip(onClick=dockClick(onClick,haptic),label=label,modifier=modifier.dockControl(source,enabled,MaterialTheme.shapes.small,compression=.035f),
        enabled=enabled,leadingIcon=leadingIcon,trailingIcon=trailingIcon,interactionSource=source)
}

@Composable fun DockSwitch(checked:Boolean,onCheckedChange:((Boolean)->Unit)?,modifier:Modifier=Modifier,
    enabled:Boolean=true,thumbContent:(@Composable ()->Unit)?=null,colors:SwitchColors=SwitchDefaults.colors(),haptic:Boolean=true) {
    val source=remember {MutableInteractionSource()}
    val feedback=LocalHapticFeedback.current
    Switch(checked=checked,onCheckedChange=if(onCheckedChange==null) null else {value->
        if(haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove);onCheckedChange(value)
    },modifier=modifier.dockControl(source,enabled,response=ControlResponse.Toggle),enabled=enabled,thumbContent=thumbContent,colors=colors,interactionSource=source)
}

@Composable private fun DockNavigationIcon(selected:Boolean,icon:@Composable ()->Unit) {
    val selection=animateFloatAsState(if(selected) 1f else 0f,DockMotion.settle(430f,.78f),label="Navigation destination")
    Box(Modifier.graphicsLayer {scaleX=1f+selection.value*.065f;scaleY=scaleX;translationY=-selection.value*1.5f*density}) {icon()}
}

@Composable fun RowScope.DockNavigationBarItem(selected:Boolean,onClick:()->Unit,icon:@Composable ()->Unit,
    modifier:Modifier=Modifier,enabled:Boolean=true,label:(@Composable ()->Unit)?=null,alwaysShowLabel:Boolean=true,
    colors:NavigationBarItemColors=NavigationBarItemDefaults.colors(),haptic:Boolean=true) {
    val source=remember {MutableInteractionSource()}
    NavigationBarItem(selected=selected,onClick=dockClick(onClick,haptic),icon={DockNavigationIcon(selected,icon)},
        modifier=modifier.dockControl(source,enabled,response=ControlResponse.Navigation),enabled=enabled,label=label,
        alwaysShowLabel=alwaysShowLabel,colors=colors,interactionSource=source)
}

@Composable fun DockNavigationRailItem(selected:Boolean,onClick:()->Unit,icon:@Composable ()->Unit,
    modifier:Modifier=Modifier,enabled:Boolean=true,label:(@Composable ()->Unit)?=null,alwaysShowLabel:Boolean=true,
    colors:NavigationRailItemColors=NavigationRailItemDefaults.colors(),haptic:Boolean=true) {
    val source=remember {MutableInteractionSource()}
    NavigationRailItem(selected=selected,onClick=dockClick(onClick,haptic),icon={DockNavigationIcon(selected,icon)},
        modifier=modifier.dockControl(source,enabled,response=ControlResponse.Navigation),enabled=enabled,label=label,
        alwaysShowLabel=alwaysShowLabel,colors=colors,interactionSource=source)
}

@Composable fun DockExtendedFloatingActionButton(onClick:()->Unit,text:@Composable ()->Unit,icon:@Composable ()->Unit,
    modifier:Modifier=Modifier,expanded:Boolean=true,shape:Shape=MaterialTheme.shapes.large,
    containerColor:Color=MaterialTheme.colorScheme.primary,contentColor:Color=MaterialTheme.colorScheme.onPrimary,
    elevation:FloatingActionButtonElevation=FloatingActionButtonDefaults.elevation(0.dp,0.dp,0.dp,0.dp),haptic:Boolean=true) {
    val source=remember {MutableInteractionSource()}
    ExtendedFloatingActionButton(onClick=dockClick(onClick,haptic),text=text,icon=icon,expanded=expanded,
        modifier=modifier.dockControl(source,shape=shape,compression=.045f,depth=3f),shape=shape,
        containerColor=containerColor,contentColor=contentColor,elevation=elevation,interactionSource=source)
}

/** Keep text and caret geometry fixed; Material animates the container, label and actual field outline. */
@Composable fun DockOutlinedTextField(value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,
    enabled:Boolean=true,readOnly:Boolean=false,textStyle:TextStyle=LocalTextStyle.current,
    label:(@Composable ()->Unit)?=null,placeholder:(@Composable ()->Unit)?=null,
    leadingIcon:(@Composable ()->Unit)?=null,trailingIcon:(@Composable ()->Unit)?=null,
    prefix:(@Composable ()->Unit)?=null,suffix:(@Composable ()->Unit)?=null,supportingText:(@Composable ()->Unit)?=null,
    isError:Boolean=false,visualTransformation:VisualTransformation=VisualTransformation.None,
    keyboardOptions:KeyboardOptions=KeyboardOptions.Default,keyboardActions:KeyboardActions=KeyboardActions.Default,
    singleLine:Boolean=false,maxLines:Int=if(singleLine) 1 else Int.MAX_VALUE,minLines:Int=1,
    shape:Shape=MaterialTheme.shapes.medium,colors:TextFieldColors?=null) {
    val source=remember {MutableInteractionSource()}
    OutlinedTextField(value=value,onValueChange=onValueChange,modifier=modifier,enabled=enabled,readOnly=readOnly,
        textStyle=textStyle,label=label,placeholder=placeholder,leadingIcon=leadingIcon,trailingIcon=trailingIcon,
        prefix=prefix,suffix=suffix,supportingText=supportingText,isError=isError,visualTransformation=visualTransformation,
        keyboardOptions=keyboardOptions,keyboardActions=keyboardActions,singleLine=singleLine,maxLines=maxLines,minLines=minLines,
        interactionSource=source,shape=shape,colors=colors ?: OutlinedTextFieldDefaults.colors(
            focusedContainerColor=MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor=MaterialTheme.colorScheme.surface,
            focusedBorderColor=MaterialTheme.colorScheme.primary,cursorColor=MaterialTheme.colorScheme.primary))
}

/** Finite arrival for content groups. No decorative loop and no delay with reduced motion. */
fun Modifier.dockArrival(key:Any?=Unit,index:Int=0,offsetDp:Float=14f)=composed {
    val entrance=remember(key) {Animatable(if(ValueAnimator.areAnimatorsEnabled()) 0f else 1f)}
    LaunchedEffect(key) {
        if(!ValueAnimator.areAnimatorsEnabled()) entrance.snapTo(1f)
        else {delay(index.coerceIn(0,5)*24L);entrance.animateTo(1f,DockMotion.settle(440f,.88f))}
    }
    graphicsLayer {alpha=entrance.value.coerceIn(0f,1f);translationY=(1f-entrance.value)*offsetDp*density}
}

fun Modifier.dockModalArrival(key:Any?=Unit)=dockArrival(key=key,offsetDp=22f)
