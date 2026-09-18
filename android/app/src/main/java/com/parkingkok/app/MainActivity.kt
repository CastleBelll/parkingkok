package com.parkingkok.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.ui.navigation.ParkingkokApp

/**
 * The single entry point. It owns the window and nothing else — the shell, its back stack
 * and every screen live in [ParkingkokApp] (docs/03_SYSTEM_ARCHITECTURE.md §5 keeps
 * business logic out of the Activity).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The fallback only fires under a harness whose Application is not ours. It is
        // safe because `preferencesDataStore` memoizes one DataStore per process, so a
        // second container still reads and writes the same store.
        val container = ParkingkokApplication.containerOf(this) ?: AppContainer(this)
        setContent {
            ParkingkokTheme {
                ParkingkokApp(container = container)
            }
        }
    }
}
