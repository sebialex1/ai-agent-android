package com.sebialex.agent.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.model.WebPreview
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * A web app the agent built (preview_webapp): its name and folder, one tap opens the preview
 * window. A folder that no longer exists shows as unavailable instead of opening a blank page.
 */
@Composable
fun PreviewCard(preview: WebPreview, available: Boolean, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .softSurface(shape, Palette.Surface, elevation = 2.dp)
            .clip(shape)
            .clickable(enabled = available, onClick = onOpen)
            .padding(start = 12.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(R.drawable.ic_globe, null, tint = if (available) Palette.Accent else Palette.IconMuted, size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(preview.title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (available) "Preview \u00b7 ${preview.path}" else "${preview.path} no longer exists",
                style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (available) {
            Spacer(Modifier.width(10.dp))
            AppIcon(R.drawable.ic_open_external, "Open preview", tint = Palette.IconActive, size = 18.dp)
        }
    }
}
