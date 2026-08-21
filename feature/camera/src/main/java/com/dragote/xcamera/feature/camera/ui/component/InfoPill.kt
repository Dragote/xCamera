package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared small pill badge — [ExposingIndicator]/[LutResolvingIndicator] both need "solid black rounded
 * rect, thin white border, white mono content" over the live viewfinder feed; extracted here on the
 * second occurrence per this project's own duplication convention (`internal`, both callers share this
 * package). Solid (not translucent) black — a flat fill reads as one clean shape against arbitrary
 * scene content, where a translucent one would visibly pick up whatever color is directly behind it.
 */
@Composable
internal fun InfoPill(alpha: Float, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(1.dp, Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        content = content,
    )
}
