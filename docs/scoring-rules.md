# Scoring rules

Platform-neutral description of scoring policies that both apps (iOS and Android)
must share. These are product decisions, not platform details. The iOS app is the
reference implementation.

Like the dealing rules, these are **app-wide preferences** (not stored per game
and not part of the backup file). They are persisted under the same key strings
on both platforms — iOS `@AppStorage` / `UserDefaults`, Android `DataStore` — so
the two ports stay in agreement. Renaming a key silently resets everyone's saved
choice, so don't.

## Allow scores below zero

Preference key: `allowNegativeScores`. Boolean, default `false`.

A competitor's total is the sum of its score entries. The default suits the games
the apps are built around — Scopa, Briscola, Scopone and Tressette are all
positive-only — while the toggle exists for games that genuinely go below zero,
such as Spades (a failed nil), Pinochle (being set), Skat, and Oh Hell.

### When the preference is off (the default)

The total cannot drop below zero, and **every path that writes a score obeys
this** — not just live subtraction:

- **Subtracting on the scoreboard.** A subtraction is **clamped** so the total
  stops exactly at zero. Subtracting 5 from a total of 2 records a `-2` entry,
  leaving the total at 0. Once the total is already at (or below) zero, a
  subtraction is **dropped entirely** — it records nothing and never flips into
  an addition. The subtract controls are disabled at zero, so the limit is
  visible rather than silently applied.
- **Correcting a finished game.** A proposed new total below zero lands on zero
  instead; see `docs/game-editing.md`.
- **Registering a past game.** A transcribed final total is clamped the same way.
  A transcribed result is still a score, so it must not be a back door past the
  user's choice.

Additions are never affected.

Score fields offer no way to type a minus sign while the preference is off: the
number pad has no minus key on iOS, and the input filter rejects one on Android.

### When the preference is on

Every requested change is recorded verbatim and totals may go negative,
everywhere the list above applies.

## Totals that are already negative

Changing the preference never rewrites existing entries. A store — or a backup
file — written while it was on can still hold a negative total after it is turned
off, and those totals are left alone: a finished game's result is a record, and
rewriting one the user never asked to change would be worse than displaying it.

Such a total is inert. Nothing drives it further down, and correcting the game
through the editor brings it up to zero like any other proposed total.

Note for implementers of the game editor: the clamp applies to a total the
**user supplied**, never to one merely read back out of the store. Clamping a
displayed total would propose a change nobody made — see `docs/game-editing.md`.

## Related documents

- `docs/game-editing.md` — correcting a finished game, which applies this policy
  to every proposed total.
- `docs/dealing-rules.md` — dealer rotation, the other app-wide preference pair.
