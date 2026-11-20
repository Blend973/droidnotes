package com.droidnotes.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.droidnotes.ui.components.NoIndication

private val OledMonoColorScheme = darkColorScheme(
    primary = OledPrimaryInk,
    onPrimary = OledInverseInk,
    primaryContainer = OledPrimaryInk,
    onPrimaryContainer = OledInverseInk,
    secondary = OledSecondaryInk,
    onSecondary = OledInverseInk,
    secondaryContainer = OledSurfaceElevated,
    onSecondaryContainer = OledPrimaryInk,
    tertiary = OledSecondaryInk,
    onTertiary = OledInverseInk,
    background = OledBlack,
    onBackground = OledPrimaryInk,
    surface = OledBlack,
    onSurface = OledPrimaryInk,
    surfaceVariant = OledSurface,
    onSurfaceVariant = OledSecondaryInk,
    surfaceContainerLowest = OledBlack,
    surfaceContainerLow = OledSurface,
    surfaceContainer = OledSurface,
    surfaceContainerHigh = OledSurfaceElevated,
    surfaceContainerHighest = OledSurfaceElevated,
    outline = OledHairline,
    outlineVariant = OledHairline,
)

private val DroidNotesShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun DroidNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OledMonoColorScheme,
        typography = DroidNotesTypography,
        shapes = DroidNotesShapes,
    ) {
        CompositionLocalProvider(LocalIndication provides NoIndication) {
            content()
        }
    }
}
