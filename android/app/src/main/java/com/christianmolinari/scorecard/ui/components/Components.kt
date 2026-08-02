// Surface(onClick:) and the SwipeToDismissBox family have moved in and out of
// the experimental marker across Material 3 releases; opting in file-wide keeps
// the build green either way (redundant opt-in is only a warning).
@file:OptIn(ExperimentalMaterial3Api::class)

package com.christianmolinari.scorecard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.christianmolinari.scorecard.domain.Tally
import com.christianmolinari.scorecard.ui.theme.ThemeColors

// Shared building blocks for the "warm & playful" look (see ui/theme/Theme.kt):
// the gradient backdrop, the floating card tiles, name avatars, and the small
// reusable list/dialog pieces every screen leans on.

// The soft diagonal gradient that sits behind every screen.
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(ThemeColors.backgroundTop, ThemeColors.backgroundBottom),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            ),
        content = content,
    )
}

// Wraps content in a rounded, softly-shadowed surface — the "playing card"
// look used for list rows and panels.
@Composable
fun CardTile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = ThemeColors.cardSurface,
            shadowElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = ThemeColors.cardSurface,
            shadowElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

// A colorful circular badge for a person or team — initials by default, or an
// icon. The hue is derived from the name so it's stable and recognizable.
@Composable
fun Avatar(name: String, icon: ImageVector? = null, size: Dp = 44.dp) {
    val color = ThemeColors.accentFor(name)
    Box(
        modifier = Modifier
            .size(size)
            .shadow(elevation = 4.dp, shape = CircleShape, ambientColor = color, spotColor = color)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        } else {
            Text(
                text = initials(name),
                color = Color.White,
                fontSize = (size.value * 0.40f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// First letters of the first two words, uppercased; "?" when there's nothing.
private fun initials(name: String): String {
    val parts = name.split(" ").filter { it.isNotEmpty() }
    val combined = buildString {
        parts.getOrNull(0)?.firstOrNull()?.let { append(it) }
        parts.getOrNull(1)?.firstOrNull()?.let { append(it) }
    }
    return if (combined.isEmpty()) "?" else combined.uppercase()
}

// Compact win/play record shown on player and team rows.
@Composable
fun TallyBadge(tally: Tally) {
    if (tally.isEmpty) {
        Text(
            text = "No games yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        // Read the whole badge as one sentence to screen readers, like the
        // iOS accessibilityLabel, instead of five tiny fragments.
        val description = buildString {
            append("${tally.won} won")
            if (tally.drawn > 0) append(", ${tally.drawn} drawn")
            append(", ${tally.played} played")
            tally.winPercentage?.let { append(", $it percent") }
            if (tally.inProgress > 0) append(", ${tally.inProgress} in progress")
        }
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TallyStat(Icons.Filled.EmojiEvents, tally.won.toString(), ThemeColors.amber)
            if (tally.drawn > 0) {
                TallyStat(Icons.Filled.DragHandle, tally.drawn.toString(), ThemeColors.plum)
            }
            TallyStat(Icons.Filled.SportsScore, tally.played.toString(), secondary)
            tally.winPercentage?.let { pct ->
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
            }
            if (tally.inProgress > 0) {
                TallyStat(Icons.Filled.Sensors, tally.inProgress.toString(), ThemeColors.teal)
            }
        }
    }
}

@Composable
private fun TallyStat(icon: ImageVector, value: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

// A small, friendly section title for use above a group of card tiles.
@Composable
fun PlayfulSectionHeader(title: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.padding(start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ThemeColors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = ThemeColors.accent,
        )
    }
}

// Centered placeholder for empty lists, mirroring iOS ContentUnavailableView.
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
        }
    }
}

// Swipe-to-delete wrapper for list rows; end-to-start only, like the iOS
// list rows. The background is clipped to the card-tile radius so the red
// reveal hugs the rounded row.
@Composable
fun SwipeToDeleteBox(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    // When true the swipe only reports intent: the row snaps back instead of
    // dismissing, so the caller can confirm first and delete itself. Off by
    // default so the call sites that delete outright keep their behaviour.
    confirmFirst: Boolean = false,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                !confirmFirst
            } else {
                false
            }
        }
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        content()
    }
}

// One-button informational alert used for status and error results.
@Composable
fun StatusAlertDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

// Dimmed full-screen scrim with a spinner, shown while a long task runs.
@Composable
fun WorkingOverlay(visible: Boolean) {
    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}
