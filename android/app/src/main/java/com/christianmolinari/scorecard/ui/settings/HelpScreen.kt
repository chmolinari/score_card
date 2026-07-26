// "How to Use ScoreCard": an index of help topics reached from Settings, and the
// page for one topic. The prose lives in domain/HelpTopic.kt, transcribed from
// docs/help-content.md — this file only renders it, so a wording change never
// touches Compose code. Both screens are pushed, not tabs, so they carry a back
// arrow and the bottom bar hides itself.

package com.christianmolinari.scorecard.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.christianmolinari.scorecard.domain.HelpBlock
import com.christianmolinari.scorecard.domain.HelpIcon
import com.christianmolinari.scorecard.domain.HelpTint
import com.christianmolinari.scorecard.domain.HelpTopic
import com.christianmolinari.scorecard.domain.helpTopics
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.theme.ThemeColors

// The list of topics. Tapping one pushes its page by identifier — the same
// identifier the cross-platform contract pins — so routing survives a reorder.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onOpenTopic: (String) -> Unit, onBack: () -> Unit) {
    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Help") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(helpTopics, key = { it.id }) { topic ->
                    TopicRow(topic = topic, onClick = { onOpenTopic(topic.id) })
                }
            }
        }
    }
}

// One topic's page. An unknown identifier (a stale back stack, say) simply shows
// nothing rather than crashing.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpTopicScreen(topicId: String, onBack: () -> Unit) {
    val topic = helpTopics.firstOrNull { it.id == topicId }
    val tint = helpTint(topic?.tint ?: HelpTint.Coral)

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(topic?.title ?: "Help") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(topic?.blocks.orEmpty()) { block ->
                    when (block) {
                        is HelpBlock.Paragraph -> ParagraphBlock(block.text)
                        is HelpBlock.Steps -> StepsBlock(items = block.items, tint = tint)
                        is HelpBlock.Bullets -> BulletsBlock(items = block.items, tint = tint)
                        is HelpBlock.Note -> NoteBlock(text = block.text, tint = tint)
                    }
                }
            }
        }
    }
}

// --- Index row ---

@Composable
private fun TopicRow(topic: HelpTopic, onClick: () -> Unit) {
    val tint = helpTint(topic.tint)
    CardTile(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(tint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = helpIcon(topic.icon),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// --- Block renderers ---

@Composable
private fun ParagraphBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// A numbered procedure. The number sits in a small tinted badge so the sequence
// reads at a glance without relying on the text alignment.
@Composable
private fun StepsBlock(items: List<String>, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(tint.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = tint,
                    )
                }
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BulletsBlock(items: List<String>, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A dot on the text's first line, nudged down to sit optically
                // centred against it.
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(6.dp)
                        .background(tint, CircleShape),
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// The rule behind the behaviour: a tinted card at low alpha, deliberately
// unlike body text so it reads as an aside rather than the next paragraph.
@Composable
private fun NoteBlock(text: String, tint: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = tint.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// --- Domain intents resolved to Compose values ---
//
// domain/HelpTopic.kt names an icon and a tint intent so it stays free of
// Android types; the mapping to a real glyph and colour belongs here.

@Composable
private fun helpIcon(icon: HelpIcon): ImageVector = when (icon) {
    HelpIcon.Rocket -> Icons.Filled.RocketLaunch
    HelpIcon.Play -> Icons.Filled.PlayArrow
    HelpIcon.Cards -> Icons.Filled.Style
    HelpIcon.Hand -> Icons.Filled.BackHand
    HelpIcon.Flag -> Icons.Filled.Flag
    HelpIcon.Pencil -> Icons.Filled.Edit
    HelpIcon.Clock -> Icons.Filled.Schedule
    HelpIcon.People -> Icons.Filled.People
    HelpIcon.Cloud -> Icons.Filled.Cloud
}

@Composable
private fun helpTint(tint: HelpTint): Color = when (tint) {
    HelpTint.Coral -> ThemeColors.coral
    HelpTint.Teal -> ThemeColors.teal
    HelpTint.Amber -> ThemeColors.amber
    HelpTint.Plum -> ThemeColors.plum
    HelpTint.Sky -> ThemeColors.sky
}
