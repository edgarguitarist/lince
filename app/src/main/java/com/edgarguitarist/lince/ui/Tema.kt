package com.edgarguitarist.lince.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amarillo = Color(0xFFFFC61A)
val Fondo = Color(0xFF1B1B1B)
val Superficie = Color(0xFF242424)
val TextoSec = Color(0xFF9A9A9A)
val Linea = Color(0xFF333333)

@Composable
fun TemaLince(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amarillo,
            onPrimary = Color.Black,
            background = Fondo,
            surface = Fondo,
            surfaceContainerLow = Fondo,
            surfaceContainer = Superficie,
            surfaceContainerHigh = Superficie,
            onSurface = Color.White,
            onSurfaceVariant = TextoSec,
            outlineVariant = Linea,
        ),
        content = content,
    )
}
