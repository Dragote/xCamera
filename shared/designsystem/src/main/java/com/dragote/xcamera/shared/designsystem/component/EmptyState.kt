package com.dragote.xcamera.shared.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message)
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    XCameraTheme {
        EmptyState(message = "Nothing here yet")
    }
}
