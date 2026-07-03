package dev.r0mai.gpsvisualizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.r0mai.gpsvisualizer.ui.GpsVisualizerTheme
import dev.r0mai.gpsvisualizer.ui.MapScreen
import dev.r0mai.gpsvisualizer.ui.MapViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
