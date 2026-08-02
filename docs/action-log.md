# Action log

An on-device record of everything that changes stored data, so an unexpected
result can be traced afterwards instead of reconstructed from backup diffs.

Both apps write the same lines, so two logs can be compared directly. This is a
**shared format, not a data contract**: nothing reads another platform's log,
and `BackupSnapshot` is untouched by it (the backup format version stays at 1).

## Why it exists

In July 2026 two player records disappeared from a device. The iCloud backups
showed *what* changed and narrowed *when* to a five-minute window, but nothing
recorded *what did it*, so the cause was never established. The log closes that
gap for any repeat.

## Format

JSON Lines: one JSON object per line, UTF-8, newline-terminated. Append-only,
cheap to roll, and greppable.

```json
{"action":"playerDeleted","detail":{"teams":"Adriano e Christian"},"entity":"Player","entityId":"41","name":"Adriano","ts":"2026-07-25T17:15:04Z"}
{"action":"scoreAdded","detail":{"points":"1"},"entity":"ScoreEntry","entityId":"812","gameId":"22","name":"Bassano","ts":"2026-08-01T13:33:21Z"}
```

| Field | Meaning |
|---|---|
| `ts` | When it happened. ISO-8601 UTC, **seconds precision, no fractional part** — the same rule the backup format follows, because Swift's `.iso8601` decoder rejects fractional seconds. |
| `action` | Verb for what happened: `playerCreated`, `playerDeleted`, `scoreAdded`, `scoreRemoved`, `gameChanged`, `gameEditRecorded`, `storeWiped`, `userConfirmedPlayerDelete`, `loggingDisabled`, … |
| `entity` | Model type the line concerns: `Player`, `Team`, `Game`, `GameParticipant`, `ScoreEntry`, `Seat`, `GameEdit`, `GameName`, `Store`, `UserAction`. |
| `entityId` | Identifier for correlating lines about the same object. Row id on Android; a hash of the persistent identifier on iOS. Local to the device and never persisted anywhere else. |
| `gameId` | Present only when the action belongs to a game. Absent for players, teams and game names — correlate those by `entityId`/`name`. |
| `name` | Display name at the time of the action, so a line still reads properly once the object is gone. |
| `detail` | Small string map of extras: `points`, `teams`, `reason`, `hand`, `position`. |

Absent fields are omitted rather than written as `null`.

## What is recorded

Everything that changes stored state, and the app events that explain a change:

- players and teams created, renamed, deleted; team membership changed
- games created, changed (which covers **closing a game, advancing the hand and
  moving the dealer**), deleted; participants and seats
- **every scoring-altering action** — each `scoreAdded` and each `scoreRemoved`
  (an undo), plus `gameEditRecorded` carrying the reason when a finished game is
  corrected. All of these carry `gameId`, so one evening reads back as one game.
- backups written, restores, and a full reset (`storeWiped`)
- logging switched on or off

Navigation, scrolling and opening a sheet are **not** recorded: they change
nothing and would bury the rest.

### Intent breadcrumbs

The store-level hooks record that a change happened, not who asked for it. On
iOS in particular, a change merged from CloudKit arrives through the same
context as a local edit. So the destructive paths additionally write a
`userConfirmed…` line before acting. A `userConfirmedPlayerDelete` immediately
followed by `playerDeleted` means the deletion was made *on this device*; a
`playerDeleted` on its own did not originate here.

Full origin attribution would need SwiftData's persistent history, which is
iOS 18+; the app's deployment target is 17.0, so it is deliberately not used.

## Storage and rolling

- iOS: `Application Support/Logs/actions.jsonl`
- Android: `filesDir/logs/actions.jsonl`

App-private on both, and deliberately **not** the iCloud Documents folder used
for backups, so the log neither syncs nor appears in Files.

Being a file rather than a model matters: a reset deletes every stored object,
so a log kept in the database would be destroyed by the very reset it needs to
record.

**Rolling uses two segments.** `actions.jsonl` is live and `actions.1.jsonl` the
previous one, each capped at half the configured maximum, so the pair never
exceeds it. On reaching the half-cap the old previous segment is dropped, the
live one becomes previous, and a new live one starts. Trimming a single file
from the front was rejected: dropping the head of a 100 MiB file means
rewriting it, far too expensive on a write path.

Lowering the maximum in Settings applies immediately rather than at the next
write.

## Settings

Settings → Logging offers: **Record actions** (on by default), **Maximum size**
(10/50/100/250/500 MiB, default 100), **View Log**, **Share Log**, and **Delete
Log**. Delete is available only while recording is off, so a delete can never
race a live write.

Writing is best effort throughout: a logging failure is swallowed rather than
allowed to interrupt the action being recorded.

## Privacy

The log contains player and team names and the scores played. It stays in
app-private storage, is never synced, and leaves the device only if the user
shares it.
