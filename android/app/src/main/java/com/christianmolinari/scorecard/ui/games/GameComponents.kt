// ModalBottomSheet is still behind the experimental marker in Material 3;
// opting in file-wide keeps the build green across library releases.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.christianmolinari.scorecard.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantWithDetails
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.domain.displayName
import com.christianmolinari.scorecard.domain.isOpen
import com.christianmolinari.scorecard.domain.sortedEntries
import com.christianmolinari.scorecard.domain.subtitle
import com.christianmolinari.scorecard.domain.totalScore
import com.christianmolinari.scorecard.location.LocationCapture
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.GameFormatting
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.components.SwipeToDeleteBox
import com.christianmolinari.scorecard.ui.theme.ThemeColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.Instant
import kotlinx.coroutines.launch

// Reusable pieces shared by the scoreboard and history screens: the game info
// section and the per-competitor scoring sheet.

// Hour:minute stamp for a score entry's timestamp.
private val EntryTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

// Compact summary of a game's metadata: status, target, date/time, location,
// and (once closed) when it ended and how long it ran. Renders as its own card
// tile so both the scoreboard and the detail screen can drop it in bare.
@Composable
fun GameInfoSection(game: GameWithDetails) {
    val context = LocalContext.current

    // Address resolved on the fly for games that only stored raw coordinates
    // (e.g. captured before reverse geocoding succeeded). We never show raw
    // latitude/longitude — only a human-readable address.
    var resolvedAddress by remember(game.game.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(game.game.id, game.game.locationName) {
        resolvedAddress = null
        if (game.game.locationName != null) return@LaunchedEffect
        val latitude = game.game.latitude ?: return@LaunchedEffect
        val longitude = game.game.longitude ?: return@LaunchedEffect
        // Best-effort and silent on failure, like the iOS header.
        resolvedAddress = LocationCapture.reverseGeocode(context, latitude, longitude)
    }
    // The address to show: the one captured with the game, or one we reverse
    // geocoded lazily from its coordinate.
    val displayAddress = game.game.locationName ?: resolvedAddress

    CardTile(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (game.isOpen) {
                    InfoChip(text = "In Progress", icon = Icons.Filled.Sensors, tint = ThemeColors.teal)
                } else {
                    InfoChip(
                        text = "Finished",
                        icon = Icons.Filled.Verified,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val target = game.game.targetPoints
                if (game.game.hasTarget && target != null) {
                    InfoChip(text = "First to $target", icon = Icons.Filled.Flag, tint = ThemeColors.sky)
                } else {
                    InfoChip(text = "Open-ended", icon = Icons.Filled.AllInclusive, tint = ThemeColors.sky)
                }
            }

            InfoRow(icon = Icons.Filled.Event, text = GameFormatting.dateTime(game.game.createdAt))

            if (displayAddress != null) {
                InfoRow(icon = Icons.Filled.Place, text = displayAddress)
            }

            val closedAt = game.game.closedAt
            if (closedAt != null) {
                InfoRow(
                    icon = Icons.Filled.CheckCircle,
                    text = "Ended ${GameFormatting.dateTime(closedAt)} · " +
                        GameFormatting.duration(from = game.game.createdAt, to = closedAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Small capsule chip with an icon, like the iOS chip(text:systemImage:tint:).
@Composable
private fun InfoChip(text: String, icon: ImageVector, tint: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
    }
}

// One icon + text metadata line.
@Composable
private fun InfoRow(
    icon: ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

// Sheet for adding (or correcting) a competitor's score, with quick chips, a
// custom amount, and a swipe-to-undo history of this competitor's entries.
// The "undo" is exact because scores are stored as individual entries.
@Composable
fun ParticipantScoringSheet(
    container: AppContainer,
    participant: ParticipantWithDetails,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gameDao = container.database.gameDao()

    var customAmount by remember { mutableIntStateOf(1) }
    val quickAmounts = listOf(1, 2, 3, 5, 10)

    fun add(points: Int) {
        if (points == 0) return
        scope.launch {
            gameDao.insertScoreEntry(
                ScoreEntryEntity(
                    participantId = participant.participant.id,
                    points = points,
                    timestamp = Instant.now(),
                )
            )
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Big live total — the participant passed in comes from the
            // observed Room flow, so it refreshes as entries are added/removed.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${participant.totalScore}",
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = participant.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = participant.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PlayfulSectionHeader(title = "Quick Add")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickAmounts.forEach { amount ->
                    Button(
                        onClick = { add(amount) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Text("+$amount")
                    }
                }
            }

            PlayfulSectionHeader(title = "Custom")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Amount", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { customAmount -= 1 }, enabled = customAmount > -100) {
                    Icon(imageVector = Icons.Filled.Remove, contentDescription = "Decrease amount")
                }
                Text(
                    text = GameFormatting.signedPoints(customAmount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { customAmount += 1 }, enabled = customAmount < 100) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = "Increase amount")
                }
            }
            OutlinedButton(
                onClick = { add(customAmount) },
                enabled = customAmount != 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add ${GameFormatting.signedPoints(customAmount)} points")
            }

            val entries = participant.sortedEntries
            if (entries.isNotEmpty()) {
                PlayfulSectionHeader(title = "Entries")
                // Newest first; swiping a row away deletes that exact entry.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        SwipeToDeleteBox(
                            onDelete = { scope.launch { gameDao.deleteScoreEntry(entry) } },
                        ) {
                            EntryRow(entry)
                        }
                    }
                }
            }
        }
    }
}

// One past scoring event: "+3" (negative corrections in red) and its time.
@Composable
private fun EntryRow(entry: ScoreEntryEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque, rounded like the swipe reveal so the red delete
            // background only shows while swiping.
            .clip(RoundedCornerShape(22.dp))
            .background(ThemeColors.cardSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = GameFormatting.signedPoints(entry.points),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (entry.points >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = EntryTimeFormatter.format(entry.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
