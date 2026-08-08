package com.dragote.xcamera.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragote.xcamera.feature.settings.presentation.SettingsUiState
import com.dragote.xcamera.feature.settings.presentation.SettingsViewModel
import com.dragote.xcamera.shared.designsystem.component.CameraLever
import com.dragote.xcamera.shared.designsystem.component.LeverGlyph
import com.dragote.xcamera.shared.designsystem.component.LoadingIndicator
import com.dragote.xcamera.shared.designsystem.theme.AppChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import com.dragote.xcamera.shared.navigation.SettingsRoutes
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/**
 * Reached from `feature:camera`'s toolbar settings button via the plain route constant
 * [SettingsRoutes.SETTINGS_SCREEN] — feature modules never depend on each other directly, so
 * `feature:camera` navigates by route rather than by a generated `SettingsScreenDestination`.
 */
@Destination(route = SettingsRoutes.SETTINGS_SCREEN)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navigator: DestinationsNavigator,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(AppChrome.BodyGradient)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("SETTINGS") },
                    navigationIcon = {
                        IconButton(onClick = navigator::navigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { innerPadding ->
            val loadedState = uiState
            if (loadedState == null) {
                LoadingIndicator(modifier = Modifier.padding(innerPadding))
            } else {
                SettingsContent(
                    uiState = loadedState,
                    onShowGridToggled = viewModel::onShowGridToggled,
                    onShowHistogramToggled = viewModel::onShowHistogramToggled,
                    onShowHorizonLineToggled = viewModel::onShowHorizonLineToggled,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

/**
 * Places the same [CameraLever] toggle FLASH/GRID/MODE use directly on the screen's own
 * [AppChrome.BodyGradient] — no wrapping card/recess around each one, mirroring exactly how
 * `feature:camera`'s toolbar Row presents `FlashLever`/`ModeLever`.
 */
@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    onShowGridToggled: (Boolean) -> Unit,
    onShowHistogramToggled: (Boolean) -> Unit,
    onShowHorizonLineToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        CameraLever(
            checked = uiState.showGrid,
            onToggle = { onShowGridToggled(!uiState.showGrid) },
            label = "GRID",
            glyph = LeverGlyph.Grid,
        )
        CameraLever(
            checked = uiState.showHistogram,
            onToggle = { onShowHistogramToggled(!uiState.showHistogram) },
            label = "HISTOGRAM",
            glyph = LeverGlyph.None,
        )
        CameraLever(
            checked = uiState.showHorizonLine,
            onToggle = { onShowHorizonLineToggled(!uiState.showHorizonLine) },
            label = "HORIZON",
            glyph = LeverGlyph.None,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun SettingsContentPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CameraLever(checked = false, onToggle = {}, label = "GRID", glyph = LeverGlyph.Grid)
            CameraLever(checked = true, onToggle = {}, label = "GRID", glyph = LeverGlyph.Grid)
            CameraLever(checked = false, onToggle = {}, label = "HISTOGRAM")
            CameraLever(checked = true, onToggle = {}, label = "HISTOGRAM")
        }
    }
}
