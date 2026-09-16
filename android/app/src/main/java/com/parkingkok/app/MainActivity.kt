package com.parkingkok.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.ui.diagnostics.DiagnosticsScreen
import com.parkingkok.app.ui.diagnostics.DiagnosticsViewModel

/**
 * Single P0 entry point. Hosts the detection diagnostics screen only — product UI lands
 * after the detection engine (CLAUDE.md Development Order).
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
                val viewModel: DiagnosticsViewModel =
                    viewModel(factory = DiagnosticsViewModel.factory(container))
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                // The permission can be revoked in Settings while the app is backgrounded,
                // so re-read it and reconcile on every resume.
                LifecycleResumeEffect(Unit) {
                    viewModel.refresh()
                    onPauseOrDispose { }
                }

                DiagnosticsScreen(
                    state = state,
                    onDetectionEnabledChange = viewModel::setDetectionEnabled,
                    onPermissionResult = viewModel::refresh,
                    onCaptureModeChange = viewModel::setCaptureMode,
                    onExportDiagnostics = viewModel::exportDiagnostics,
                    onClearEvents = viewModel::clearEventLog,
                    onTraceLabelChange = viewModel::setTraceLabel,
                )
            }
        }
    }
}
