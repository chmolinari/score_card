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

Pure Kotlin (no Android imports), so it unit-tests on the JVM. This is where the cross-platform semantics live, ported from the iOS models: `Tally` (played/won/drawn/in-progress, win %), `FrequentPicker` and `GameNamePicker` (the New Game ranking/pre-selection), `CompetitorSorter` + `NameComparator` (roster sort, numeric-aware), `DealingDirection`, `NegativeScores` (the below-zero clamp — an app-wide preference, off by default, obeyed by every score-writing path: `effectiveDelta` for a subtraction and `clamped` for a total the user supplies outright, including a transcribed past game; see `docs/scoring-rules.md`), `GameCompetitor` + `CompetitorSelectionRules` (the players-only-or-teams-only selection shared by New Game and Register Past Game), `GameRegistration` (registering a past game — specified in `docs/registering-past-games.md`: an already-closed game with `createdAt == closedAt` backdated to the played-on date, one final-total score entry per competitor, no seats/target/coordinates; a date-only stamp is local midnight AND sets `playedDateOnly`, which the display layer honours in preference to inferring from the stamp), `GameScoreEdit` (editing a finished game: the below-zero clamp on a proposed total, the delta entry that moves a competitor to it, and `plan(...)`, which returns null — writing nothing — when the totals don't line up with the competitors or when no final score actually moved; see `docs/game-editing.md`), and the `GameWithDetails`/`ParticipantWithDetails` extension properties (`rankedScores`, `topScorers`, `isDraw`, `participantsInDealingOrder`, dealer rotation, tallies). A win is the **sole** top score of a *closed* game; a tie is a draw for the top scorers only. Dealing order follows the seating rotation, not score.

### Scoreboard behaviors (`ui/games/ScoreboardScreen.kt`)

The two load-bearing rules, ported from `GameScoreboardView`:
- **Hand-baseline gating** — "Next Hand" stays disabled until some competitor's score differs from `handBaselineScores` (each competitor's total at the hand's start, keyed by participant id, armed from the first non-null game emission), then advancing the hand re-snapshots it. Gating on a *net score change* (not the entry count) means adding then undoing points back to where the hand started leaves the hand a draw — "Next Hand" re-disables and only "Hand Was a Draw" stays available.
- **Target lock** — when any total reaches `targetPoints` the board prompts once to end the game; declining locks scoring until the over-target score is corrected down. The prompt fires only on a genuine false→true transition (tracked via `prevReachedTarget`), so re-entering the board / rotating / returning after process death never re-pops it — mirroring iOS's `.onChange` not firing on the initial value.

### Editing a finished game (`ui/games/GameEditScreen.kt`)

Reached only from `GameDetailScreen` (an open game is corrected by scoring on the board). One screen with two steps: a mandatory reason — `Continue` stays disabled while the trimmed text is empty, so there is no way to reach the scores without one — then one new **final total** per competitor and nothing else. `Save` arms only when a total actually moved, and the write goes through `GameDao.applyScoreEdit`, which puts the delta entries and the edit row in a single transaction. The score fields are parsed **per keystroke**, not on focus loss: `Save` lives in the app bar and tapping it doesn't move focus first, so a parse-on-blur field would commit a stale total; a field is re-rendered from the stored value when it *does* lose focus, so a clamped or half-typed entry stops showing a number that will not be saved. `proposedTotals` is state seeded **verbatim** from the captured totals and written only by a user action — the clamp lives in `GameScoreEdit.typedTotal` and must never be applied to a total merely read back out of the store, or a competitor sitting on a negative total (played while the preference was on) proposes zero, arms `Save` with no input, and gets rewritten on the next tap. Competitors and their original totals are captured with `remember(game.id)` so the rows don't re-sort under the user while typing; the flow state is `rememberSaveable` so a rotation doesn't discard it. `BackHandler` is always enabled and swallows Back while saving — leaving it disabled would let Back pop the screen and cancel the write mid-flight.

### In-app help (`domain/HelpTopic.kt`, `ui/settings/HelpScreen.kt`)

Settings opens "How to Use ScoreCard": `HelpScreen` is a topic index over `helpTopics`, and `HelpTopicScreen` renders one topic, reached by the `help` and `help/{topicId}` routes in `AppNav.kt` (route-based, so the open topic survives process death). `domain/HelpTopic.kt` is pure Kotlin with **zero Android and Compose imports** like the rest of `domain/` — which is why the icon and tint are intents (`HelpIcon`, `HelpTint`) resolved to a Material icon and a `ThemeColors` value in the UI layer, and why `HelpContentTest` can assert the invariants on the JVM.

**The prose is not authored here.** It is transcribed from `docs/help-content.md`, a cross-platform contract with the iOS port (see the root `CLAUDE.md`): both apps show the same nine topics in the same order, and only blocks the document marks **iOS only** or **Android only** may differ. Change the document first, then both transcriptions. The identifier list and its order are pinned by `HelpContentTest` here and by the iOS suite there, so adding or reordering a topic fails a test until both sides agree. `HelpBlock` has exactly four kinds (`Paragraph`/`Steps`/`Bullets`/`Note`) and the vocabulary is deliberately small — prefer rewording a topic over adding a fifth.

### Action log (`data/log/`)

An on-device audit trail of everything that changes stored data, specified in `docs/action-log.md` and written in the same JSON Lines format as the iOS port so two logs can be compared directly — a **shared format, not a data contract**; nothing reads the other platform's log and `BackupSnapshot` is untouched.

Room has no row-level change hook (`InvalidationTracker` reports which *tables* changed, not what happened to which row), so where iOS gets away with one `ModelContext.willSave` observer, Android records at the DAO boundary: `LoggingDaos.kt` wraps each DAO, and **`AppContainer` hands those out as `container.playerDao` etc. — screens must never go back to `container.database.playerDao()`, which would silently bypass logging**. `BackupService` takes the DAOs too (defaulting to the raw ones) so a restore is explained in the log rather than appearing from nowhere. Deleting a player reads its teams *before* delegating, because the membership rows cascade away with it. The log is a rolling pair of files under `filesDir/logs` capped at the size chosen in Settings; `file_paths.xml` exposes that directory so "Share Log" works.

### Backup (`data/backup/`)

`BackupSnapshot.kt` / `BackupService` / `BackupStorage` / `BackupMapping` implement the **cross-platform data contract** (`BackupMapping` holds the conversions that are pure, so they can be unit-tested on the Java Virtual Machine rather than only through a live database) — the JSON must round-trip with the iOS app. Dates are ISO-8601 UTC, **seconds precision, no fractional seconds** (Swift's `.iso8601` decoder rejects fractional seconds). `Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; prettyPrint = true }` — `encodeDefaults` keeps `version` present; `explicitNulls = false` omits nil optionals like Swift's `encodeIfPresent`. Relationships are encoded as array indices, not row ids. Files use the same `ScoreCard-Backup-*.json` name prefix as iOS so they interchange. A game's `edits` array is optional in both directions — backups predating the editing feature decode with it absent, and readers predating it ignore the key — which is why the format version stayed at 1.

## Build, run, test

Run all commands from this directory (`android/`). Needs a JDK 17+ and an Android SDK; `local.properties` (gitignored) points at the SDK via `sdk.dir`.

```sh
# Build the debug APK
./gradlew :app:assembleDebug

# Run the JVM unit tests (domain + backup)
./gradlew :app:testDebugUnitTest

# Run the instrumented tests (the Room migrations) — needs a booted emulator
# or an attached device; `emulator -avd sc35` then wait for sys.boot_completed
./gradlew :app:connectedDebugAndroidTest

# Install on a running emulator/device
./gradlew :app:installDebug

# List connected devices / simulators
adb devices
```

There is a Gradle wrapper (`./gradlew`); a system `gradle` is not required. The first build downloads dependencies and takes a few minutes.

## Code structure notes

- **Manual DI**: anything new that needs the database/prefs/backup/location gets reached through `AppContainer`; don't construct a second `ScoreCardDatabase`.
- **No ViewModels by default**: keep state in the composable, collect DAO flows, mutate via suspend DAO calls. Match the existing screens before introducing a new pattern.
- **Room schema changes**: bump `@Database(version=…)` and supply a migration (or `fallbackToDestructiveMigration` only in dev). `exportSchema = true` and `app/schemas/` is committed (via the `room.schemaLocation` KSP arg) — a hand-written migration that drifts from the entities only fails at runtime on *upgrading* devices, never on a fresh install or in the JVM suite, so the diffable schema is the guard. Check a new migration's SQL against the generated `createSql` for that version, and add a case to `MigrationTest` (see below) — a new version means a new `<n>.json` and a new upgrade path, both of which want covering. `1.json` predates the schema export and was recovered by rebuilding commit `27c28da` with `exportSchema = true`, not hand-written; it is byte-identical to `2.json` minus `game_edits`. Any change to the entity set or the `BackupSnapshot` DTOs is a **cross-platform breaking change** — see the root `CLAUDE.md` and keep the iOS side in sync.
- **Unit tests** (`app/src/test/`) are pure JVM JUnit 4 — construct the Room entity/relation data classes directly; don't pull in Android framework types. The domain and backup layers are designed to be testable this way.
- **Instrumented tests** (`app/src/androidTest/`) exist for the one thing the JVM suite structurally cannot cover: the migrations, which need a real SQLite and an actual upgrade. `MigrationTest` seeds a database at an old version with raw SQL (the entity classes have moved on and cannot populate an old schema), migrates it, and checks both the schema and the surviving rows. The `androidTest` source set has `schemas/` on its `assets.srcDirs` so the committed JSON ships inside the test APK. Note that `runMigrationsAndValidate` alone does **not** catch a stray `DEFAULT` clause — verified by re-introducing the bug — so a new migration wants an explicit assertion on the value existing rows end up with, not just schema validation.
- **Compose UI tests** live alongside them. `HelpUiTest` pins the Settings entry point into the help page and that a topic opens — the entry point, deliberately not the prose, which `HelpContentTest` covers on the JVM. `GameEditingUiTest` pins the closed-game editor's mandatory-reason gate and the "Edited" badge/history, and `ScoreboardUiTest` pins the two scoreboard rules above (hand-baseline gating, including the take-the-points-back case a score *count* would get wrong; and the target lock, including that a board opened already over the target locks without prompting) — the rules a refactor could drop without failing anything else. Two conventions worth keeping: a UI test asserts the invariant by *attempting the bypass* (clicking a disabled `Continue` and checking the scores step still does not exist; tapping a locked quick-add button and checking the total in the store did not move), not merely by asserting a button's enabled state, because a greyed-out control that still works when tapped would pass the weaker check; and score changes are driven through content descriptions rather than text entry or node indices — `Raise <name>'s total` in the editor, `Add <n> points to <name>` / `More scoring options for <name>` on the scoreboard rows, `Add <n> points` / `Subtract <n> points` in the scoring sheet. Those descriptions exist because the visible labels ("+5", an ellipsis) repeat identically down the board, which is a screen-reader ambiguity as much as a test one; `GameFormatting.points` builds the "1 point"/"3 points" fragment they share. `AppContainer` takes an optional `database` so these run against `Room.inMemoryDatabaseBuilder` and never touch the games on the device — production never passes it.
- **Dismissing a `ModalBottomSheet` in a test** goes through the scrim's semantics action (`onNodeWithContentDescription("Close sheet").performSemanticsAction(SemanticsActions.OnClick)`), not `performClick()`. The scrim fills the screen, so a click at the centre of its bounds lands on the sheet sitting on top of it and dismisses nothing.
- **Deliberate platform adaptations** (not bugs to "fix" back toward iOS): seating reorder uses up/down arrows instead of drag; game detail shows the location name but no map; Settings has no sync status.
