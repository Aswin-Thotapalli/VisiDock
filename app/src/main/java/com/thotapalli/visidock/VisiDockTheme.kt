package com.thotapalli.visidock

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    scrim=Color(0xFF071D49), inverseSurface=Color(0xFF102C60), inverseOnSurface=Color(0xFFF4F8FF),
    surfaceContainerLowest=Color.White, surfaceContainerLow=Color(0xFFF4F8FF), surfaceContainer=Color(0xFFEAF1FC), surfaceContainerHigh=Color(0xFFE5EFFC), surfaceContainerHighest=Color(0xFFDDE8F8),
    primary=Color(0xFF2455CE), onPrimary=Color.White, primaryContainer=Color(0xFFDCE7FF), onPrimaryContainer=Color(0xFF102C60),
    secondary=Color(0xFF486487), secondaryContainer=Color(0xFFE5EFFC), onSecondaryContainer=Color(0xFF15385E),
    background=Color(0xFFF4F8FF), onBackground=Color(0xFF10264B), surface=Color.White, onSurface=Color(0xFF10264B),
    surfaceVariant=Color(0xFFEAF1FC), onSurfaceVariant=Color(0xFF486080), outline=Color(0xFF637C9F), outlineVariant=Color(0xFFD4E0F3),
    error=Color(0xFFAE2929), errorContainer=Color(0xFFFFE9E6), onErrorContainer=Color(0xFF782222)
)
private val Dark = darkColorScheme(
    scrim=Color(0xFF071D49), inverseSurface=Color(0xFFEAF1FC), inverseOnSurface=Color(0xFF10264B),
    surfaceContainerLowest=Color(0xFF071D49), surfaceContainerLow=Color(0xFF0C2454), surfaceContainer=Color(0xFF102C60), surfaceContainerHigh=Color(0xFF17366C), surfaceContainerHighest=Color(0xFF203F75),
    primary=Color(0xFFA9C5FF), onPrimary=Color(0xFF102C60), primaryContainer=Color(0xFF244994), onPrimaryContainer=Color(0xFFE3EDFF),
    secondary=Color(0xFF99DDEB), secondaryContainer=Color(0xFF1A3A6A), onSecondaryContainer=Color(0xFFE5EFFC),
    background=Color(0xFF071D49), onBackground=Color(0xFFF1F6FF), surface=Color(0xFF071D49), onSurface=Color(0xFFF1F6FF),
    surfaceVariant=Color(0xFF102C60), onSurfaceVariant=Color(0xFFB7C9E7), outline=Color(0xFF92AACE), outlineVariant=Color(0xFF375384)
)
private val Type = Typography(
    headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=32.sp, lineHeight=38.sp, letterSpacing=(-0.8).sp),
    headlineMedium=TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=28.sp, lineHeight=34.sp, letterSpacing=(-0.5).sp),
    titleLarge=TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=22.sp, lineHeight=28.sp),
    titleMedium=TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=17.sp, lineHeight=24.sp),
    bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif, fontSize=16.sp, lineHeight=24.sp),
    bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif, fontSize=14.sp, lineHeight=21.sp),
    labelLarge=TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=14.sp, lineHeight=20.sp)
)
@Composable fun VisiDockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=if (isSystemInDarkTheme()) Dark else Light, typography=Type,
        shapes=Shapes(small=RoundedCornerShape(8.dp), medium=RoundedCornerShape(12.dp), large=RoundedCornerShape(16.dp)), content=content)
}
