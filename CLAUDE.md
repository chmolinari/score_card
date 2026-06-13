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
- **Domain model semantics** — the entity set (Player, Team, Game, GameParticipant, ScoreEntry, Seat) and rules like "a win is the top score in a closed game, ties count for all leaders", dealer rotation order (counter-clockwise from `position` 0), and the "Next Hand" / target-lock scoreboard behaviors are product decisions, not platform details. The iOS app is the reference implementation; when porting, match its behavior unless a doc in `docs/` says otherwise.
- **Sync** — there is no cross-platform live sync. iOS uses CloudKit (Apple-only); migration between platforms goes through backup files.

## Conventions

- Commit messages: past tense ("added", "fixed", "changed"). Do not add co-author trailers.
