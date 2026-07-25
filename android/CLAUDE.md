# CLAUDE.md — Android app

Guidance for the Android app. For the repository-wide layout and the cross-platform contracts (backup format, domain semantics), see the root `CLAUDE.md`. The iOS app (`../ios/`) is the **reference implementation** — when a behavior is unclear, match it.

## Project

ScoreCard is an Android app for keeping the score of card games (Scopa, Briscola, etc.). It is a native port of the iOS app, not shared code. Application id `com.christianmolinari.scorecard`, `minSdk 26` (so `java.time.*` is available natively — the backup format's ISO-8601 dates depend on it), `targetSdk`/`compileSdk 35`, Kotlin 2.1, Jetpack Compose + Material 3.

Stack: **Room** (SQLite, via KSP) for persistence, **kotlinx.serialization** for the backup JSON, **DataStore Preferences** for app-wide settings, **Navigation Compose** for routing. No DI framework — a single `AppContainer` (built in `ScoreCardApplication`) hand-wires the database, prefs, backup, and location services. No ViewModels — screens are composables that take `AppContainer`, collect Room `Flow`s with `collectAsStateWithLifecycle`, and mutate through DAO calls in a `rememberCoroutineScope`.

There is **no CloudKit equivalent** — Android data is on-device only. Cross-device and cross-platform movement goes through backup files (the shared `BackupSnapshot` JSON contract); the Settings screen says exactly that rather than faking a sync status.

### Domain model (`data/db/Entities.kt`)

Room entities mirror the iOS SwiftData models. Relationships use foreign keys with delete rules chosen to match SwiftData's behavior: `games`→`participants`/`seats` and `participants`→`score_entries` **cascade**; a participant's/seat's `playerId`/`teamId` use **SET_NULL** so `nameSnapshot` history survives deleting the underlying player/team; `team_members` cascades both ways. `game_edits` (one correction to a closed game's scores) also cascades from `games`. Read-side `@Relation` graphs live in `Relations.kt` (`GameWithDetails`, `ParticipantWithDetails`, `TeamWithMembers`, `SeatWithPlayer`).

`Instant`s are stored as epoch millis (`InstantConverters`). `DISTANT_PAST` (a never-used game name's `lastUsedAt`) is `0001-01-01T00:00:00Z` — **exactly** what iOS's `JSONEncoder.iso8601` emits for `Date.distantPast` (not the `0000-12-30` form that `Date.description` prints), so never-used names tie correctly across a backup round-trip.

### Domain logic (`domain/`)

Pure Kotlin (no Android imports), so it unit-tests on the JVM. This is where the cross-platform semantics live, ported from the iOS models: `Tally` (played/won/drawn/in-progress, win %), `FrequentPicker` and `GameNamePicker` (the New Game ranking/pre-selection), `CompetitorSorter` + `NameComparator` (roster sort, numeric-aware), `DealingDirection`, `NegativeScores` (the below-zero clamp — an app-wide preference, off by default, that stops a subtraction at zero; see `docs/scoring-rules.md`), `GameCompetitor` + `CompetitorSelectionRules` (the players-only-or-teams-only selection shared by New Game and Register Past Game), `GameRegistration` (registering a past game: an already-closed game with `createdAt == closedAt` backdated to the played-on date, one final-total score entry per competitor, no seats/target/coordinates; a date-only stamp is local midnight, which the display layer renders without a time), `GameScoreEdit` (editing a finished game: the below-zero clamp on a proposed total, the delta entry that moves a competitor to it, and `plan(...)`, which returns null — writing nothing — when the totals don't line up with the competitors or when no final score actually moved; see `docs/game-editing.md`), and the `GameWithDetails`/`ParticipantWithDetails` extension properties (`rankedScores`, `topScorers`, `isDraw`, `participantsInDealingOrder`, dealer rotation, tallies). A win is the **sole** top score of a *closed* game; a tie is a draw for the top scorers only. Dealing order follows the seating rotation, not score.

### Scoreboard behaviors (`ui/games/ScoreboardScreen.kt`)

The two load-bearing rules, ported from `GameScoreboardView`:
- **Hand-baseline gating** — "Next Hand" stays disabled until some competitor's score differs from `handBaselineScores` (each competitor's total at the hand's start, keyed by participant id, armed from the first non-null game emission), then advancing the hand re-snapshots it. Gating on a *net score change* (not the entry count) means adding then undoing points back to where the hand started leaves the hand a draw — "Next Hand" re-disables and only "Hand Was a Draw" stays available.
- **Target lock** — when any total reaches `targetPoints` the board prompts once to end the game; declining locks scoring until the over-target score is corrected down. The prompt fires only on a genuine false→true transition (tracked via `prevReachedTarget`), so re-entering the board / rotating / returning after process death never re-pops it — mirroring iOS's `.onChange` not firing on the initial value.

### Editing a finished game (`ui/games/GameEditScreen.kt`)

Reached only from `GameDetailScreen` (an open game is corrected by scoring on the board). One screen with two steps: a mandatory reason — `Continue` stays disabled while the trimmed text is empty, so there is no way to reach the scores without one — then one new **final total** per competitor and nothing else. `Save` arms only when a total actually moved, and the write goes through `GameDao.applyScoreEdit`, which puts the delta entries and the edit row in a single transaction. The score fields are parsed **per keystroke**, not on focus loss: `Save` lives in the app bar and tapping it doesn't move focus first, so a parse-on-blur field would commit a stale total. Competitors and their original totals are captured with `remember(game.id)` so the rows don't re-sort under the user while typing.

### Backup (`data/backup/`)

`BackupSnapshot.kt` / `BackupService` / `BackupStorage` implement the **cross-platform data contract** — the JSON must round-trip with the iOS app. Dates are ISO-8601 UTC, **seconds precision, no fractional seconds** (Swift's `.iso8601` decoder rejects fractional seconds). `Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; prettyPrint = true }` — `encodeDefaults` keeps `version` present; `explicitNulls = false` omits nil optionals like Swift's `encodeIfPresent`. Relationships are encoded as array indices, not row ids. Files use the same `ScoreCard-Backup-*.json` name prefix as iOS so they interchange. A game's `edits` array is optional in both directions — backups predating the editing feature decode with it absent, and readers predating it ignore the key — which is why the format version stayed at 1.

## Build, run, test

Run all commands from this directory (`android/`). Needs a JDK 17+ and an Android SDK; `local.properties` (gitignored) points at the SDK via `sdk.dir`.

```sh
# Build the debug APK
./gradlew :app:assembleDebug

# Run the JVM unit tests (domain + backup)
./gradlew :app:testDebugUnitTest

# Install on a running emulator/device
./gradlew :app:installDebug

# List connected devices / simulators
adb devices
```

There is a Gradle wrapper (`./gradlew`); a system `gradle` is not required. The first build downloads dependencies and takes a few minutes.

## Code structure notes

- **Manual DI**: anything new that needs the database/prefs/backup/location gets reached through `AppContainer`; don't construct a second `ScoreCardDatabase`.
- **No ViewModels by default**: keep state in the composable, collect DAO flows, mutate via suspend DAO calls. Match the existing screens before introducing a new pattern.
- **Room schema changes**: bump `@Database(version=…)` and supply a migration (or `fallbackToDestructiveMigration` only in dev). Any change to the entity set or the `BackupSnapshot` DTOs is a **cross-platform breaking change** — see the root `CLAUDE.md` and keep the iOS side in sync.
- **Unit tests** (`app/src/test/`) are pure JVM JUnit 4 — construct the Room entity/relation data classes directly; don't pull in Android framework types. The domain and backup layers are designed to be testable this way.
- **Deliberate platform adaptations** (not bugs to "fix" back toward iOS): seating reorder uses up/down arrows instead of drag; game detail shows the location name but no map; Settings has no sync status.
