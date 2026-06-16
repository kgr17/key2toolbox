package com.kgr.key2toolbox

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.ui.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Forces every background/surface token to pure black while leaving
 * accent tokens (primary/secondary/tertiary etc.) untouched, so a
 * dynamic (system wallpaper-derived) scheme keeps its accent color.
 */
private fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceTint = Color.Black
)

@Composable
private fun pureBlackColorScheme(): ColorScheme {
    val context = LocalContext.current
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Pulls accent colors from the system wallpaper/theme (Material You).
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }
    return base.toPureBlack()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // IMPORTANT: RootShell must be the first thing to touch libsu's Shell
        // class, since RootShell's init block calls Shell.setDefaultBuilder().
        // If anything calls Shell.getShell()/Shell.cmd() first, setDefaultBuilder()
        // throws (libsu requires it to run before any shell is created), which
        // fails RootShell's <clinit> and poisons every later reference to it
        // with NoClassDefFoundError - which is exactly the crash we just saw.
        //
        // Run on a background thread since this blocks on the root grant
        // prompt (FolkPatch/APatch manager) on first launch.
        lifecycleScope.launch(Dispatchers.IO) {
            RootShell.isRootAvailable()
        }

        setContent {
            MaterialTheme(colorScheme = pureBlackColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen()
                }
            }
        }
    }
}
