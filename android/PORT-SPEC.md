# ScoreCard Android port — authoring contract

> **Status: historical. Superseded by the code itself.**
>
> This document was the parallel-authoring contract used to write the Android
> port in one pass. That port is complete and has since been changed, so this
> file is a record of the original plan, **not** a description of the app as it
> stands. It is kept because the rationale behind many decisions is written down
> here and nowhere else.
>
> Do not code against it. For current guidance use `android/CLAUDE.md`, the
> platform-neutral specs in `docs/`, and the source. Where this file and the code
> disagree, **the code wins** — at least one signature here was already found to
> be wrong (`DISTANT_PAST`, corrected in place below) and others may be. It also
> predates features added after the port, including the in-app help page
> (`docs/help-content.md`).

The original contract follows, unchanged except for corrections marked
`SUPERSEDED`.

Every authoring agent MUST read it fully and code against the signatures below
EXACTLY (names, parameters, types), because other files are written in parallel
against the same contract. The iOS app at `../ios/ScoreCard/` is the reference
implementation — read the iOS files listed for your assignment and match their
behavior precisely unless this spec says otherwise.

Project root for all paths below: `android/app/src/main/java/com/christianmolinari/scorecard/`
(package `com.christianmolinari.scorecard`). Tests: `android/app/src/test/java/com/christianmolinari/scorecard/`.

General rules:
- Kotlin 2.1, Jetpack Compose + Material 3, Room (KSP), kotlinx.serialization, DataStore preferences. All dependencies are already declared in `app/build.gradle.kts`; do not edit Gradle files.
- minSdk 26 — `java.time.*` is available natively, use `Instant` everywhere for timestamps.
- Plain JavaDoc-free Kotlin style with `//` comments where the iOS source has meaningful comments; carry over the *why*, not the *what*.
- No Hilt/Dagger/Koin. Manual dependency injection via `AppContainer`.
- No ViewModels: screens are composables taking `AppContainer`, collecting Room flows with `collectAsStateWithLifecycle(initialValue = ...)`, mutating via `rememberCoroutineScope().launch { dao... }`.
- Material icons come from `androidx.compose.material.icons.extended` (`Icons.Filled.*` / `Icons.AutoMirrored.Filled.*`).

## Data layer — `data/db/` (agent: data-layer)

iOS reference: `Models/Player.swift`, `Team.swift`, `Game.swift`, `GameParticipant.swift`, `ScoreEntry.swift`, `Seat.swift`, `GameName.swift`.

### `data/db/Entities.kt`

```kotlin
// SUPERSEDED — this value was wrong and was corrected during the port. The
// shipped constant is 0001-01-01T00:00:00Z, which is what iOS's
// JSONEncoder.iso8601 actually emits for Date.distantPast; 0000-12-30 is only
// what Date.description prints. Using the value below breaks the backup
// round-trip for never-used game names. See android/CLAUDE.md.
val DISTANT_PAST: Instant = Instant.parse("0001-01-01T00:00:00Z")

@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Instant,
)

@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Instant,
)

// Many-to-many Player<->Team. Cascade both ways: deleting a player or team
// removes the membership row (mirrors SwiftData nullify of the relationship).
@Entity(tableName = "team_members", primaryKeys = ["teamId", "playerId"], ...)
data class TeamMemberCrossRef(val teamId: Long, val playerId: Long)

@Entity(tableName = "game_names")
data class GameNameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant,   // DISTANT_PAST until first used
)

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val hasTarget: Boolean = false,
    val targetPoints: Int? = null,
    val createdAt: Instant,
    val closedAt: Instant? = null,        // null => game still open
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val currentDealerIndex: Int = 0,      // index into seats ordered by position
    val currentHand: Int = 1,             // 1-based hand (manche) counter
)

// One competitor in one game: EITHER playerId OR teamId is set; nameSnapshot
// survives deletion of the underlying player/team (FKs use SET_NULL).
// gameId FK cascades. Index gameId, playerId, teamId.
@Entity(tableName = "participants", ...)
data class ParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val playerId: Long? = null,
    val teamId: Long? = null,
    val nameSnapshot: String,
    val sortIndex: Int,
)

// participantId FK cascades; index participantId.
@Entity(tableName = "score_entries", ...)
data class ScoreEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: Long,
    val points: Int,
    val timestamp: Instant,
)

// gameId FK cascades, playerId SET_NULL; indices on both.
// position 0 = first dealer; seats stored counter-clockwise.
@Entity(tableName = "seats", ...)
data class SeatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val playerId: Long? = null,
    val position: Int,
)

object InstantConverters {
    @TypeConverter fun fromEpochMillis(value: Long?): Instant?
    @TypeConverter fun toEpochMillis(value: Instant?): Long?
}
```

Note: `DISTANT_PAST` is year 0 — store Instants as epoch **millis** (`toEpochMilli()` handles negative values fine).

### `data/db/Relations.kt`

```kotlin
data class TeamWithMembers(
    @Embedded val team: TeamEntity,
    @Relation(parentColumn = "id", entityColumn = "id",
        associateBy = Junction(TeamMemberCrossRef::class, parentColumn = "teamId", entityColumn = "playerId"))
    val members: List<PlayerEntity>,
)

data class ParticipantWithDetails(
    @Embedded val participant: ParticipantEntity,
    @Relation(parentColumn = "id", entityColumn = "participantId")
    val entries: List<ScoreEntryEntity>,
    @Relation(parentColumn = "playerId", entityColumn = "id")
    val player: PlayerEntity?,
    @Relation(parentColumn = "teamId", entityColumn = "id", entity = TeamEntity::class)
    val team: TeamWithMembers?,
)

data class SeatWithPlayer(
    @Embedded val seat: SeatEntity,
    @Relation(parentColumn = "playerId", entityColumn = "id")
    val player: PlayerEntity?,
)

data class GameWithDetails(
    @Embedded val game: GameEntity,
    @Relation(parentColumn = "id", entityColumn = "gameId", entity = ParticipantEntity::class)
    val participants: List<ParticipantWithDetails>,
    @Relation(parentColumn = "id", entityColumn = "gameId", entity = SeatEntity::class)
    val seats: List<SeatWithPlayer>,
)
```

### `data/db/Daos.kt`

```kotlin
@Dao interface PlayerDao {
    @Query("SELECT * FROM players") fun observeAll(): Flow<List<PlayerEntity>>
    @Query("SELECT * FROM players ORDER BY createdAt") suspend fun getAll(): List<PlayerEntity>
    @Insert suspend fun insert(player: PlayerEntity): Long
    @Update suspend fun update(player: PlayerEntity)
    @Delete suspend fun delete(player: PlayerEntity)
}

@Dao interface TeamDao {
    @Transaction @Query("SELECT * FROM teams") fun observeAllWithMembers(): Flow<List<TeamWithMembers>>
    @Transaction @Query("SELECT * FROM teams ORDER BY createdAt") suspend fun getAllWithMembers(): List<TeamWithMembers>
    @Insert suspend fun insert(team: TeamEntity): Long
    @Update suspend fun update(team: TeamEntity)
    @Delete suspend fun delete(team: TeamEntity)
    @Query("DELETE FROM team_members WHERE teamId = :teamId") suspend fun clearMembers(teamId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMembers(refs: List<TeamMemberCrossRef>)
    @Transaction suspend fun setMembers(teamId: Long, playerIds: List<Long>) { clearMembers(teamId); insertMembers(playerIds.map { TeamMemberCrossRef(teamId, it) }) }
}

@Dao interface GameNameDao {
    @Query("SELECT * FROM game_names ORDER BY name") fun observeAll(): Flow<List<GameNameEntity>>
    @Query("SELECT * FROM game_names ORDER BY name") suspend fun getAll(): List<GameNameEntity>
    @Insert suspend fun insert(name: GameNameEntity): Long
    @Update suspend fun update(name: GameNameEntity)
    @Delete suspend fun delete(name: GameNameEntity)
}

@Dao interface GameDao {
    @Transaction @Query("SELECT * FROM games ORDER BY createdAt DESC") fun observeAllWithDetails(): Flow<List<GameWithDetails>>
    @Transaction @Query("SELECT * FROM games WHERE id = :id") fun observeGame(id: Long): Flow<GameWithDetails?>
    @Transaction @Query("SELECT * FROM games ORDER BY createdAt") suspend fun getAllWithDetails(): List<GameWithDetails>
    @Insert suspend fun insertGame(game: GameEntity): Long
    @Update suspend fun updateGame(game: GameEntity)
    @Delete suspend fun deleteGame(game: GameEntity)
    @Insert suspend fun insertParticipant(participant: ParticipantEntity): Long
    @Insert suspend fun insertScoreEntry(entry: ScoreEntryEntity): Long
    @Delete suspend fun deleteScoreEntry(entry: ScoreEntryEntity)
    @Insert suspend fun insertSeat(seat: SeatEntity): Long
    @Query("DELETE FROM seats WHERE gameId = :gameId") suspend fun deleteSeatsForGame(gameId: Long)
}

// Children before parents, mirroring BackupService.eraseAll on iOS.
@Dao interface WipeDao {
    // @Query("DELETE FROM ...") for: score_entries, seats, participants, games,
    // team_members, teams, players, game_names — plus:
    @Transaction suspend fun wipeAll() { /* call them in that order */ }
}
```

### `data/db/ScoreCardDatabase.kt`

```kotlin
@Database(entities = [PlayerEntity::class, TeamEntity::class, TeamMemberCrossRef::class,
    GameNameEntity::class, GameEntity::class, ParticipantEntity::class,
    ScoreEntryEntity::class, SeatEntity::class], version = 1, exportSchema = false)
@TypeConverters(InstantConverters::class)
abstract class ScoreCardDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun teamDao(): TeamDao
    abstract fun gameNameDao(): GameNameDao
    abstract fun gameDao(): GameDao
    abstract fun wipeDao(): WipeDao
    companion object {
        fun build(context: Context): ScoreCardDatabase =
            Room.databaseBuilder(context, ScoreCardDatabase::class.java, "scorecard.db").build()
    }
}
```

### `data/Prefs.kt` (agent: data-layer)

DataStore preferences wrapper. Key strings and enum raw values are IDENTICAL to
the iOS `@AppStorage` keys (`CompetitorSort.swift`, `DealingDirection.swift`,
`NewGameView.swift`): `"dealingDirection"`, `"playersSortOrder"`,
`"teamsSortOrder"`, `"hasSeededGameNames"`.

```kotlin
class Prefs(private val context: Context) {
    val dealingDirection: Flow<DealingDirection>           // default CounterClockwise
    suspend fun setDealingDirection(value: DealingDirection)
    val playersSortOrder: Flow<CompetitorSortOrder>        // default NameAscending
    suspend fun setPlayersSortOrder(value: CompetitorSortOrder)
    val teamsSortOrder: Flow<CompetitorSortOrder>          // default NameAscending
    suspend fun setTeamsSortOrder(value: CompetitorSortOrder)
    val hasSeededGameNames: Flow<Boolean>                  // default false
    suspend fun setHasSeededGameNames(value: Boolean)
}
```

## Domain — `domain/` (agent: domain)

iOS reference: `Models/Tally.swift`, `FrequentPicker.swift`, `GameNamePicker.swift`,
`CompetitorSort.swift`, `DealingDirection.swift`, `Game.swift`, `GameParticipant.swift`, `Team.swift`.
All pure Kotlin (no Android imports) so it unit-tests on the JVM.

### `domain/DealingDirection.kt`

```kotlin
enum class DealingDirection(val rawValue: String, val step: Int, val label: String, val adverb: String) {
    CounterClockwise("counterClockwise", 1, "Counter-clockwise", "counter-clockwise"),
    Clockwise("clockwise", -1, "Clockwise", "clockwise");
    companion object { fun fromRaw(raw: String?): DealingDirection /* default CounterClockwise */ }
}
```

### `domain/Tally.kt`

```kotlin
data class Tally(val played: Int = 0, val won: Int = 0, val drawn: Int = 0, val inProgress: Int = 0) {
    val winPercentage: Int?   // null when played == 0, else (won/played*100) rounded half-up
    val isEmpty: Boolean      // played == 0 && inProgress == 0
}
```

### `domain/Pickers.kt`

```kotlin
object FrequentPicker {
    const val DEFAULT_LIMIT = 5
    fun <T> top(items: List<T>, limit: Int = DEFAULT_LIMIT, usage: (T) -> Int, name: (T) -> String): List<T>
    // usage > 0 only; usage desc; ties by NameComparator; take(limit)
}
object GameNamePicker {
    fun <T> defaultSelection(items: List<T>, lastUsed: (T) -> Instant, name: (T) -> String): T?
    // most recent lastUsed first; ties (incl. DISTANT_PAST) by NameComparator; null for empty
}
```

### `domain/CompetitorSort.kt`

```kotlin
// Numeric-aware, case-insensitive comparator approximating iOS
// localizedStandardCompare: digit runs compare as numbers ("Player 2" < "Player 10").
object NameComparator : Comparator<String>

enum class CompetitorSortOrder(val rawValue: String, val label: String) {
    NameAscending("nameAscending", "Name (A–Z)"),
    NameDescending("nameDescending", "Name (Z–A)"),
    ScoreDescending("scoreDescending", "Wins (high to low)"),
    ScoreAscending("scoreAscending", "Wins (low to high)");
    val isAscendingArrow: Boolean  // NameAscending/ScoreAscending -> true (up arrow in the menu)
    companion object { fun fromRaw(raw: String?): CompetitorSortOrder /* default NameAscending */ }
}

object CompetitorSorter {
    fun <T> sorted(items: List<T>, order: CompetitorSortOrder, name: (T) -> String, tally: (T) -> Tally): List<T>
    // Port iOS CompetitorSorter exactly: score = wins, tie-break win% (null -> -1),
    // final tie-break name A–Z REGARDLESS of direction. Compute tally once per item.
}
```

### `domain/GameLogic.kt`

Extensions over the Room relation types (import from `data.db`). Port the iOS
computed properties exactly (`Game.swift`, `GameParticipant.swift`, `Team.swift`):

```kotlin
val ParticipantWithDetails.totalScore: Int                 // entries.sumOf { it.points }
val ParticipantWithDetails.displayName: String             // player?.name ?: team?.team?.name ?: participant.nameSnapshot
val ParticipantWithDetails.isTeamCompetitor: Boolean
val ParticipantWithDetails.subtitle: String                // team roster summary, else "Player"
val ParticipantWithDetails.sortedEntries: List<ScoreEntryEntity>  // newest first
val TeamWithMembers.sortedMembers: List<PlayerEntity>      // by NameComparator
val TeamWithMembers.rosterSummary: String                  // "Alice, Bob & Carol" / "No members"
val GameWithDetails.isOpen: Boolean
val GameWithDetails.rankedScores: List<Pair<ParticipantWithDetails, Int>>  // score desc, then sortIndex
val GameWithDetails.rankedParticipants: List<ParticipantWithDetails>
val GameWithDetails.leader: ParticipantWithDetails?
val GameWithDetails.topScorers: List<ParticipantWithDetails>
val GameWithDetails.isDraw: Boolean                        // closed && topScorers.size > 1
val GameWithDetails.orderedSeats: List<SeatWithPlayer>     // by position
val GameWithDetails.hasSeating: Boolean
val GameWithDetails.currentDealer: PlayerEntity?
fun GameWithDetails.nextDealer(direction: DealingDirection): PlayerEntity?
fun GameWithDetails.advancedDealerIndex(direction: DealingDirection): Int
    // ((currentDealerIndex + direction.step) % count + count) % count; callers persist it
fun GameWithDetails.participantsInDealingOrder(direction: DealingDirection): List<ParticipantWithDetails>
    // Port Game.participantsInDealingOrder: rank = ((position * step) % count + count) % count
    // per seated player; team rank = min over members; unranked = Int.MAX_VALUE; tie by sortIndex.
fun GameWithDetails.isSoleWinner(p: ParticipantWithDetails): Boolean   // closed, single top scorer, p.participant.id match
fun GameWithDetails.isDrawFor(p: ParticipantWithDetails): Boolean      // game.isDraw && p among topScorers
fun playerTally(playerId: Long, games: List<GameWithDetails>): Tally
fun teamTally(teamId: Long, games: List<GameWithDetails>): Tally
    // open -> inProgress; closed -> played, +won if isSoleWinner, +drawn if isDrawFor
fun playerUsageCount(playerId: Long, games: List<GameWithDetails>): Int  // participations count
fun teamUsageCount(teamId: Long, games: List<GameWithDetails>): Int
```

## Backup — `data/backup/` (agent: backup)

iOS reference: `Models/BackupSnapshot.swift`, `Services/BackupService.swift`, `Services/BackupStorage.swift`.

**Cross-platform contract.** The JSON must round-trip with the iOS app:
- Dates: ISO-8601 UTC, **seconds precision, no fractional seconds**, `Z` suffix (Swift's `.iso8601` strategy REJECTS fractional seconds on decode). Truncate on write; on read accept `Instant.parse`, falling back to `OffsetDateTime.parse(...).toInstant()`.
- Missing keys decode as null/defaults; nulls are OMITTED on encode (Swift synthesized Codable uses encodeIfPresent). kotlinx: `Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; prettyPrint = true }`. `encodeDefaults = true` is REQUIRED so `version` is always written.
- Field names exactly as in `BackupSnapshot.swift` (camelCase, default kotlinx names — no @SerialName needed if properties match).

### `data/backup/BackupSnapshot.kt`

```kotlin
typealias IsoInstant = @Serializable(with = IsoInstantSerializer::class) Instant
object IsoInstantSerializer : KSerializer<Instant>

@Serializable data class BackupSnapshot(
    val version: Int = CURRENT_VERSION,
    val exportedAt: IsoInstant,
    val players: List<PlayerDTO> = emptyList(),
    val teams: List<TeamDTO> = emptyList(),
    val games: List<GameDTO> = emptyList(),
    val gameNames: List<GameNameDTO>? = null,
) { companion object { const val CURRENT_VERSION = 1 } }

@Serializable data class PlayerDTO(val name: String, val createdAt: IsoInstant)
@Serializable data class GameNameDTO(val name: String, val createdAt: IsoInstant, val lastUsedAt: IsoInstant)
@Serializable data class TeamDTO(val name: String, val createdAt: IsoInstant, val memberIndices: List<Int>)
@Serializable data class GameDTO(
    val title: String, val hasTarget: Boolean, val targetPoints: Int? = null,
    val createdAt: IsoInstant, val closedAt: IsoInstant? = null,
    val latitude: Double? = null, val longitude: Double? = null, val locationName: String? = null,
    val participants: List<ParticipantDTO> = emptyList(),
    val seats: List<SeatDTO>? = null, val currentDealerIndex: Int? = null, val currentHand: Int? = null,
)
@Serializable data class SeatDTO(val position: Int, val playerIndex: Int? = null)
@Serializable data class ParticipantDTO(
    val nameSnapshot: String, val sortIndex: Int,
    val playerIndex: Int? = null, val teamIndex: Int? = null,
    val entries: List<EntryDTO> = emptyList(),
)
@Serializable data class EntryDTO(val points: Int, val timestamp: IsoInstant)

class BackupException(message: String) : Exception(message)

object BackupCodec {
    val json: Json
    fun encode(snapshot: BackupSnapshot): String
    fun decode(text: String): BackupSnapshot
    // malformed -> BackupException("That file isn't a valid ScoreCard backup.")
    // version > CURRENT_VERSION -> BackupException("This backup was made by a newer version of ScoreCard (format N) and can't be restored.")
}
```

### `data/backup/BackupService.kt`

```kotlin
class BackupService(private val database: ScoreCardDatabase) {
    suspend fun makeSnapshot(): BackupSnapshot   // players/teams/games ordered by createdAt, gameNames by name; indices into those lists; participants by sortIndex; entries by timestamp; nameSnapshot = live displayName
    suspend fun exportJson(): String
    suspend fun restore(text: String): BackupSnapshot  // decode -> wipeAll -> insert (gameNames, players, teams+members, games+participants+entries+seats), mapping indices; out-of-range indices -> null link with snapshot fallback; whole thing in withTransaction
    suspend fun eraseAll()                       // wipeDao().wipeAll()
}
```

### `data/backup/BackupStorage.kt`

```kotlin
data class BackupFile(val file: File, val name: String, val date: Instant, val sizeBytes: Long)

class BackupStorage(private val context: Context) {
    // getExternalFilesDir(null) ?: filesDir, child "Backups", created on demand
    val backupsDir: File
    fun makeFilename(date: Instant = Instant.now()): String
    // "ScoreCard-Backup-yyyy-MM-dd-HHmmss.json" — same prefix/extension as iOS so files interchange
    suspend fun write(json: String, filename: String = makeFilename()): File      // Dispatchers.IO
    suspend fun listBackups(): List<BackupFile>   // *.json with the prefix, newest first
    suspend fun delete(file: File)
    suspend fun read(uri: Uri): String            // contentResolver.openInputStream
    suspend fun read(file: File): String
    fun shareUri(file: File): Uri                 // FileProvider.getUriForFile, authority "${context.packageName}.fileprovider"
}
```

## Location — `location/LocationCapture.kt` (agent: shared-ui)

iOS reference: `Services/LocationManager.swift`. Best-effort, never throws.

```kotlin
data class CapturedLocation(val latitude: Double, val longitude: Double, val placeName: String?)

class LocationCapture(private val context: Context) {
    fun hasPermission(): Boolean   // COARSE or FINE granted
    suspend fun capture(): CapturedLocation?
    // null when no permission or no fix. API 30+: LocationManager.getCurrentLocation
    // (prefer FUSED_PROVIDER if present, else GPS/NETWORK); below 30: best lastKnownLocation.
    // Then best-effort reverse geocode (keep the fix when geocoding fails).
    companion object {
        suspend fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String?
        // Geocoder; API 33+ listener variant, else deprecated sync on Dispatchers.IO.
        // Compose "street number, locality, country" like LocationManager.describe (dedupe parts, join ", ").
    }
}
```

## Theme & components — `ui/theme/`, `ui/components/` (agent: theme-components)

iOS reference: `Views/Theme.swift`, `Views/TallyBadge.swift`, `Views/GameFormatting.swift`.

### `ui/theme/Theme.kt`

```kotlin
object ThemeColors {
    // Light/dark hex pairs from Theme.swift; each accessor is @Composable and
    // switches on isSystemInDarkTheme().
    val coral: Color @Composable get   // 0xFFFF6B6B / 0xFFFF8A8A
    val teal: Color @Composable get    // 0xFF12B3A6 / 0xFF32D6C6
    val amber: Color @Composable get   // 0xFFF5A314 / 0xFFFBBF24
    val plum: Color @Composable get    // 0xFF7C5CFF / 0xFF9F8BFF
    val sky: Color @Composable get     // 0xFF3B82F6 / 0xFF60A5FA
    val accent: Color @Composable get  // = coral
    val cardSurface: Color @Composable get   // 0xFFFFFFFF / 0xFF2A2632
    val backgroundTop: Color @Composable get     // 0xFFF7FAFC / 0xFF14171C
    val backgroundBottom: Color @Composable get  // 0xFFE9F0F5 / 0xFF171D24
    @Composable fun accentFor(name: String): Color
    // hash = name.codePoints fold: h = 5381; h = h*33 + cp (wrapping Int math);
    // palette [coral, teal, amber, plum, sky]; abs(hash) % 5 — must match iOS Theme.accent(for:)
}

@Composable fun ScoreCardTheme(content: @Composable () -> Unit)
// MaterialTheme: primary = coral, secondary = teal, tertiary = amber,
// background = backgroundTop, surface = cardSurface; light/dark via isSystemInDarkTheme.
```

### `ui/components/Components.kt`

```kotlin
@Composable fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit)
// Box, fillMaxSize, Brush.linearGradient(backgroundTop -> backgroundBottom, top-left to bottom-right)

@Composable fun CardTile(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)
// Surface (clickable when onClick != null), RoundedCornerShape(22.dp), color cardSurface,
// shadowElevation 6.dp, inner padding 16.dp

@Composable fun Avatar(name: String, icon: ImageVector? = null, size: Dp = 44.dp)
// circle filled accentFor(name); white icon, or 1–2 uppercase initials (first letters of first two words, "?" fallback)

@Composable fun TallyBadge(tally: Tally)
// "No games yet" when empty; else: trophy+won (amber), equals+drawn when >0 (plum),
// flag+played (secondary), "NN%" when winPercentage != null, live-dot+inProgress when >0 (teal)

@Composable fun PlayfulSectionHeader(title: String, icon: ImageVector? = null)
// small bold accent-colored label row, like the iOS PlayfulSectionHeader

@Composable fun EmptyState(icon: ImageVector, title: String, description: String, actionLabel: String? = null, onAction: (() -> Unit)? = null)

@Composable fun SwipeToDeleteBox(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit)
// M3 SwipeToDismissBox, EndToStart only, red background with Delete icon, confirms by calling onDelete

@Composable fun StatusAlertDialog(title: String, body: String, onDismiss: () -> Unit)   // one OK button
@Composable fun WorkingOverlay(visible: Boolean)   // centered CircularProgressIndicator overlay when visible
```

### `ui/components/Formatting.kt`

```kotlin
object GameFormatting {
    fun dateTime(instant: Instant): String   // localized medium date + short time, system zone
    fun duration(from: Instant, to: Instant): String  // "1h 12m" / "12m" / "less than a minute"
    fun signedPoints(value: Int): String     // "+3" / "-2" / "0"
}
```

## Edit dialogs — `ui/components/EditDialogs.kt` (agent: shared-ui)

iOS reference: `Views/Players/PlayerEditView.swift`, `Views/Teams/TeamEditView.swift`, `Views/Games/GameNameEditView.swift`.

```kotlin
@Composable fun PlayerEditDialog(container: AppContainer, existing: PlayerEntity?, onDismiss: () -> Unit, onCreated: (PlayerEntity) -> Unit = {})
@Composable fun TeamEditDialog(container: AppContainer, existing: TeamWithMembers?, onDismiss: () -> Unit, onCreated: (TeamWithMembers) -> Unit = {})
@Composable fun GameNameEditDialog(container: AppContainer, existing: GameNameEntity?, onDismiss: () -> Unit, onCreated: (GameNameEntity) -> Unit = {})
```

Behavior (all three): trimmed name; Save disabled when blank or when another
row's name matches case-insensitively (show the iOS error line in red, e.g.
"A player named “X” already exists."); editing updates in place (onCreated NOT
called), creating inserts then calls onCreated with the inserted entity
(re-read with the generated id). Team dialog: member checklist of all players
(observed flow), needs ≥1 member, footer "A team needs at least one member.",
inline "New Player" button opening a nested PlayerEditDialog that auto-selects
the new player. Creating a player/team/name stamps createdAt = Instant.now();
new GameName gets lastUsedAt = DISTANT_PAST.

## Seating — `ui/games/SeatingArrangement.kt` (agent: shared-ui)

iOS reference: `Views/Games/SeatingArrangementView.swift`.

```kotlin
@Composable fun SeatingArrangement(people: List<PlayerEntity>, direction: DealingDirection, confirmTitle: String, isSaving: Boolean = false, onConfirm: (List<PlayerEntity>) -> Unit)
```

First dealer chosen at random on first composition; "shuffle" icon re-randomizes;
tapping the dealer card opens a picker dialog listing everyone — choosing a new
dealer swaps it with the old one's slot (port `selectDealer`). Remaining people
listed in order with position numbers starting at 2 and **up/down arrow buttons**
to reorder (Compose has no built-in drag-reorder; arrows are the deliberate
platform adaptation). Footer text mirrors the iOS one (deal passes
`direction.adverb`). Confirm button (text `confirmTitle`) disabled while saving
or when there's no dealer; calls `onConfirm(listOf(dealer) + others)`.

## App wiring (agent: app-wiring)

### `ScoreCardApplication.kt`

```kotlin
class AppContainer(context: Context) {
    val database: ScoreCardDatabase   // ScoreCardDatabase.build(context)
    val prefs: Prefs
    val backupService: BackupService
    val backupStorage: BackupStorage
    val locationCapture: LocationCapture
}
class ScoreCardApplication : Application() { val container: AppContainer by lazy { AppContainer(this) } }
```

### `MainActivity.kt`

ComponentActivity; enableEdgeToEdge(); setContent { ScoreCardTheme { ScoreCardApp(container) } } where
`container = (application as ScoreCardApplication).container`.

### `ui/AppNav.kt`

```kotlin
@Composable fun ScoreCardApp(container: AppContainer)
```

Scaffold + NavHost (start `"games"`). Bottom `NavigationBar` shown ONLY on the
four top-level routes, items: Games (Icons.Filled.Style), Players
(Icons.Filled.Person), Teams (Icons.Filled.Groups), Settings
(Icons.Filled.Settings); selecting an item navigates with
`popUpTo(start) { saveState = true }; launchSingleTop = true; restoreState = true`.

Routes:
- `"games"` → `GamesScreen(container, onOpenGame = { nav("scoreboard/$it") }, onOpenDetail = { nav("detail/$it") }, onNewGame = { nav("newGame") })`
- `"players"` → `PlayersScreen(container)`
- `"teams"` → `TeamsScreen(container)`
- `"settings"` → `SettingsScreen(container, onOpenBackups = { nav("backups") })`
- `"newGame"` → `NewGameScreen(container, onStarted = { id -> navigate("scoreboard/$id") popping "newGame" off the stack }, onCancel = { popBackStack() })`
- `"scoreboard/{gameId}"` (Long arg) → `ScoreboardScreen(container, gameId, onBack = { popBackStack() })`
- `"detail/{gameId}"` (Long arg) → `GameDetailScreen(container, gameId, onBack = { popBackStack() })`
- `"backups"` → `BackupListScreen(container, onBack = { popBackStack() })`

## Screens

Every screen composable signature is FIXED (see routes above). All screens use
`AppBackground` behind their content, `CardTile` rows, and their own `Scaffold`
top bar (`TopAppBar` with back arrow where applicable). Status/error results
show via `StatusAlertDialog`.

### `ui/games/GamesScreen.kt` (agent: games-screen) — iOS `GamesView.swift`

`GamesScreen(container: AppContainer, onOpenGame: (Long) -> Unit, onOpenDetail: (Long) -> Unit, onNewGame: () -> Unit)`

Observe `gameDao().observeAllWithDetails()`. Empty → EmptyState("No Games Yet",
"Start a game to begin keeping score.", action "New Game"). Else LazyColumn:
"In Progress" section (open games) then "History" (closed), each row in
SwipeToDeleteBox (delete via `deleteGame`). Row: Avatar(title, icon Style when
open / Verified when closed), title, status chip (Live teal / Draw plum /
trophy+winner-name amber), score summary "Name N · Name M" in ranked order,
caption line with dateTime, locationName if any, "to N" if target. Tap → open
scoreboard when open, detail when closed. Top bar action "+" → onNewGame.

### `ui/games/NewGameScreen.kt` (agent: newgame) — iOS `NewGameView.swift`, `GameDraft.swift`, `GameName.swift`

`NewGameScreen(container: AppContainer, onStarted: (Long) -> Unit, onCancel: () -> Unit)`

Two internal steps (local state), mirroring the iOS push:

Step 1 (form): game-name single-select list (check mark on selected, swipe-delete
rows, "New Game Name" button → GameNameEditDialog auto-selecting on create);
"Play to a target score" switch + target stepper (− / + buttons, 1..1000,
default 11) with the iOS footer copy; competitor selection — players and teams
lists with checkmarks, "Most Used Players/Teams" sections above the rest when
`FrequentPicker.top` (usage = participation count from games) is non-empty AND
there are leftovers; selecting a team clears selected players (team game — the
players sections hide entirely once any team is selected); inline "New Player"/
"New Team" via dialogs, auto-selected on create; "Playing" numbered summary of
the selection order; location status footnote (granted / denied / not asked —
request the permission via `rememberLauncherForActivityResult` when the screen
opens, like iOS `requestAuthorizationIfNeeded`). "Next" enabled when a name is
selected and ≥2 competitors.

One-time seeding before showing the list (port `GameName.seedFromExistingGames`
+ `hasSeededGameNames` pref): if pref false → if no GameNames exist and games
do, create one per distinct trimmed title (case-insensitive key, spelling and
lastUsedAt from the most recently created game using it) → set pref true. Then
pre-select `GameNamePicker.defaultSelection` if nothing selected yet.

Step 2: `SeatingArrangement(people = distinct individuals in competitor order
(teams expanded via sortedMembers), direction = prefs.dealingDirection,
confirmTitle = "Start Game")`. On confirm: capture location best-effort
(`container.locationCapture.capture()`), set selected GameName.lastUsedAt = now,
insert GameEntity (title from name, target, createdAt now, location fields),
participants in selection order (sortIndex = index, nameSnapshot = current
name), seats (position = order, dealer index 0, hand 1) — then `onStarted(gameId)`.

### `ui/games/ScoreboardScreen.kt` + `ui/games/GameComponents.kt` (agent: scoreboard) — iOS `GameScoreboardView.swift`, `GameComponents.swift`

`ScoreboardScreen(container: AppContainer, gameId: Long, onBack: () -> Unit)`

Observe `gameDao().observeGame(gameId)`. Derive per recomposition (single pass):
rows = `participantsInDealingOrder(direction)` with each total + entry count;
`reachedTarget` = any total ≥ target (when hasTarget); `scoredThisHand` = total
entry count > handBaseline; `scoringDisabled = scoredThisHand || reachedTarget`.

State: `handBaseline` rememberSaveable Int initialized to the game's total entry
count when the game FIRST loads (init to -1, set once on first non-null game);
`declinedTargetEnd` rememberSaveable Boolean.

Layout (LazyColumn of card tiles): target-reached banner (amber→coral gradient,
flag icon, winner names + iOS copy) when reachedTarget; "Current Hand" section —
dealer card with Avatar, "Dealer this hand" + name, HAND counter, **Next Hand**
filled button (enabled scoredThisHand && !reachedTarget) advancing dealer
(`advancedDealerIndex`) + hand + re-arming baseline, **Hand Was a Draw** outlined
button (enabled !scoredThisHand && !reachedTarget) doing the same advance,
"Next to deal: X" caption and the iOS helper copy; when `!hasSeating` show "Set
Up Seating & Dealer" button instead → full-screen dialog hosting
`SeatingArrangement(confirmTitle = "Save")` that replaces seats
(deleteSeatsForGame + inserts, dealer index 0, hand 1). "Scores" section — one
row per competitor: position badge (table order), Avatar, name + subtitle, big
total (amber when ≥ target), quick "+1 +2 +3 +5" buttons (disabled when
scoringDisabled) inserting ScoreEntry(now), and an always-enabled "⋯" opening
`ParticipantScoringSheet`. "Details" section — `GameInfoSection(game)`.

Target prompt: when reachedTarget flips false→true and !declinedTargetEnd, show
a dialog ("End Game" → close; "Not Yet" → declinedTargetEnd = true). When it
flips back false, reset declinedTargetEnd. Top bar: "End Game" (destructive) →
confirm dialog → set closedAt = now via updateGame, then onBack().

`GameComponents.kt`:
```kotlin
@Composable fun GameInfoSection(game: GameWithDetails)
// chips: In Progress (teal) / Finished; "First to N" or "Open-ended";
// created dateTime; place (locationName, else lazily reverse-geocoded from
// coords via LocationCapture.reverseGeocode in a LaunchedEffect — never show raw coords);
// "Ended <dateTime> · <duration>" when closed.
@Composable fun ParticipantScoringSheet(container: AppContainer, participant: ParticipantWithDetails, onDismiss: () -> Unit)
// ModalBottomSheet: big totalScore, name, subtitle; Quick Add +1+2+3+5+10;
// Custom stepper −100..100 (default 1) with "Add +N points" button (disabled at 0);
// Entries list newest-first ("+3", time hour:minute, negative red) with
// swipe-to-delete (exact undo). Inserts/deletes via gameDao.
```

### `ui/games/GameDetailScreen.kt` (agent: detail) — iOS `GameDetailView.swift`

`GameDetailScreen(container: AppContainer, gameId: Long, onBack: () -> Unit)`

Read-only. "Final Standings" (title gets "· Draw" suffix when isDraw): ranked
rows with icon — equals (plum) for every top scorer in a draw, trophy (amber)
for rank 0 otherwise, "N." rank text for the rest — name, subtitle, bold total.
Location section when locationName/coords exist: place name row (reverse
geocode lazily when only coords; no map on Android — deliberate adaptation).
Details section: `GameInfoSection(game)`.

### `ui/players/PlayersScreen.kt` (agent: rosters) — iOS `PlayersView.swift`

`PlayersScreen(container: AppContainer)`

Observe players + all games (for tallies). Sort via `CompetitorSorter` with the
persisted `prefs.playersSortOrder`; sort menu in the top bar (4 options with
up/down arrow icons, checkmark on active). Empty → EmptyState("No Players",
"Add the people who will be keeping score.", "Add Player"). Rows: Avatar,
name, teams line (member-of, name-sorted), `TallyBadge(playerTally(...))`;
tap → PlayerEditDialog; SwipeToDeleteBox → delete. "+" in top bar → create dialog.

### `ui/teams/TeamsScreen.kt` (agent: rosters) — iOS `TeamsView.swift`

`TeamsScreen(container: AppContainer)` — same shape with teams:
rosterSummary line, `teamTally`, TeamEditDialog, `prefs.teamsSortOrder`,
empty copy from iOS ("Group players into teams to score them together. …").

### `ui/settings/SettingsScreen.kt` (agent: settings) — iOS `SettingsView.swift`

`SettingsScreen(container: AppContainer, onOpenBackups: () -> Unit)`

Sections: **Storage** card — Android has no CloudKit; show an info card "On this
device" explaining data is stored locally and moves between devices/platforms
via backup files (do NOT pretend there's sync). **Gameplay** — dealing-direction
picker (two options, icons RotateLeft/RotateRight) persisted to prefs.
**Your Data** — counts of players/teams/games. **Backup & Restore** — "Back Up
Now" (disabled when store empty) → `backupService.exportJson()` +
`backupStorage.write` → success StatusAlertDialog with counts ("Saved N players,
M teams, and K games to this device."); "Share Latest Backup" (after a backup
this session) → ACTION_SEND intent with `shareUri`, type "application/json";
"Restore from Backup…" → onOpenBackups. **Danger** — "Delete All Data" (disabled
when empty) → confirm dialog with the iOS copy (minus the iCloud sentence) →
`backupService.eraseAll()`. **About** — App "ScoreCard", Version from
`context.packageManager` versionName. Show WorkingOverlay while busy.

### `ui/settings/BackupListScreen.kt` (agent: settings) — iOS `BackupListView.swift`

`BackupListScreen(container: AppContainer, onBack: () -> Unit)`

List `backupStorage.listBackups()` (reload after changes): rows with
dateTime(file.date), "On this device · <size>" (use `android.text.format.Formatter.formatShortFileSize`),
download icon; tap → confirm "Restore this backup? Restoring replaces
everything…" → `backupService.restore(read(file))` → success dialog with
restored counts; SwipeToDeleteBox → delete file. Empty state mirrors iOS copy.
"Import from Files…" → `rememberLauncherForActivityResult(OpenDocument)` with
`arrayOf("application/json")` → same confirm + restore from uri.

## Tests (agent: tests) — `android/app/src/test/java/com/christianmolinari/scorecard/`

JUnit 4, pure JVM. iOS reference: `ScoreCardTests/ScoreCardTests.swift` for spirit.
Construct entities/relations directly (plain data classes).

- `FrequentPickerTest.kt`: zero-usage excluded; usage desc; tie by name (numeric-aware); limit honored.
- `GameNamePickerTest.kt`: most-recent lastUsed wins; all-DISTANT_PAST ties alphabetical; empty list → null.
- `CompetitorSorterTest.kt`: nameAscending numeric-aware ("Player 2" before "Player 10"); descending reversed; score sorts by wins, win%-tie-break, null% below 0%, final A–Z tie-break regardless of direction.
- `GameLogicTest.kt`: rankedScores ordering + sortIndex tie-break; topScorers/isDraw (open game never draw; closed tie = draw for top scorers only); isSoleWinner; dealing order for both directions incl. a team game (team ranked by earliest-dealing member); advancedDealerIndex wraps both ways; playerTally counts won/drawn/inProgress correctly.
- `BackupJsonTest.kt`: encode→decode round-trip preserves everything; dates serialize as "2026-06-12T18:30:00Z" (no fractional seconds) and parse back; null targetPoints/closedAt/locationName keys OMITTED from output; `version` IS present in output; decoding a hand-written iOS-style fixture (with `gameNames` absent and seats present) works; version 2 fixture → BackupException; garbage → BackupException.

## Build commands (from `android/`)

```sh
./gradlew :app:assembleDebug          # compile
./gradlew :app:testDebugUnitTest      # unit tests
```
