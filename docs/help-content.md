# In-app help content

The text of the in-app help page — **How to Use ScoreCard**, reached from
Settings. Both apps (iOS and Android) must show the same topics, in the same
order, with the same words. What the help says is a product decision, not a
platform detail; the iOS app is the reference implementation.

The text is **transcribed** into a data structure on each platform, not shipped
as a shared asset. This repository shares specifications, not code: SwiftUI
renders Markdown natively and Compose does not, so a shared asset would mean
writing a Markdown renderer on Android purely to avoid typing the prose twice.
For text that changes rarely, that is the worse trade. This document is the
single source both transcriptions must agree with.

Where a passage genuinely differs between the platforms it is marked **iOS
only** or **Android only**, the same way the two Settings screens already
diverge on the storage card. Everything unmarked is shared and must match
word for word.

## Content model

A topic is an identifier, a title, an icon intent, a tint, and an ordered list
of blocks. There are exactly four block kinds:

| Kind | Renders as |
| ---- | ---------- |
| `paragraph` | Body text. |
| `steps` | A numbered list — an ordered procedure the reader follows. |
| `bullets` | A bulleted list — unordered, for options or related facts. |
| `note` | A tinted callout card, visually distinct from body text. Reserved for *the rule behind the behaviour* — the thing a user would otherwise file as a bug. |

This vocabulary is deliberately small, and it should not grow casually. Every
kind added has to be designed, styled and kept in step twice; the help page is
prose, and the moment it needs a fifth block kind it is turning into a layout
engine. Prefer rewording a topic over inventing a block.

Conventions for the text itself:

- Second person, present tense, plain words. Write for someone holding a hand
  of cards, not for a developer.
- Refer to a control by its exact on-screen label, in quotation marks.
- Never name a source file, a type, or a framework.
- Roughly two to six blocks per topic, and at most two `note` blocks.

A note on one label: the control that starts a game is a **+** button in the
Games screen's toolbar whose accessibility name is "Add Game". The help says
`tap +` throughout, because that is what the reader sees.

## Topics

Nine topics, in this order. The identifiers and their order are the
cross-platform contract — see "Keeping the two apps in step" below.

### 1. Getting Started — `gettingStarted` (icon intent: rocket)

**Paragraph.**

> ScoreCard keeps the score of your card games — Scopa, Briscola, and anything
> else you play round after round. You set up who is playing, then tap points in
> as the hands go by; the app remembers every game, who won, and how often each
> person plays.

**Bullets.**

- Games — every match you have played or are playing right now, and where you
  start a new one.
- Players — the people who keep score, each with their record of games played
  and won.
- Teams — named groups of players, for games played in pairs or sides.
- Settings — dealing and scoring preferences, plus backing up and restoring your
  data.

**Steps.**

1. Open the Games tab, tap +, and choose "New Game".
2. Under Game, tap "New Game Name" and type the game you are playing (Scopa,
   Briscola, and so on) — the first time there are no names yet.
3. Tap at least two players so they get a checkmark, then tap "Next".
4. On the seating step, check who deals first and tap "Start Game". The
   scoreboard opens straight away.

**Note.**

> You do not have to fill in the Players tab first. Inside New Game the button
> "New Player" creates someone on the spot and adds them to the game
> automatically — the Players and Teams tabs are just where you manage them
> later.

### 2. Starting a Game — `startingGame` (icon intent: play)

**Paragraph.**

> On the Games tab, tap +. The menu offers "New Game" for a match you are about
> to play, and "Register Past Game" for one you already finished on paper. New
> Game takes two steps: the setup form, then the seating.

**Steps.**

1. Pick the game under Game. The name you used most recently is already
   selected, so playing the same game again takes no taps; "New Game Name" adds
   one that is not in the list.
2. Decide how the game ends. Turn on "Play to a target score" and set "Target"
   when the first to reach a number wins, as in Scopa. Leave it off for an
   open-ended game where you just track running totals, as in Briscola.
3. Tap the players — or the teams — who are competing. You need at least two;
   the "Playing" list shows them in the order you added them.
4. Tap "Next", then "Start Game" once the seating looks right.

**Note.**

> A game is between individual players or between teams, never a mix. As soon as
> you pick a team, any players you had already chosen are dropped and the players
> list goes away — pick your competitors one way or the other.

**Bullets.**

- "Most Used Players" and "Most Used Teams" sit above the full lists, so your
  regular group is at the top instead of buried in the alphabet.
- "New Player" and "New Team" create someone without leaving the screen, and
  they are selected for this game as soon as you save them.
- The first dealer is picked at random. Tap the dealer to choose someone else,
  or tap "Shuffle" for another random pick. In team games the dealer is still
  one person, not the team.
- Put everyone else in the order they sit around the table — the deal passes to
  the next seat each hand.

**Paragraph. — iOS only**

> Drag the other players into their seating order. If you have allowed location
> access, the game is tagged with where you played when you tap "Start Game",
> and the game's details show the place on a map afterwards. The form tells you
> which of the two applies before you start.

**Paragraph. — Android only**

> Use the up and down arrows to move the other players into their seating order.
> If you have allowed location access, the game is tagged with where you played
> when you tap "Start Game", and the game's details show the place name
> afterwards — there is no map. The form tells you which of the two applies
> before you start.

### 3. Keeping Score — `keepingScore` (icon intent: cards)

**Paragraph.**

> Every competitor gets a row on the scoreboard, with their running total in big
> type on the right. For most hands you never need more than the four buttons on
> the row: tap "+1", "+2", "+3" or "+5" and the points land straight away.

**Paragraph.**

> For anything else, tap "Score options" — the "…" button at the end of the row.
> It opens a sheet just for that competitor, showing their total and four things
> you can do with it.

**Bullets.**

- "Quick Add" — +1, +2, +3, +5 and +10.
- "Quick Subtract" — the same amounts, taken off instead: −1, −2, −3, −5 and −10.
- "Custom" — dial any amount between −100 and +100, then tap the button to apply
  it.
- "Entries" — every point you have given this competitor, newest first, with the
  time it was added. Swipe one away and that exact entry is undone; the total
  corrects itself at once.

**Paragraph.**

> The rows sit in dealing order, so they never shuffle around while you play. The
> small number tucked onto each avatar is that competitor's place at the table —
> 1 is the first dealer — not their position in the race. To see who is actually
> ahead, read the totals.

**Paragraph.**

> Once the hand's scores have changed, the "+1" / "+2" / "+3" / "+5" buttons on
> every row go quiet, so you cannot accidentally score the same hand twice.
> Tapping "Next Hand" brings them back for the new hand. "Score options" is never
> switched off, so a mistake can always be corrected — add the missing points
> there, or swipe away the entry that should not be there.

**Note.**

> By default a total stops at zero. Subtracting more than a competitor has takes
> them down to zero and no further, and the subtract buttons go dim once they are
> already at zero. If you play something that genuinely goes negative — Spades,
> Pinochle — turn on "Allow scores below zero" in Settings. That switch applies
> everywhere a score is written, including "Edit Scores" and "Register Past
> Game".

### 4. Hands & the Dealer — `handsAndDealer` (icon intent: hand)

**Paragraph.**

> At the top of the board, the dealer card tells you where you are: who is
> dealing this hand, the hand number on the right, and a "Next to deal:" line
> naming whoever the deal passes to next.

**Paragraph.**

> "Next Hand" stays greyed out until this hand's scores have actually moved.
> Score the hand, then tap it: the deal passes on, the hand counter goes up by
> one, and the quick-add buttons on the rows open again for the new hand.

**Note.**

> The button watches the totals, not how many taps you made. It compares against
> where every total stood when the hand began — so if you add points and then
> swipe them back off, you are exactly where you started and "Next Hand" switches
> off again. A hand that leaves every score untouched can only be finished as a
> draw, which is precisely what it was.

**Paragraph.**

> That is what "Hand Was a Draw" is for. It is available only while nothing has
> been scored this hand, and it starts the next hand anyway. The hand counter
> always moves on; only the question of who deals depends on your "After a Draw"
> setting in Settings.

**Bullets.**

- Same dealer deals again — a drawn hand does not move the deal.
- Pass to the next dealer — the deal moves on, just as after a scored hand.
- Ask each time — the app asks you every time a hand is drawn, naming both
  choices. This is the default; dismissing the question leaves the hand as it
  was.

**Paragraph.**

> Which way the deal travels is also up to you: "Dealing order" in Settings is
> counter-clockwise by default, or clockwise. It decides both who deals next and
> the order the rows appear in. If a game has no seating yet, the dealer card is
> replaced by "Set Up Seating & Dealer" — put everyone in the order they are
> sitting and save, and the first person in that order starts as dealer with the
> hand counter back at 1.

### 5. Finishing a Game — `finishingGame` (icon intent: flag)

**Paragraph.**

> If your game has a target score, a "Target reached!" banner appears at the top
> of the scoreboard the moment someone's total reaches it, and you are asked once
> whether to end the game.

**Bullets.**

- Choose "End Game" to stop there and record the result.
- Choose "Not Yet" to keep the game open. Scoring then locks: the quick-add
  buttons, "Next Hand" and "Hand Was a Draw" all stay off until the over-target
  total comes back down.
- To bring it down, tap "Score options" on that competitor's row and swipe away
  the wrong entry in the "Entries" list. As soon as the total drops below the
  target, the board unlocks and you carry on.

**Paragraph.**

> You can also finish at any time with "End Game" at the top of the scoreboard,
> target or no target. You are asked to confirm, and confirming is final: the
> scores are saved and no more points can be added.

**Paragraph.**

> A finished game leaves "In Progress" and moves into "History" on the Games
> screen. Its badge tells you how it ended: the name of the single highest
> scorer, or "Draw" when two or more competitors finish level at the top. If you
> got a score wrong, see "Correcting a Result".

**Note.**

> Reaching the target only offers to end the game, it never ends it for you. A
> mistapped point should not decide the match, so the board locks instead and
> waits for you to correct the score or confirm the win.

### 6. Correcting a Result — `correctingResult` (icon intent: pencil)

**Paragraph.**

> Wrote down the wrong number? A finished game can be corrected without being
> reopened.

**Steps.**

1. On the Games screen, open the game from "History".
2. Tap "Edit Scores".
3. Type why you are changing the scores. This is required: "Continue" stays
   unavailable until you write something.
4. Tap "Continue", then set each competitor's total. You type the new final
   total, not the number of points to add or take away.
5. Tap "Save".

**Note.**

> Nothing is recorded unless a total actually moves. If every figure ends up the
> same as before, "Save" stays unavailable and leaving the editor changes nothing
> at all.

**Paragraph.**

> The game stays finished and keeps its place in History; only the scores change,
> so a correction can change who won. Afterwards the game carries an "Edited"
> badge wherever it appears, and your reason, along with the date and time of the
> correction, is kept in the "Edit History" section of the game. Correct it again
> later and each correction is listed separately.

### 7. Registering a Past Game — `registeringPastGame` (icon intent: clock)

**Paragraph.**

> For a game you played on paper, or one from before you had the app: register it
> and it joins your history as if you had scored it here.

**Steps.**

1. On the Games screen, tap + and choose "Register Past Game".
2. Pick the game name and at least two competitors. The same rule as a new game
   applies: either all individual players or all teams, never a mix.
3. Tap "Next" and type each competitor's final total.
4. Set when it was played, and add the place under "Location (optional)" if you
   want to.
5. Tap "Save Game".

**Bullets.**

- Date and time set: the game is filed under that exact day and time.
- Date only: the game is filed under that day, and only the date is ever shown.
- "Set the date" turned off: the game is filed under today.
- You cannot pick a date in the future.

**Note.**

> A registered game is saved already finished, with one final total per
> competitor and no target, no seating and no dealer, because nobody is going to
> deal another hand. It goes straight into "History" under its date, gets the
> usual winner or "Draw" badge, and can be corrected later with "Edit Scores"
> like any other finished game.

### 8. Players & Teams — `playersAndTeams` (icon intent: people)

**Paragraph.**

> The Players tab is your roster of people. The Teams tab holds teams — a team is
> just a named group of players. A player can belong to more than one team and
> can still play on their own, so you never have to choose one or the other.

**Bullets.**

- Add someone with the + button at the top of the list — "Add Player" or "Add
  Team". Names have to be unique, and a team needs at least one member, so
  "Save" stays dimmed until both are true.
- Tap a row to edit it: rename a player, or rename a team and tick and untick who
  is in it. While you are building a team you can tap "New Player" to create a
  missing person on the spot.
- Swipe a row to delete that player or team.

**Paragraph.**

> Tap "Sort" to reorder the list by "Name (A–Z)", "Name (Z–A)", "Wins (high to
> low)" or "Wins (low to high)". Your choice is remembered for next time, and the
> Players tab and the Teams tab each keep their own.

**Paragraph.**

> Each row carries a small record badge: a trophy with the number of games won,
> an equals sign with the number of draws (shown only when there are any), a
> chequered flag with the number of finished games played, the win rate as a
> percentage of those finished games, and a green marker with the number of games
> still in progress. Before anyone has played, the badge just says "No games
> yet".

**Note.**

> A win means the single top score of a finished game. If two or more competitors
> finish level on the top score, nobody wins it — it counts as a draw for
> everyone who tied, and for nobody below them. Games still in progress count
> only towards the in-progress number; they are left out of games played and out
> of the win rate until you end them.

**Note.**

> Deleting a player or a team never damages your history. Every past game keeps
> the name it was played under, so old scoreboards and results stay exactly as
> they were.

### 9. Your Data & Backups — `dataAndBackups` (icon intent: cloud)

**Paragraph. — iOS only**

> Your players, teams and games are kept on this device and synced automatically
> to your private iCloud account, so they follow you to your other Apple devices.
> The "iCloud Sync" card at the top of Settings tells you whether that is
> working, and says so if you need to sign in to iCloud first. Backup files you
> make go into the ScoreCard folder in iCloud Drive when iCloud is available, and
> onto the device itself when it is not.

**Paragraph. — Android only**

> Your players, teams and games are kept on this device only — there is no
> syncing to an account or to another phone. Backup files are saved on the
> device, and sharing one is how you move your data somewhere else.

**Bullets.**

- "Back Up Now" saves a snapshot of everything — every player, team and game —
  into a single backup file, and tells you how much it saved.
- "Share Latest Backup" appears once you have made a backup, and sends that file
  anywhere you like: a message, a mail, or another app.
- "Restore from Backup…" lists the backups you have saved, newest first, with the
  date and size of each. Tap one to load it, or tap "Import from Files…" to pick
  a backup file from somewhere else. Swipe a backup in the list to delete the
  file.

**Paragraph.**

> A backup made on iPhone can be restored on Android, and one made on Android can
> be restored on iPhone. That is how you move your card-playing history between
> the two apps: back up on the old phone, share the file to yourself, then use
> "Restore from Backup…" and "Import from Files…" on the new one.

**Note.**

> Restoring replaces everything currently in ScoreCard with the contents of the
> backup — it does not merge — which is why it asks you to confirm with "Replace
> All Data". "Delete All Data" wipes every player, team and game to start fresh.
> Neither can be undone, so make a backup before you do either.

**Note. — iOS only**

> Because your data syncs, deleting all of it here also removes it from your
> other devices. Back up first if there is any chance you will want it again.

## Keeping the two apps in step

Changing this file means changing both apps. A help page that describes one
port's behaviour while the other quietly does something else is worse than no
help page, because the reader has no way to tell which one is lying.

The topic identifiers, and their order, are pinned by a unit test on each
platform:

| | |
| --- | --- |
| iOS | `HelpTopic.all` in `ios/ScoreCard/Models/HelpTopic.swift`, asserted in `ios/ScoreCardTests/` |
| Android | `helpTopics` in `android/app/src/main/java/com/christianmolinari/scorecard/domain/HelpTopic.kt`, asserted in `android/app/src/test/` |

The canonical order is:

1. `gettingStarted`
2. `startingGame`
3. `keepingScore`
4. `handsAndDealer`
5. `finishingGame`
6. `correctingResult`
7. `registeringPastGame`
8. `playersAndTeams`
9. `dataAndBackups`

Adding, removing or reordering a topic therefore fails a test on each side until
both have been updated — which is the point. A block marked **iOS only** is
omitted from the Android transcription and vice versa; every other block must
match word for word.

The help text describes behaviour specified elsewhere in `docs/`. When one of
those rules changes, the matching topic changes with it:

| Rule | Specified in | Topic |
| --- | --- | --- |
| Dealer rotation, the after-a-draw rule | `dealing-rules.md` | Hands & the Dealer |
| The below-zero scoring policy | `scoring-rules.md` | Keeping Score |
| Correcting a finished game | `game-editing.md` | Correcting a Result |
| Registering a past game | `registering-past-games.md` | Registering a Past Game |

## Related documents

- `docs/dealing-rules.md` — how the deal moves around the table.
- `docs/scoring-rules.md` — the below-zero policy every score-writing path obeys.
- `docs/game-editing.md` — correcting a finished game.
- `docs/registering-past-games.md` — transcribing a game played elsewhere.

## Appendix — acronyms and abbreviations

- **iCloud** — Apple's consumer cloud storage and sync service. Not an acronym,
  but named here because the iOS-only passages assume the reader knows it is
  Apple's account-based sync.
- **iOS** — Apple's mobile operating system, the platform of the iPhone app.
- **UI** — user interface.
