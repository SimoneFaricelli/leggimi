package it.faricelli.leggimi.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF7A4A12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBC),
    onPrimaryContainer = Color(0xFF2B1700),
    secondary = Color(0xFF715A47),
    background = Color(0xFFFFF8F4),
    surface = Color(0xFFFFF8F4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF6BA7A),
    onPrimary = Color(0xFF482A00),
    primaryContainer = Color(0xFF653E00),
    onPrimaryContainer = Color(0xFFFFDCBC),
    secondary = Color(0xFFE0C1A8),
    background = Color(0xFF1A120C),
    surface = Color(0xFF1A120C),
)

@Composable
fun LeggimiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
