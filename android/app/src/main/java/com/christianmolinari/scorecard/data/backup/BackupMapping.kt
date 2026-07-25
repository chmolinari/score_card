// The parts of the backup conversion that are pure — no DAO, no Android — so
// the cross-platform contract can be pinned by JVM unit tests instead of only
// by whatever a manual export/restore happens to exercise.

package com.christianmolinari.scorecard.data.backup

import com.christianmolinari.scorecard.data.db.GameEditEntity
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.domain.sortedEdits

object BackupMapping {

    // A game's corrections on the way out, newest first — the order the iOS
    // exporter writes, so a file from either app reads the same way.
    fun gameEdits(game: GameWithDetails): List<GameEditDTO> =
        game.sortedEdits.map { GameEditDTO(reason = it.reason, editedAt = it.editedAt) }

    // The rows to insert for a restored game's corrections. Takes the freshly
    // inserted game's id explicitly: attaching these to the wrong game would
    // silently move an edit history onto another match, and backups are the
    // only path between platforms.
    fun editEntities(gameId: Long, dtos: List<GameEditDTO>?): List<GameEditEntity> =
        dtos.orEmpty().map {
            GameEditEntity(gameId = gameId, reason = it.reason, editedAt = it.editedAt)
        }
}
