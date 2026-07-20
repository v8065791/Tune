package dev.tune.player.ui.theme

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
import dev.tune.player.data.ThemeMode

private val Purple = Color(0xFF7C5CFF)
private val PurpleLight = Color(0xFFB9A7FF)

private val DarkColors = darkColorScheme(
    primary = PurpleLight,
    onPrimary = Color(0xFF2A1B5E),
    primaryContainer = Color(0xFF3F2E86),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFC9C2DC),
    background = Color(0xFF121016),
    surface = Color(0xFF121016),
    surfaceVariant = Color(0xFF2C2836),
)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF20005E),
    secondary = Color(0xFF615B71),
    background = Color(0xFFFDFBFF),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE7E0EB),
)

@Composable
fun TuneTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** True black surfaces in dark mode, so OLED panels can switch pixels off entirely. */
    blackTheme: Boolean = false,
    /** Material You wallpaper colours, where the platform supports them. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    var colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    if (darkTheme && blackTheme) {
        // Override after the scheme is picked so this applies to dynamic colours too.
        colors = colors.copy(background = Color.Black, surface = Color.Black)
    }

    MaterialTheme(colorScheme = colors, content = content)
}
