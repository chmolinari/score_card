# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ScoreCard is an iOS app for keeping the score of card games (Scopa, Briscola, etc.). Built with SwiftUI and SwiftData. Bundle identifier `com.christianmolinari.ScoreCardApp`, iOS 17.0 deployment target, Swift 5.0, universal (iPhone + iPad).

### Domain model (`ScoreCard/Models/`)

All five types are SwiftData `@Model` classes and are CloudKit-compatible: every stored attribute has a default value and every relationship is optional, because SwiftData + CloudKit sync rejects non-optional properties without defaults. `ScoreCardSchema.models` is the single source of truth for the schema list, shared by the live container and previews/tests.

- `Player` — a person; many-to-many with `Team` (inverse declared on `Player.teams`).
- `Team` — a named group of players; `members` is the to-many side.
- `Game` — one match. Has an optional target (`hasTarget` + `targetPoints`), a creation timestamp (`createdAt`), an optional `closedAt` (nil ⇒ still open), and primitive lat/long/`locationName` geotag. `participants` cascades on delete.
- `GameParticipant` — one competitor in one game, linked to **either** a `player` **or** a `team`. Stores a `nameSnapshot` so history survives deletion of the underlying player/team. Score is derived (`totalScore`) from its `scoreEntries`.
- `ScoreEntry` — one scoring event (e.g. "+3"); cascades from its participant. Storing entries individually gives exact undo and a per-game log.
- `Seat` — one place at the table for one **individual** player, ordered counter-clockwise from the first dealer (`position` 0). `Game.seats` (cascade) + `Game.currentDealerIndex` drive `currentDealer`/`nextDealer`/`advanceDealer()`. Dealers are always people, even in team games (team participants expand to their members). At game start the new-game flow goes through `SeatingArrangementView` (random first dealer, drag-reorder the rest); the scoreboard shows the current dealer with a "Next Hand" control, and existing games without seating can set it up there. Seats + dealer index are included in the backup snapshot (optional fields for backward compatibility).

`GameScoreboardView` is the live board for an open game. Two non-obvious behaviors live in its `@State`, derived per-render in a single pass: (1) **"Next Hand" gating** — the control stays disabled until at least one point is scored *beyond* a per-hand baseline (`handBaselineEntryCount`, the total `ScoreEntry` count at the hand's start), then advancing the hand re-arms the baseline so it disables again; (2) **target lock** — when a competitor first reaches `targetPoints` the board prompts to end the game, and if declined it locks scoring (`reachedTarget`) until the over-target score is corrected back down. Rows are ordered by **dealing rotation**, not score. Saves are deferred to hand boundaries rather than per point.

`Player.tally` and `Team.tally` compute a `Tally` (games played / won / in-progress, win %) on the fly from `participations` — nothing is stored. A win is `GameParticipant.isWinner` (top score in a *closed* game; ties count for all leaders). The `TallyBadge` view renders it on the Players and Teams rows.

### App wiring

- `ScoreCardApp.makeModelContainer()` builds a persistent container with `cloudKitDatabase: .automatic` (iCloud sync), falling back to a local-only store if CloudKit is unavailable. The container is held in a stored property — a `ModelContext` does **not** retain its container, so any code (including tests) that uses a context must keep the container alive. Passing the `-uitesting` launch argument switches to a clean in-memory store (used by `ScoreCardUITests`).
- New Game (`NewGameView`) lets the user create players and teams inline (via `PlayerEditView`/`TeamEditView`, which expose an `onCreate` callback) and auto-selects them. The in-flight selection is modeled by `GameDraft` / `GameCompetitor` (`Models/GameDraft.swift`) — an enum wrapping the `Player`/`Team` model object **directly** (not a persistent ID), because a new object's ID changes on first autosave; identity compares by reference (`===`). The draft is carried from New Game through `SeatingArrangementView` before anything is persisted. The selectors surface the most-used players/teams in a "Most Used" section above the full list, ranked by `Player.usageCount`/`Team.usageCount` (participation count) via the pure, unit-tested `FrequentPicker.top(...)`.
- `ContentView` is a `TabView`: Games / Players / Teams / Settings.
- `LocationManager` (`Services/`) is an `@Observable @MainActor` CoreLocation wrapper injected via `.environment`; it captures a one-shot fix + reverse-geocoded name when a game starts, and fails soft if permission is denied.
- `CloudKitStatusProbe` (`Services/`) is the only direct CloudKit usage — it reads iCloud account status for the Settings screen. Actual syncing is automatic via SwiftData.
- Settings also offers manual **backup/restore** and a full **reset**. `BackupSnapshot` is a portable Codable mirror of the store (relationships encoded as array indices, not SwiftData IDs). `BackupService` (@MainActor) does `exportData`/`decodeSnapshot`/`restore`/`eraseAll`; `BackupStorage` does the file I/O — writing to the app's iCloud Drive container (`Documents`) when available, else local, and a coordinated read for imports. The UI backs up to iCloud Drive, and `BackupListView` lists existing backups (iCloud + local, via `BackupStorage.listBackups()`) for one-tap restore plus a `.json` document-picker import; reset sits behind a confirmation. Both restore and reset go through `eraseAll`, which deletes objects **individually** (not via batch `delete(model:)`) so live `@Query` views refresh immediately. Requires the iCloud Documents entitlement + ubiquity container (in `ScoreCard.entitlements`) and `NSUbiquitousContainers` (in `Info.plist`).

The entitlements declare CloudKit (container `iCloud.com.christianmolinari.ScoreCardApp`) and APNs (`aps-environment = development`, `UIBackgroundModes = remote-notification`). `Info.plist` carries `NSLocationWhenInUseUsageDescription`. Note `GENERATE_INFOPLIST_FILE = YES` is on alongside the explicit `INFOPLIST_FILE`, so keys added to `Info.plist` are merged with generated ones.

## Build, run, test

There is no `.xcworkspace` or SwiftPM manifest at the root — use the `.xcodeproj` directly.

```sh
# Build (replace destination with an installed simulator if needed: `xcrun simctl list devices available`)
xcodebuild -project ScoreCard.xcodeproj -scheme ScoreCard \
  -destination 'platform=iOS Simulator,name=iPhone 16' build

# Run all unit + UI tests
xcodebuild -project ScoreCard.xcodeproj -scheme ScoreCard \
  -destination 'platform=iOS Simulator,name=iPhone 16' test

# Run a single test (Swift Testing or XCTest, by identifier)
xcodebuild test -project ScoreCard.xcodeproj -scheme ScoreCard \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:ScoreCardTests/ScoreCardTests/example

# Clean
xcodebuild -project ScoreCard.xcodeproj -scheme ScoreCard clean
```

To launch the app interactively, open `ScoreCard.xcodeproj` in Xcode and Cmd-R; there is no separate runner script.

## Code structure notes

- The Xcode project uses `PBXFileSystemSynchronizedRootGroup` for `ScoreCard/`, `ScoreCardTests/`, and `ScoreCardUITests/`. Files added to those folders are picked up automatically — **do not hand-edit `project.pbxproj`** to register new sources. Only edit it if you need a new target, build phase, or a per-file membership exception (e.g. how `Info.plist` is currently excluded from the app target's compile sources).
- `ScoreCardTests/` uses the Swift Testing framework (`import Testing`, `@Test`, `#expect`). `ScoreCardUITests/` uses XCTest (`XCUIApplication`). Don't mix the two — keep unit tests in Swift Testing and UI/integration tests in XCTest.
- The live SwiftData `ModelContainer` is constructed once in `ScoreCardApp.swift` (persistent + CloudKit). Previews use `SampleData.container` (in-memory, CloudKit disabled, pre-seeded). Tests build their own in-memory container with `cloudKitDatabase: .none` and **must keep it alive for the test's duration** (bind to a local `let`) — a context whose container has deallocated traps inside SwiftData.

## Conventions inherited from `~/.claude/CLAUDE.md`

- Commit messages: past tense ("added", "fixed", "changed"). Do not add co-author trailers.
