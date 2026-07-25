# Editing a finished game

Platform-neutral description of how the scores of an already-finished game may be
corrected. Both apps (iOS and Android) must behave identically; these are product
decisions, not platform details. The iOS app is the reference implementation.

Unlike the dealing and scoring rules, this is **not** an app-wide preference.
Every edit is stored data, belongs to one game, and travels in the backup file —
see "Backup format" below.

The whole feature exists to serve one idea: a finished game is a record. It may
be corrected, but a correction must be deliberate, must be explained, and must
remain visible afterwards.

## What can be edited

- **Only a finished game.** A game with no closing timestamp is still in play and
  is corrected simply by scoring on the board; it offers no editing entry point.
  The editor is reachable only from the read-only detail screen of a finished
  game.
- **Only the scores.** The editor exposes one final total per competitor and
  nothing else. Title, target, creation and closing timestamps, location,
  competitors, seating and dealer are all out of scope; there is no path to
  change them from here.
- **An edit never reopens the game.** The closing timestamp is left exactly as it
  was, so the game stays in the finished section and never returns to the live
  board.

## Step 1 — the reason, which cannot be skipped

The editor opens on a reason step before any score is shown. The user types free
text explaining why the scores are being changed. The text is trimmed of
surrounding whitespace and newlines; while the trimmed text is empty, the control
that advances to the scores step is disabled. There is no dismiss-the-prompt, no
"skip", and no default reason: without a reason there is no way to reach the
scores at all.

The reason is kept with the game, not consumed and discarded — it is shown in the
game's edit history and it is part of the backup.

Abandoning the editor persists nothing. Nothing is written until the scores step
is saved, so leaving before that — however the platform lets the user leave —
records no edit. In the reference implementation the explicit cancel control sits
on the reason step; from the scores step the user steps back to it first.

## Step 2 — the scores

One row per competitor, laid out in the game's ranking order (highest total
first, ties broken by the competitor's stable ordering index). That ordering is
captured when the editor opens and does not change while the user types —
re-ranking rows under the cursor as a total is edited would be unusable. The same
captured totals are the "before" side of every comparison below.

Each row accepts a new **final total** (typed or stepped), not a delta. Proposed
totals obey the app-wide below-zero preference described in
`docs/scoring-rules.md`: with it off (the default) a proposed total below zero
lands on zero instead, so a competitor cannot be pushed below it; with it on, the
proposed total is taken verbatim.

The clamp applies to a total the **user supplied**, never to one merely read back
out of the store. A game may legitimately hold a negative total (played while the
preference was on); clamping it just because the editor displayed it would
propose a change nobody made — arming the save control with no input and
rewriting a finished score.

## The rule: a changed final score, or it never happened

The proposed totals are compared element-wise against the captured originals, in
the same order. If they are identical the game was **not** edited:

- the save control is disabled, and
- the check is repeated at save time, so even if the control were reachable the
  editor would close having persisted nothing at all — no adjustment, no edit
  record, no "Edited" badge.

An edit exists only when at least one competitor's final total actually moved.

## How a change is recorded

A competitor's total is derived by summing its score entries, so a correction is
**appended**, never applied by rewriting or deleting existing entries. The
per-game log therefore stays auditable: it shows what was scored during play and,
after it, what was corrected.

On save, and only when the totals actually changed:

1. For each competitor whose total changed, one adjustment score entry is
   inserted carrying the difference `after − before`. A competitor whose total is
   unchanged gets nothing — no zero-point entry.
2. One edit record is inserted for the game, carrying the trimmed reason and the
   moment of the edit. Edit records are only ever added, never overwritten, so a
   game repeatedly corrected accumulates one record per correction.
3. The game's closing timestamp is left untouched.

Everything derived from totals is recomputed from the new figures with no further
work: the final standings, who won, whether the game ended in a tie, and the
per-player and per-team tallies. A correction may therefore change the winner of
a finished game, and that is intended.

## Showing that a game was edited

A game counts as edited when it has at least one edit record.

- **Edited badge.** An edited game carries an "Edited" badge wherever the game is
  presented — on its card in the games list and in the game's information header
  on the detail screen. It sits alongside, and does not replace, the result badge
  (leader, draw, or live).
- **Edit history.** The detail screen of an edited game gains an edit-history
  section listing every edit, newest first, each showing its reason and the date
  and time it was made. A game with no edits shows no such section.

## Backup format

Edits are user data and must survive a backup/restore round trip, including
between platforms.

- Each game in the backup gains an **optional** `edits` array. Each element is an
  object with a `reason` string and an `editedAt` timestamp.
- The array is written newest-first on export; restore attaches each element to
  the game it was read from. Order carries no meaning beyond presentation — the
  timestamps are authoritative.
- The array is optional in both directions. Backups written before this feature
  simply lack the key and must restore as a game with no edits; readers that
  predate the feature ignore the unknown key. The backup format version is
  therefore deliberately **not** bumped.
- Adjustment score entries need no special treatment: they are ordinary score
  entries and are already exported and restored as such. The `edits` array is
  what records *why* they exist.

## Related documents

- `docs/scoring-rules.md` — the below-zero policy that also governs proposed
  totals in the editor.
- `docs/dealing-rules.md` — dealer rotation; unaffected by editing.
