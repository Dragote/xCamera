package com.dragote.xcamera.feature.diagnostics.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragote.xcamera.feature.diagnostics.presentation.DiagnosticsUiState
import com.dragote.xcamera.feature.diagnostics.presentation.DiagnosticsViewModel
import com.dragote.xcamera.feature.diagnostics.ui.component.FullyCapableMainLens
import com.dragote.xcamera.feature.diagnostics.ui.component.LensDiagnosticsPage
import com.dragote.xcamera.shared.designsystem.component.state.ErrorState
import com.dragote.xcamera.shared.designsystem.component.state.LoadingIndicator
import com.dragote.xcamera.shared.designsystem.theme.MinimalChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics
import com.dragote.xcamera.shared.navigation.DiagnosticsRoutes
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/**
 * Reached from `feature:settings`'s toolbar info button via the plain route constant
 * [DiagnosticsRoutes.DIAGNOSTICS_SCREEN] — feature modules never depend on each other directly, so
 * `feature:settings` navigates to it by route rather than a generated `DiagnosticsScreenDestination`,
 * mirroring `ui/SettingsScreen`'s own doc for [com.dragote.xcamera.shared.navigation.SettingsRoutes].
 */
@Destination(route = DiagnosticsRoutes.DIAGNOSTICS_SCREEN)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    navigator: DestinationsNavigator,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var cameraPermissionGranted by remember { mutableStateOf<Boolean?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraPermissionGranted = granted }

    LaunchedEffect(Unit) {
        val alreadyGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            cameraPermissionGranted = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MinimalChrome.Background)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("DIAGNOSTICS", style = MinimalChrome.valueStyle()) },
                    navigationIcon = {
                        IconButton(onClick = navigator::navigateUp) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MinimalChrome.Ink,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MinimalChrome.Ink,
                        navigationIconContentColor = MinimalChrome.Ink,
                    ),
                )
            },
        ) { innerPadding ->
            when (cameraPermissionGranted) {
                null -> Box(modifier = Modifier.padding(innerPadding).fillMaxSize())
                false -> ErrorState(
                    message = "Camera permission is required to read lens diagnostics",
                    onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(innerPadding),
                )
                true -> DiagnosticsContent(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            }
        }
    }
}

@Composable
private fun DiagnosticsContent(viewModel: DiagnosticsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        DiagnosticsUiState.Loading -> LoadingIndicator(modifier = modifier)
        is DiagnosticsUiState.Error -> ErrorState(
            message = state.message,
            onRetry = viewModel::loadDiagnostics,
            modifier = modifier,
        )
        is DiagnosticsUiState.Loaded -> LensPager(lenses = state.lenses, modifier = modifier)
    }
}

/**
 * One full-screen [LensDiagnosticsPage] per lens, swiped through via [HorizontalPager] instead of
 * stacked in a single scrolling list — a full-page layout gives each lens room for a large lens-icon-led
 * hierarchy that a flat list of equal-weight cards didn't have space for. [PageIndicator] below the
 * pager is the only cue for how many lenses there are and which one is showing, since swiping alone
 * doesn't otherwise reveal that.
 */
@Composable
private fun LensPager(lenses: List<LensDiagnostics>, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(pageCount = { lenses.size })
    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            LensDiagnosticsPage(lens = lenses[page], modifier = Modifier.fillMaxSize())
        }
        PageIndicator(
            pageCount = lenses.size,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
        )
    }
}

/** Small [MinimalChrome.Ink] dots — filled for the current page, a low-alpha outline for the rest —
 *  matching this identity's flat line-art/no-glow language rather than a filled-vs-tinted Material
 *  dot-indicator look. */
@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isCurrent = index == currentPage
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) MinimalChrome.Ink else Color.Transparent)
                    .border(
                        width = MinimalChrome.StrokeWidth,
                        color = MinimalChrome.Ink.copy(alpha = if (isCurrent) 0f else 0.4f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun PageIndicatorPreview() {
    XCameraTheme {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageIndicator(pageCount = 3, currentPage = 0)
            PageIndicator(pageCount = 3, currentPage = 1)
            PageIndicator(pageCount = 3, currentPage = 2)
        }
    }
}

/** [HorizontalPager] itself doesn't preview meaningfully as a static image — this previews a single
 *  page's real content instead; see `ui/component/LensDiagnosticsPage.kt`'s own previews for the other
 *  two sample lenses. */
@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC, widthDp = 360, heightDp = 780)
@Composable
private fun DiagnosticsContentSinglePagePreview() {
    XCameraTheme {
        Column(modifier = Modifier.fillMaxSize().background(MinimalChrome.Background)) {
            LensDiagnosticsPage(lens = FullyCapableMainLens, modifier = Modifier.weight(1f))
            PageIndicator(pageCount = 3, currentPage = 0, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
        }
    }
}
