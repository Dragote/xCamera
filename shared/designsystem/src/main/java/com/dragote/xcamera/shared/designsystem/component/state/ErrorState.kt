package com.dragote.xcamera.shared.designsystem.component.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String = "Retry",
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message)
        Button(onClick = onRetry) {
            Text(text = actionLabel)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ErrorStatePreview() {
    XCameraTheme {
        Row {
            ErrorState(message = "Something went wrong", onRetry = {}, modifier = Modifier.weight(1f))
            ErrorState(
                message = "Camera permission is required",
                onRetry = {},
                modifier = Modifier.weight(1f),
                actionLabel = "Open settings",
            )
        }
    }
}
