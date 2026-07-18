package org.eidora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * Renders a circular thumbnail from a file.
 * Uses aspectRatio(1f) to guarantee a perfect circle regardless of parent constraints.
 */
@Composable
fun CircleThumbnail(
    file: File?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    borderWidth: Dp = 3.dp,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .then(
                    if (borderColor != null) {
                        Modifier
                            .background(borderColor, CircleShape)
                            .padding(borderWidth)
                    } else {
                        Modifier
                    },
                ).clip(CircleShape),
    ) {
        if (file != null && file.exists()) {
            AsyncImage(
                model = file,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF9E9E9E)),
            )
        }
        overlay()
    }
}

/**
 * Colored circle with a text label (for virtual persons or placeholders).
 */
@Composable
fun CircleColorLabel(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}
