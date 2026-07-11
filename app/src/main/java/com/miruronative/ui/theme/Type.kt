package com.miruronative.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Lightly tuned defaults; the system font stands in for Geist without bundling a font file.
val MiruroTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

val MiruroTvTypography = MiruroTypography.copy(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp),
)
