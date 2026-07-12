package dev.r0mai.gpsvisualizer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.r0mai.gpsvisualizer.ui.GpsVisualizerTheme
import dev.r0mai.gpsvisualizer.ui.MapScreen
import dev.r0mai.gpsvisualizer.ui.MapViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The map tiles are always light-themed, so force dark status/navigation
        // bar icons regardless of the system dark-mode setting. Otherwise the
        // clock and battery in the top status row are white-on-light and
        // unreadable. SystemBarStyle.light() = dark foreground icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            GpsVisualizerTheme {
                MapScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Finish a Dropbox authorization that redirected back into the app.
        viewModel.refreshDropboxLink()
    }
}
