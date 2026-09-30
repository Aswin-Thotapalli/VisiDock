package com.thotapalli.visidock

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** One tactile response across primary actions; interruptible and system-motion aware. */
@Composable fun DockButton(onClick: () -> Unit, modifier: Modifier=Modifier, enabled: Boolean=true, content: @Composable RowScope.() -> Unit) {
    val interactions=remember {MutableInteractionSource()}
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed && enabled) 0.97f else 1f,
        tween(if(ValueAnimator.areAnimatorsEnabled()) 120 else 0,easing=CubicBezierEasing(0.2f,0f,0f,1f)),label="Button pressure")
    Button(onClick=onClick,enabled=enabled,interactionSource=interactions,modifier=modifier.graphicsLayer {scaleX=scale;scaleY=scale},content=content)
}
