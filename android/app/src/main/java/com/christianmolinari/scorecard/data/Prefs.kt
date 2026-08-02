package com.christianmolinari.scorecard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.christianmolinari.scorecard.domain.CompetitorSortOrder
import com.christianmolinari.scorecard.domain.DealingDirection
import com.christianmolinari.scorecard.domain.DrawDealingRule
import com.christianmolinari.scorecard.data.log.ActionLogSize
import com.christianmolinari.scorecard.domain.NegativeScores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// App-wide preferences. Key strings and enum raw values are identical to the
// iOS @AppStorage keys so a user's choices mean the same thing on both
// platforms — renaming them would silently reset everyone's saved preference.
class Prefs(private val context: Context) {

    private object Keys {
        val dealingDirection = stringPreferencesKey("dealingDirection")
        val drawDealingRule = stringPreferencesKey("drawDealingRule")
        val playersSortOrder = stringPreferencesKey("playersSortOrder")
        val teamsSortOrder = stringPreferencesKey("teamsSortOrder")
        val hasSeededGameNames = booleanPreferencesKey("hasSeededGameNames")
        val allowNegativeScores = booleanPreferencesKey(NegativeScores.STORAGE_KEY)
        val actionLogEnabled = booleanPreferencesKey(ActionLogSize.ENABLED_KEY)
        val actionLogMaxMiB = intPreferencesKey(ActionLogSize.MAX_MIB_KEY)
    }

    // Whether the action log records anything. On by default: an audit trail
    // that has to be switched on before it is useful is not there when needed.
    val actionLogEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.actionLogEnabled] ?: true }

    suspend fun setActionLogEnabled(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.actionLogEnabled] = value }
    }

    // Total size the log may occupy on disk, in MiB.
    val actionLogMaxMiB: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[Keys.actionLogMaxMiB] ?: ActionLogSize.DEFAULT_MIB }

    suspend fun setActionLogMaxMiB(value: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.actionLogMaxMiB] = value }
    }

    // The direction the deal passes around the table after each hand.
    val dealingDirection: Flow<DealingDirection> = context.dataStore.data
        .map { prefs -> DealingDirection.fromRaw(prefs[Keys.dealingDirection]) }

    suspend fun setDealingDirection(value: DealingDirection) {
        context.dataStore.edit { prefs -> prefs[Keys.dealingDirection] = value.rawValue }
    }

    // Who deals the next hand when a hand ends in a draw.
    val drawDealingRule: Flow<DrawDealingRule> = context.dataStore.data
        .map { prefs -> DrawDealingRule.fromRaw(prefs[Keys.drawDealingRule]) }

    suspend fun setDrawDealingRule(value: DrawDealingRule) {
        context.dataStore.edit { prefs -> prefs[Keys.drawDealingRule] = value.rawValue }
    }

    // How the Players list is ordered; kept per tab, like the iOS preference.
    val playersSortOrder: Flow<CompetitorSortOrder> = context.dataStore.data
        .map { prefs -> CompetitorSortOrder.fromRaw(prefs[Keys.playersSortOrder]) }

    suspend fun setPlayersSortOrder(value: CompetitorSortOrder) {
        context.dataStore.edit { prefs -> prefs[Keys.playersSortOrder] = value.rawValue }
    }

    // How the Teams list is ordered.
    val teamsSortOrder: Flow<CompetitorSortOrder> = context.dataStore.data
        .map { prefs -> CompetitorSortOrder.fromRaw(prefs[Keys.teamsSortOrder]) }

    suspend fun setTeamsSortOrder(value: CompetitorSortOrder) {
        context.dataStore.edit { prefs -> prefs[Keys.teamsSortOrder] = value.rawValue }
    }

    // Guards the one-time backfill of GameNames from existing game titles
    // (port of GameName.seedFromExistingGames); the in-flow emptiness check
    // is a second safety net.
    val hasSeededGameNames: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.hasSeededGameNames] ?: false }

    suspend fun setHasSeededGameNames(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.hasSeededGameNames] = value }
    }

    // Whether a competitor's total may drop below zero. Off (default) clamps any
    // score-writing action at zero; see NegativeScores / docs/scoring-rules.md.
    val allowNegativeScores: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.allowNegativeScores] ?: false }

    suspend fun setAllowNegativeScores(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.allowNegativeScores] = value }
    }
}
