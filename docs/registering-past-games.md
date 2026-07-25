# Registering a past game

Platform-neutral description of the "Register Past Game" flow, which records a
game played outside the app — on paper, or before the user installed it. These
are product decisions, not platform details. The iOS app is the reference
implementation.

## What gets stored

An **already-closed** game, backdated to the played-on moment:

- `createdAt` and `closedAt` are both the played-on instant. Their equality is
  load-bearing: the game info section hides its "Ended … · duration" line when
  `closedAt` is not strictly after `createdAt`, because for a transcription that
  line would repeat the date and report a meaningless duration.
- One score entry per competitor, carrying that competitor's **final total** and
  stamped with the same played-on instant. The running total is derived by
  summing entries, so a transcription is simply a game whose whole history is one
  entry per competitor.
- No target: a transcription has only final totals, so the game reads as
  open-ended.
- No seats and no dealer: nobody is going to deal another hand. Every screen that
  consumes seating already handles a game that has none.
- No coordinates. The phone's current position says nothing about where a past
  game was played; only a typed location name is stored, trimmed, and omitted
  entirely when blank.

Competitors follow the same players-only-or-teams-only rule as a new game, and
their selection order becomes `sortIndex`, which is the stable tie-break for
equal scores.

## Final totals obey the scoring policy

A transcribed total is a score like any other: it obeys the app-wide below-zero
preference described in `docs/scoring-rules.md`. With the preference off (the
default) a negative final lands on zero, and the score fields offer no way to
type a minus sign.

This was not always true — the flow originally wrote finals verbatim and ignored
the preference, which is how a negative total could reach a store whose owner had
never enabled them. Both ports now pass the preference explicitly into the
builder, with **no default value**, so dropping the argument is a compile error
rather than a silent reversion.

## The played-on stamp

How much the user remembers is optional, and each case maps to a specific stamp:

| User sets | Stored instant |
|---|---|
| Date and time | That local date and time, verbatim |
| Date only | The **start of that local day** |
| Neither | The moment of registration, so the game files under today |

The stamp is never in the future: a date of today combined with a not-yet-reached
time clamps to now. (The date picker already caps at today, so this only catches
the time.)

### The date-only marker is stored, not inferred

Whether the time of day is meaningful is recorded on the game itself, as a
**nullable** flag (`playedDateOnly` on iOS, the `playedDateOnly` column on
Android):

| Value | Meaning | Rendered as |
|---|---|---|
| `true` | Date known, time unknown | Date only |
| `false` | The stamp's time is real | Date and time |
| absent / `null` | Recorded before the flag existed | Inferred from the stamp being start-of-day |

Storing the intent rather than inferring it fixes two things inference cannot:

1. **A change of time zone.** A stamp is an absolute instant, so start-of-day in
   the zone it was created in is some other time of day elsewhere. A user who
   registers a game in Rome and opens it in New York keeps seeing a date-only
   game, instead of the day shifting and a time appearing.
2. **A deliberate midnight.** Someone who genuinely sets the time to 00:00 is now
   distinguishable from someone who set no time, and their game keeps its time.

The fallback matters: games recorded before the flag existed carry no value, and
must keep rendering exactly as they did — by the start-of-day inference. Readers
must therefore treat "absent" as its own case, not as `false`.

A consequence of the inference, which the fallback still carries for old games:
comparing against a literal midnight is wrong, because a date-only stamp is built
with start-of-day, and on a day when the clock springs forward at 00:00 local
midnight does not exist. Compare against the zone's start of day instead.

### Backup

The flag travels in the backup as an optional `playedDateOnly` on each game.
Optional in both directions — absent means "recorded before the field existed",
which is exactly the fallback case — so the format version is deliberately
unchanged and a reader that predates the field ignores it.

## Related documents

- `docs/scoring-rules.md` — the below-zero policy the final totals obey.
- `docs/game-editing.md` — correcting a game after the fact, including one
  registered this way.
