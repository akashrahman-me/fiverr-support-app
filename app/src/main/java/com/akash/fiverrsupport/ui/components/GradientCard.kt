package com.akash.fiverrsupport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    gradient: Brush,
    topStartCornerRadius: Int = 0,
    topEndCornerRadius: Int = 0,
    bottomStartCornerRadius: Int = 0,
    bottomEndCornerRadius: Int = 0,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = topStartCornerRadius.dp,
        topEnd = topEndCornerRadius.dp,
        bottomStart = bottomStartCornerRadius.dp,
        bottomEnd = bottomEndCornerRadius.dp
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.1f),
                spotColor = Color.Black.copy(alpha = 0.1f)
            )
            .clip(shape)
            .background(gradient)
    ) {
        content()
    }
}

