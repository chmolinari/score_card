package com.christianmolinari.scorecard.ui.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.domain.DealingDirection
import com.christianmolinari.scorecard.ui.components.Avatar
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import kotlin.random.Random

// Asks the user to arrange the table's seating order before a game starts. The
// first dealer is picked at random; the user puts the remaining people into
// the order they sit, going counter-clockwise from the dealer. The deal then
// passes each hand in the configured direction.
//
// Reusable: the caller supplies the people and handles persistence via
// onConfirm, which receives the ordered seating with index 0 = first dealer.
// Up/down arrows replace the iOS drag-reorder (Compose has no built-in
// drag-reorder; the arrows are the deliberate platform adaptation).
@Composable
fun SeatingArrangement(
    people: List<PlayerEntity>,
    direction: DealingDirection,
    confirmTitle: String,
    isSaving: Boolean = false,
    onConfirm: (List<PlayerEntity>) -> Unit,
) {
    // Random first dealer on first composition; the rest keep the caller's
    // order (port of initializeIfNeeded + reshuffleDealer on iOS).
    val initialOrder = remember(people) {
        if (people.isEmpty()) {
            emptyList()
        } else {
            val pool = people.toMutableList()
            val first = pool.removeAt(Random.nextInt(pool.size))
            listOf(first) + pool
        }
    }
    var dealer by remember(initialOrder) { mutableStateOf(initialOrder.firstOrNull()) }
    var others by remember(initialOrder) { mutableStateOf(initialOrder.drop(1)) }
    // Drives the manual dealer-picker dialog (tap the dealer card to open it).
    var isPickingDealer by remember { mutableStateOf(false) }

    fun reshuffleDealer() {
        if (people.isEmpty()) return
        val pool = people.toMutableList()
        dealer = pool.removeAt(Random.nextInt(pool.size))
        others = pool
    }

    // Manually set `player` as the first dealer, keeping the rest of the
    // seating order stable: the previous dealer simply takes the chosen
    // player's old place in the line-up.
    fun selectDealer(player: PlayerEntity) {
        val current = dealer ?: return
        if (current.id == player.id) return
        val index = others.indexOfFirst { it.id == player.id }
        if (index >= 0) {
            others = others.toMutableList().also { it[index] = current }
        }
        dealer = player
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (index !in others.indices || target !in others.indices) return
        others = others.toMutableList().also {
            val tmp = it[index]
            it[index] = it[target]
            it[target] = tmp
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayfulSectionHeader("Dealer", Icons.Filled.BackHand)
        dealer?.let { current ->
            CardTile(onClick = { isPickingDealer = true }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Avatar(current.name)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(current.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "First dealer · tap to choose",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { reshuffleDealer() }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle")
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "The first dealer is random — tap the dealer to pick someone else, or shuffle " +
                "for another random pick. Use the arrows to put the others in the order they " +
                "sit around the table — the deal passes ${direction.adverb} each hand. " +
                "In team games, the dealer is still an individual player.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Seats are stored counter-clockwise from the dealer regardless of the
        // dealing direction, so this header stays literal (as on iOS).
        PlayfulSectionHeader("Then, counter-clockwise")
        if (others.isEmpty()) {
            CardTile {
                Text(
                    "No other players.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            others.forEachIndexed { index, player ->
                CardTile {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${index + 2}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(player.name, modifier = Modifier.weight(1f))
                        IconButton(onClick = { move(index, -1) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(onClick = { move(index, 1) }, enabled = index < others.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { dealer?.let { onConfirm(listOf(it) + others) } },
            enabled = dealer != null && !isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(confirmTitle)
            }
        }
    }

    if (isPickingDealer) {
        // List of everyone at the table for manually choosing the first dealer.
        AlertDialog(
            onDismissRequest = { isPickingDealer = false },
            title = { Text("Choose First Dealer") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(people, key = { it.id }) { person ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectDealer(person)
                                    isPickingDealer = false
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(person.name, modifier = Modifier.weight(1f))
                            if (person.id == dealer?.id) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Current dealer",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { isPickingDealer = false }) { Text("Cancel") }
            },
        )
    }
}
