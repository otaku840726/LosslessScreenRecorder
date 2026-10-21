package com.lossless.screenrecorder.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioMonitorColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = OnElectricCyanContainer,
    secondary = SignalCrimson,
    onSecondary = OnSignalCrimson,
    secondaryContainer = SignalCrimsonContainer,
    onSecondaryContainer = OnSignalCrimsonContainer,
    tertiary = TelemetryAmber,
    tertiaryContainer = TelemetryAmberContainer,
    onTertiaryContainer = OnTelemetryAmberContainer,
    background = StudioBackground,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = StudioOutline,
    outlineVariant = StudioOutlineVariant,
    error = SignalCrimson,
    onError = OnSignalCrimson,
    errorContainer = SignalCrimsonContainer,
    onErrorContainer = OnSignalCrimsonContainer
)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun LosslessRecorderTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioMonitorColorScheme,
        typography = AppTypography,
        shapes = StudioShapes,
        content = content
    )
}
