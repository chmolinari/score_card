# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

ScoreCard is an app for keeping the score of card games (Scopa, Briscola, etc.). This is a monorepo holding one native app per platform — the platforms share **specifications and data contracts**, not code.

```
ios/        # iOS app (SwiftUI + SwiftData + CloudKit) — see ios/CLAUDE.md
android/    # Android app (Kotlin + Jetpack Compose + Room) — see android/CLAUDE.md
docs/       # platform-neutral docs: specs, release notes, cross-platform contracts
```

Platform-specific guidance (domain model details, build/test commands, code structure) lives in each platform's own `CLAUDE.md`. Read `ios/CLAUDE.md` before working on the iOS app.

## Cross-platform contracts

Things both apps must agree on. When changing any of these on one platform, treat it as a breaking change for the other:

- **Backup format** — the iOS `BackupSnapshot` JSON (a portable Codable mirror of the store; relationships encoded as array indices, not database identifiers) is the interchange format for user data between platforms. The Android app must read and write the same format so users can migrate via a backup file.
- **Domain model semantics** — the entity set (Player, Team, Game, GameParticipant, ScoreEntry, Seat, GameEdit) and rules like "a win is the *sole* top score in a closed game; an equal top score is a draw for every competitor who tied and a win for nobody" (iOS `GameParticipant.isWinner` requires `topScorers.count == 1`, Android `isSoleWinner` requires `topScorers.size == 1`; draws are tallied separately via `isDraw`/`isDrawFor`), dealer rotation order (counter-clockwise from `position` 0; the deal direction and the after-a-draw rule are app-wide preferences — see `docs/dealing-rules.md`), the below-zero scoring policy (an app-wide preference, off by default, that every score-writing path obeys — including transcribing a past game — see `docs/scoring-rules.md`), editing a finished game (scores only, behind a mandatory reason, recorded as adjustment entries plus a `GameEdit` — see `docs/game-editing.md`), registering a past game (an already-closed backdated game, one final-total entry per competitor, no seats/target/coordinates, and the stored `playedDateOnly` flag that says whether the stamp's time of day is meaningful — see `docs/registering-past-games.md`), and the "Next Hand" / target-lock scoreboard behaviors are product decisions, not platform details. The iOS app is the reference implementation; when porting, match its behavior unless a doc in `docs/` says otherwise.
- **In-app help** — the text of the Settings → "How to Use ScoreCard" page is specified in `docs/help-content.md`, and both apps must show the same nine topics (`gettingStarted`, `startingGame`, `keepingScore`, `handsAndDealer`, `finishingGame`, `correctingResult`, `registeringPastGame`, `playersAndTeams`, `dataAndBackups`) in that order, with the same words. Only blocks the spec marks **iOS only** or **Android only** may differ — everything else matches verbatim. The identifier list and its order are pinned by a unit test on each side (`HelpTopic.all` on iOS, `helpTopics` on Android), so adding or reordering a topic fails a test until both ports agree. The help describes behavior specified in the other `docs/` files, so changing one of those rules means changing the matching topic too.
- **Sync** — there is no cross-platform live sync. iOS uses CloudKit (Apple-only); migration between platforms goes through backup files.

Related but deliberately **not** a contract: the **action log** (`docs/action-log.md`) is an on-device audit trail both apps write in the same JSON Lines format so two logs read alike, but nothing ever parses the other platform's log and it is never part of a backup. Keep the formats in step because it is convenient, not because anything breaks if they drift.

## Conventions

- Commit messages: past tense ("added", "fixed", "changed"). Do not add co-author trailers.
