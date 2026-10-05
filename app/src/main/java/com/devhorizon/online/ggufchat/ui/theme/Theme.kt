package com.devhorizon.online.ggufchat.ui.theme

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

// 6 UI themes: light, dark, green, orange, light blue, yellow
enum class AppTheme { LIGHT, DARK, GREEN, ORANGE, LIGHT_BLUE, YELLOW }

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6650a4),
    secondary = Color(0xFF625b71),
    tertiary = Color(0xFF7D5260)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8)
)

private val GreenColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    secondary = Color(0xFF4CAF50),
    tertiary = Color(0xFF81C784)
)

private val OrangeColorScheme = lightColorScheme(
    primary = Color(0xFFE65100),
    secondary = Color(0xFFFF9800),
    tertiary = Color(0xFFFFCC80)
)

private val LightBlueColorScheme = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFF4FC3F7),
    tertiary = Color(0xFF81D4FA)
)

private val YellowColorScheme = lightColorScheme(
    primary = Color(0xFFF57F17),
    secondary = Color(0xFFFFEB3B),
    tertiary = Color(0xFFFFF176)
)

@Composable
fun GGUFChatTemplateTheme(
    appTheme: AppTheme = AppTheme.LIGHT,
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        AppTheme.LIGHT -> {
            val context = LocalContext.current
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(context)
            } else {
                LightColorScheme
            }
        }
        AppTheme.DARK -> {
            val context = LocalContext.current
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                DarkColorScheme
            }
        }
        AppTheme.GREEN -> GreenColorScheme
        AppTheme.ORANGE -> OrangeColorScheme
        AppTheme.LIGHT_BLUE -> LightBlueColorScheme
        AppTheme.YELLOW -> YellowColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
