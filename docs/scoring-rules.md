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

A participant's running total is the sum of its score entries. When this
preference is **off** (the default), the total cannot drop below zero:

- A subtraction is **clamped** so the total stops exactly at zero. Subtracting 5
  from a total of 2 records a `-2` entry, leaving the total at 0.
- Once the total is already at (or below) zero, a subtraction is **dropped
  entirely** — it records nothing and never flips into an addition.
- Additions are never affected.

When the preference is **on**, every requested change is recorded verbatim and
totals may go negative.

This only governs what gets written when the user subtracts. Existing entries
(including negative totals already in the store, e.g. from a backup made while the
preference was on) are left untouched; the policy applies to new subtractions
only.
