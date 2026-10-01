package com.thotapalli.visidock

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable fun SettingsCompartment(title:String,summary:String,content:@Composable ColumnScope.()->Unit) {
    var open by rememberSaveable(title) {mutableStateOf(false)}
    val angle by animateFloatAsState(if(open) 180f else 0f,PhysicalMotion.settle(PhysicalMaterial.Metal),label="$title latch")
    val shape=MaterialTheme.shapes.large
    Column(Modifier.fillMaxWidth().physicalSurface(2.5f,shape=shape,material=PhysicalMaterial.Leather)
        .clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).materialUnderlay(.12f)) {
        DockTextButton(onClick={open=!open},modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ExpandMore,if(open) "Collapse $title" else "Expand $title",Modifier.graphicsLayer {rotationZ=angle})
        }
        AnimatedVisibility(open,enter=expandVertically(DockMotion.settle(390f,.92f))+fadeIn(DockMotion.spec(150)),exit=shrinkVertically(DockMotion.settle(440f,.95f))+fadeOut(DockMotion.spec(90))) {
            Column(Modifier.padding(start=18.dp,end=18.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
        }
    }
}
