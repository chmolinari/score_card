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

### The date-only marker, and what it cannot express

"Date known, time unknown" is encoded as *the stamp being the start of its local
day*, and the display layer renders such a stamp as a date with no time. This is
inferred from the value; nothing separate records it. Two consequences follow,
and both ports must behave the same way about them:

1. **Compare against the zone's actual start of day, not a literal midnight.**
   On a day when the clock springs forward at 00:00, local midnight does not
   exist and start-of-day is 01:00. Testing for literal midnight would miss the
   marker and show a time the user explicitly declined to give.
2. **A user who deliberately sets the time to 12:00 AM cannot be distinguished
   from one who set no time**, and their game renders as date-only. This is
   accepted: midnight is a rare thing to record deliberately, and the alternative
   costs a stored field.

Both are consequences of inferring the marker rather than storing it. A game also
carries no record of the zone its stamp was created in, so a user who changes
time zone between registering and viewing sees a date-only game shift — the
marker no longer matches start-of-day in the new zone, and both the rendered day
and a spurious time can change. Fixing this properly means storing the intent
explicitly (a "date only" flag, or the originating zone) as an optional field, in
which case the backup format version can stay unchanged and older readers ignore
it. Until then, treat it as a known limitation rather than a per-platform bug.

## Related documents

- `docs/scoring-rules.md` — the below-zero policy the final totals obey.
- `docs/game-editing.md` — correcting a game after the fact, including one
  registered this way.
