# Dealing rules

Platform-neutral description of how the deal moves around the table between
hands. Both apps (iOS and Android) must behave identically; these are product
decisions, not platform details. The iOS app is the reference implementation.

Both settings are **app-wide preferences** (not stored per game and not part of
the backup file). They are persisted under the same key strings on both
platforms — iOS `@AppStorage` / `UserDefaults`, Android `DataStore` — so the two
ports stay in agreement. Renaming a key or a stored raw value silently resets
everyone's saved choice, so don't.

## Dealing order

Preference key: `dealingDirection`. Raw values: `counterClockwise` (default),
`clockwise`.

Seats are recorded counter-clockwise from the first dealer (position 0). After a
hand, the deal steps to the next seat in this direction (`+1` counter-clockwise,
`-1` clockwise, wrapping around the table).

## After a draw

Preference key: `drawDealingRule`. Raw values:

| Raw value | Meaning |
| --------- | ------- |
| `redeal`  | The last dealer deals the next hand again (the draw does not move the deal). |
| `passOn`  | The deal passes to the next dealer, exactly as after a scored hand. |
| `ask`     | **Default.** Ask the user, each time a hand ends in a draw, who deals next. |

A "draw" here means a single hand in which nobody scored — surfaced on the
scoreboard by the **Hand Was a Draw** control. (This is distinct from a *game*
ending in a tie, which is the `isDraw` result used for tallies.)

When the rule is `ask`, choosing **Hand Was a Draw** opens a prompt offering both
outcomes ("same dealer deals again" or "pass to the next dealer"); dismissing the
prompt cancels and leaves the current hand in place. When the rule is `redeal` or
`passOn`, the control applies that outcome immediately without prompting.

In every case the hand counter advances; only whether the dealer changes depends
on the rule.
