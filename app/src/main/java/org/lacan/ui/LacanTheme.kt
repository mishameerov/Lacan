package org.lacan.ui

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal enum class Appearance(val title: String) {
    System("Системная"), Light("Светлая"), Dark("Тёмная")
}

@Composable
internal fun LacanTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    val context = LocalContext.current
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                @Suppress("DEPRECATION")
                window.statusBarColor = AndroidColor.TRANSPARENT
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// Material icons are local vectors so navigation works offline without an icon font.
private val navigationIcons = listOf(
    "M12,14a3,3 0,0 0,3 -3V5a3,3 0,0 0,-6 0v6a3,3 0,0 0,3 3z M17,11a5,5 0,0 1,-10 0H5a7,7 0,0 0,6 6.92V21h2v-3.08A7,7 0,0 0,19 11z",
    "M19,3H5a2,2 0,0 0,-2 2v14a2,2 0,0 0,2 2h14a2,2 0,0 0,2 -2V5a2,2 0,0 0,-2 -2z M19,19H5V5h14z M7,7h10v2H7z M7,11h10v2H7z M7,15h6v2H7z",
    "M13,3a9,9 0,0 0,-9 9H1l4,4 4,-4H6a7,7 0,1 1,2.05 4.95l-1.42,1.42A9,9 0,1 0,13 3z M12,7v6l5,3 1,-1.73 -4,-2.37V7z",
    "M19.43,12.98a7.9,7.9 0,0 0,0 -1.96l2.11,-1.65 -2,-3.46 -2.49,1a7.6,7.6 0,0 0,-1.7 -0.98L15,3h-4l-0.35,2.93a7.6,7.6 0,0 0,-1.7 0.98l-2.49,-1 -2,3.46 2.11,1.65a7.9,7.9 0,0 0,0 1.96l-2.11,1.65 2,3.46 2.49,-1a7.6,7.6 0,0 0,1.7 0.98L11,21h4l0.35,-2.93a7.6,7.6 0,0 0,1.7 -0.98l2.49,1 2,-3.46z M13,15.5a3.5,3.5 0,1 1,0 -7 3.5,3.5 0,0 1,0 7z"
).mapIndexed { index, path ->
    ImageVector.Builder("Navigation$index", 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()
}

internal fun destinationIcon(index: Int): ImageVector = navigationIcons[index]
