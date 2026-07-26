//
//  HelpTopic.swift
//  ScoreCard
//
//  The content of the in-app help page — "How to Use ScoreCard", reached from
//  Settings. Pure data with no view code, so the topics are unit-testable and the
//  rendering lives entirely in HelpView.
//
//  The text is specified in `docs/help-content.md` and transcribed here word for
//  word; that document, not this file, is the source both platforms agree with.
//  The topic identifiers and their order are the cross-platform contract with the
//  Android port and are pinned by a unit test on each side, so adding, removing or
//  reordering a topic here means doing the same in `docs/help-content.md` and in
//  the Android transcription. Blocks marked "Android only" in the specification
//  are omitted below; the "iOS only" ones are the passages included here.
//

import SwiftUI

/// One piece of a help topic. The vocabulary is deliberately small — every kind
/// has to be designed, styled and kept in step on both platforms — so prefer
/// rewording a topic over inventing a fifth kind.
enum HelpBlock {
    /// Body text.
    case paragraph(String)
    /// A numbered list: an ordered procedure the reader follows.
    case steps([String])
    /// A bulleted list: unordered, for options or related facts.
    case bullets([String])
    /// A tinted callout, reserved for the rule behind the behaviour — the thing a
    /// user would otherwise file as a bug.
    case note(String)
}

/// One entry in the help index: how it is listed, and what it says.
struct HelpTopic: Identifiable {
    /// Stable identifier. Part of the cross-platform contract — never rename one.
    let id: String
    let title: String
    let systemImage: String
    let tint: Color
    let blocks: [HelpBlock]
}

extension HelpTopic {
    /// Every topic, in the order the help index lists them.
    static let all: [HelpTopic] = [
        gettingStarted,
        startingGame,
        keepingScore,
        handsAndDealer,
        finishingGame,
        correctingResult,
        registeringPastGame,
        playersAndTeams,
        dataAndBackups
    ]

    // MARK: - 1. Getting Started

    private static let gettingStarted = HelpTopic(
        id: "gettingStarted",
        title: "Getting Started",
        systemImage: "sparkles",
        tint: Theme.coral,
        blocks: [
            .paragraph("""
                ScoreCard keeps the score of your card games — Scopa, Briscola, and anything \
                else you play round after round. You set up who is playing, then tap points in \
                as the hands go by; the app remembers every game, who won, and how often each \
                person plays.
                """),
            .bullets([
                """
                Games — every match you have played or are playing right now, and where you \
                start a new one.
                """,
                """
                Players — the people who keep score, each with their record of games played \
                and won.
                """,
                """
                Teams — named groups of players, for games played in pairs or sides.
                """,
                """
                Settings — dealing and scoring preferences, plus backing up and restoring your \
                data.
                """
            ]),
            .steps([
                """
                Open the Games tab, tap +, and choose "New Game".
                """,
                """
                Under Game, tap "New Game Name" and type the game you are playing (Scopa, \
                Briscola, and so on) — the first time there are no names yet.
                """,
                """
                Tap at least two players so they get a checkmark, then tap "Next".
                """,
                """
                On the seating step, check who deals first and tap "Start Game". The \
                scoreboard opens straight away.
                """
            ]),
            .note("""
                You do not have to fill in the Players tab first. Inside New Game the button \
                "New Player" creates someone on the spot and adds them to the game \
                automatically — the Players and Teams tabs are just where you manage them \
                later.
                """)
        ]
    )

    // MARK: - 2. Starting a Game

    private static let startingGame = HelpTopic(
        id: "startingGame",
        title: "Starting a Game",
        systemImage: "play.circle.fill",
        tint: Theme.teal,
        blocks: [
            .paragraph("""
                On the Games tab, tap +. The menu offers "New Game" for a match you are about \
                to play, and "Register Past Game" for one you already finished on paper. New \
                Game takes two steps: the setup form, then the seating.
                """),
            .steps([
                """
                Pick the game under Game. The name you used most recently is already \
                selected, so playing the same game again takes no taps; "New Game Name" adds \
                one that is not in the list.
                """,
                """
                Decide how the game ends. Turn on "Play to a target score" and set "Target" \
                when the first to reach a number wins, as in Scopa. Leave it off for an \
                open-ended game where you just track running totals, as in Briscola.
                """,
                """
                Tap the players — or the teams — who are competing. You need at least two; \
                the "Playing" list shows them in the order you added them.
                """,
                """
                Tap "Next", then "Start Game" once the seating looks right.
                """
            ]),
            .note("""
                A game is between individual players or between teams, never a mix. As soon as \
                you pick a team, any players you had already chosen are dropped and the players \
                list goes away — pick your competitors one way or the other.
                """),
            .bullets([
                """
                "Most Used Players" and "Most Used Teams" sit above the full lists, so your \
                regular group is at the top instead of buried in the alphabet.
                """,
                """
                "New Player" and "New Team" create someone without leaving the screen, and \
                they are selected for this game as soon as you save them.
                """,
                """
                The first dealer is picked at random. Tap the dealer to choose someone else, \
                or tap "Shuffle" for another random pick. In team games the dealer is still \
                one person, not the team.
                """,
                """
                Put everyone else in the order they sit around the table — the deal passes to \
                the next seat each hand.
                """
            ]),
            // iOS only: dragging the seating order, and the map on the game's details.
            .paragraph("""
                Drag the other players into their seating order. If you have allowed location \
                access, the game is tagged with where you played when you tap "Start Game", \
                and the game's details show the place on a map afterwards. The form tells you \
                which of the two applies before you start.
                """)
        ]
    )

    // MARK: - 3. Keeping Score

    private static let keepingScore = HelpTopic(
        id: "keepingScore",
        title: "Keeping Score",
        systemImage: "rectangle.stack.fill",
        tint: Theme.amber,
        blocks: [
            .paragraph("""
                Every competitor gets a row on the scoreboard, with their running total in big \
                type on the right. For most hands you never need more than the four buttons on \
                the row: tap "+1", "+2", "+3" or "+5" and the points land straight away.
                """),
            .paragraph("""
                For anything else, tap "Score options" — the "…" button at the end of the row. \
                It opens a sheet just for that competitor, showing their total and four things \
                you can do with it.
                """),
            .bullets([
                """
                "Quick Add" — +1, +2, +3, +5 and +10.
                """,
                """
                "Quick Subtract" — the same amounts, taken off instead: −1, −2, −3, −5 and −10.
                """,
                """
                "Custom" — dial any amount between −100 and +100, then tap the button to apply \
                it.
                """,
                """
                "Entries" — every point you have given this competitor, newest first, with the \
                time it was added. Swipe one away and that exact entry is undone; the total \
                corrects itself at once.
                """
            ]),
            .paragraph("""
                The rows sit in dealing order, so they never shuffle around while you play. The \
                small number tucked onto each avatar is that competitor's place at the table — \
                1 is the first dealer — not their position in the race. To see who is actually \
                ahead, read the totals.
                """),
            .paragraph("""
                Once the hand's scores have changed, the "+1" / "+2" / "+3" / "+5" buttons on \
                every row go quiet, so you cannot accidentally score the same hand twice. \
                Tapping "Next Hand" brings them back for the new hand. "Score options" is never \
                switched off, so a mistake can always be corrected — add the missing points \
                there, or swipe away the entry that should not be there.
                """),
            .note("""
                By default a total stops at zero. Subtracting more than a competitor has takes \
                them down to zero and no further, and the subtract buttons go dim once they are \
                already at zero. If you play something that genuinely goes negative — Spades, \
                Pinochle — turn on "Allow scores below zero" in Settings. That switch applies \
                everywhere a score is written, including "Edit Scores" and "Register Past \
                Game".
                """)
        ]
    )

    // MARK: - 4. Hands & the Dealer

    private static let handsAndDealer = HelpTopic(
        id: "handsAndDealer",
        title: "Hands & the Dealer",
        systemImage: "hand.raised.fill",
        tint: Theme.plum,
        blocks: [
            .paragraph("""
                At the top of the board, the dealer card tells you where you are: who is \
                dealing this hand, the hand number on the right, and a "Next to deal:" line \
                naming whoever the deal passes to next.
                """),
            .paragraph("""
                "Next Hand" stays greyed out until this hand's scores have actually moved. \
                Score the hand, then tap it: the deal passes on, the hand counter goes up by \
                one, and the quick-add buttons on the rows open again for the new hand.
                """),
            .note("""
                The button watches the totals, not how many taps you made. It compares against \
                where every total stood when the hand began — so if you add points and then \
                swipe them back off, you are exactly where you started and "Next Hand" switches \
                off again. A hand that leaves every score untouched can only be finished as a \
                draw, which is precisely what it was.
                """),
            .paragraph("""
                That is what "Hand Was a Draw" is for. It is available only while nothing has \
                been scored this hand, and it starts the next hand anyway. The hand counter \
                always moves on; only the question of who deals depends on your "After a Draw" \
                setting in Settings.
                """),
            .bullets([
                """
                Same dealer deals again — a drawn hand does not move the deal.
                """,
                """
                Pass to the next dealer — the deal moves on, just as after a scored hand.
                """,
                """
                Ask each time — the app asks you every time a hand is drawn, naming both \
                choices. This is the default; dismissing the question leaves the hand as it \
                was.
                """
            ]),
            .paragraph("""
                Which way the deal travels is also up to you: "Dealing order" in Settings is \
                counter-clockwise by default, or clockwise. It decides both who deals next and \
                the order the rows appear in. If a game has no seating yet, the dealer card is \
                replaced by "Set Up Seating & Dealer" — put everyone in the order they are \
                sitting and save, and the first person in that order starts as dealer with the \
                hand counter back at 1.
                """)
        ]
    )

    // MARK: - 5. Finishing a Game

    private static let finishingGame = HelpTopic(
        id: "finishingGame",
        title: "Finishing a Game",
        systemImage: "flag.checkered",
        tint: Theme.sky,
        blocks: [
            .paragraph("""
                If your game has a target score, a "Target reached!" banner appears at the top \
                of the scoreboard the moment someone's total reaches it, and you are asked once \
                whether to end the game.
                """),
            .bullets([
                """
                Choose "End Game" to stop there and record the result.
                """,
                """
                Choose "Not Yet" to keep the game open. Scoring then locks: the quick-add \
                buttons, "Next Hand" and "Hand Was a Draw" all stay off until the over-target \
                total comes back down.
                """,
                """
                To bring it down, tap "Score options" on that competitor's row and swipe away \
                the wrong entry in the "Entries" list. As soon as the total drops below the \
                target, the board unlocks and you carry on.
                """
            ]),
            .paragraph("""
                You can also finish at any time with "End Game" at the top of the scoreboard, \
                target or no target. You are asked to confirm, and confirming is final: the \
                scores are saved and no more points can be added.
                """),
            .paragraph("""
                A finished game leaves "In Progress" and moves into "History" on the Games \
                screen. Its badge tells you how it ended: the name of the single highest \
                scorer, or "Draw" when two or more competitors finish level at the top. If you \
                got a score wrong, see "Correcting a Result".
                """),
            .note("""
                Reaching the target only offers to end the game, it never ends it for you. A \
                mistapped point should not decide the match, so the board locks instead and \
                waits for you to correct the score or confirm the win.
                """)
        ]
    )

    // MARK: - 6. Correcting a Result

    private static let correctingResult = HelpTopic(
        id: "correctingResult",
        title: "Correcting a Result",
        systemImage: "square.and.pencil",
        tint: Theme.coral,
        blocks: [
            .paragraph("""
                Wrote down the wrong number? A finished game can be corrected without being \
                reopened.
                """),
            .steps([
                """
                On the Games screen, open the game from "History".
                """,
                """
                Tap "Edit Scores".
                """,
                """
                Type why you are changing the scores. This is required: "Continue" stays \
                unavailable until you write something.
                """,
                """
                Tap "Continue", then set each competitor's total. You type the new final \
                total, not the number of points to add or take away.
                """,
                """
                Tap "Save".
                """
            ]),
            .note("""
                Nothing is recorded unless a total actually moves. If every figure ends up the \
                same as before, "Save" stays unavailable and leaving the editor changes nothing \
                at all.
                """),
            .paragraph("""
                The game stays finished and keeps its place in History; only the scores change, \
                so a correction can change who won. Afterwards the game carries an "Edited" \
                badge wherever it appears, and your reason, along with the date and time of the \
                correction, is kept in the "Edit History" section of the game. Correct it again \
                later and each correction is listed separately.
                """)
        ]
    )

    // MARK: - 7. Registering a Past Game

    private static let registeringPastGame = HelpTopic(
        id: "registeringPastGame",
        title: "Registering a Past Game",
        systemImage: "clock.arrow.circlepath",
        tint: Theme.teal,
        blocks: [
            .paragraph("""
                For a game you played on paper, or one from before you had the app: register it \
                and it joins your history as if you had scored it here.
                """),
            .steps([
                """
                On the Games screen, tap + and choose "Register Past Game".
                """,
                """
                Pick the game name and at least two competitors. The same rule as a new game \
                applies: either all individual players or all teams, never a mix.
                """,
                """
                Tap "Next" and type each competitor's final total.
                """,
                """
                Set when it was played, and add the place under "Location (optional)" if you \
                want to.
                """,
                """
                Tap "Save Game".
                """
            ]),
            .bullets([
                """
                Date and time set: the game is filed under that exact day and time.
                """,
                """
                Date only: the game is filed under that day, and only the date is ever shown.
                """,
                """
                "Set the date" turned off: the game is filed under today.
                """,
                """
                You cannot pick a date in the future.
                """
            ]),
            .note("""
                A registered game is saved already finished, with one final total per \
                competitor and no target, no seating and no dealer, because nobody is going to \
                deal another hand. It goes straight into "History" under its date, gets the \
                usual winner or "Draw" badge, and can be corrected later with "Edit Scores" \
                like any other finished game.
                """)
        ]
    )

    // MARK: - 8. Players & Teams

    private static let playersAndTeams = HelpTopic(
        id: "playersAndTeams",
        title: "Players & Teams",
        systemImage: "person.2.fill",
        tint: Theme.amber,
        blocks: [
            .paragraph("""
                The Players tab is your roster of people. The Teams tab holds teams — a team is \
                just a named group of players. A player can belong to more than one team and \
                can still play on their own, so you never have to choose one or the other.
                """),
            .bullets([
                """
                Add someone with the + button at the top of the list — "Add Player" or "Add \
                Team". Names have to be unique, and a team needs at least one member, so \
                "Save" stays dimmed until both are true.
                """,
                """
                Tap a row to edit it: rename a player, or rename a team and tick and untick who \
                is in it. While you are building a team you can tap "New Player" to create a \
                missing person on the spot.
                """,
                """
                Swipe a row to delete that player or team.
                """
            ]),
            .paragraph("""
                Tap "Sort" to reorder the list by "Name (A–Z)", "Name (Z–A)", "Wins (high to \
                low)" or "Wins (low to high)". Your choice is remembered for next time, and the \
                Players tab and the Teams tab each keep their own.
                """),
            .paragraph("""
                Each row carries a small record badge: a trophy with the number of games won, \
                an equals sign with the number of draws (shown only when there are any), a \
                chequered flag with the number of finished games played, the win rate as a \
                percentage of those finished games, and a green marker with the number of games \
                still in progress. Before anyone has played, the badge just says "No games \
                yet".
                """),
            .note("""
                A win means the single top score of a finished game. If two or more competitors \
                finish level on the top score, nobody wins it — it counts as a draw for \
                everyone who tied, and for nobody below them. Games still in progress count \
                only towards the in-progress number; they are left out of games played and out \
                of the win rate until you end them.
                """),
            .note("""
                Deleting a player or a team never damages your history. Every past game keeps \
                the name it was played under, so old scoreboards and results stay exactly as \
                they were.
                """)
        ]
    )

    // MARK: - 9. Your Data & Backups

    private static let dataAndBackups = HelpTopic(
        id: "dataAndBackups",
        title: "Your Data & Backups",
        systemImage: "icloud.fill",
        tint: Theme.sky,
        blocks: [
            // iOS only: iCloud sync and the iCloud Drive folder backups land in.
            .paragraph("""
                Your players, teams and games are kept on this device and synced automatically \
                to your private iCloud account, so they follow you to your other Apple devices. \
                The "iCloud Sync" card at the top of Settings tells you whether that is \
                working, and says so if you need to sign in to iCloud first. Backup files you \
                make go into the ScoreCard folder in iCloud Drive when iCloud is available, and \
                onto the device itself when it is not.
                """),
            .bullets([
                """
                "Back Up Now" saves a snapshot of everything — every player, team and game — \
                into a single backup file, and tells you how much it saved.
                """,
                """
                "Share Latest Backup" appears once you have made a backup, and sends that file \
                anywhere you like: a message, a mail, or another app.
                """,
                """
                "Restore from Backup…" lists the backups you have saved, newest first, with the \
                date and size of each. Tap one to load it, or tap "Import from Files…" to pick \
                a backup file from somewhere else. Swipe a backup in the list to delete the \
                file.
                """
            ]),
            .paragraph("""
                A backup made on iPhone can be restored on Android, and one made on Android can \
                be restored on iPhone. That is how you move your card-playing history between \
                the two apps: back up on the old phone, share the file to yourself, then use \
                "Restore from Backup…" and "Import from Files…" on the new one.
                """),
            .note("""
                Restoring replaces everything currently in ScoreCard with the contents of the \
                backup — it does not merge — which is why it asks you to confirm with "Replace \
                All Data". "Delete All Data" wipes every player, team and game to start fresh. \
                Neither can be undone, so make a backup before you do either.
                """),
            // iOS only: the consequence of syncing when everything is deleted.
            .note("""
                Because your data syncs, deleting all of it here also removes it from your \
                other devices. Back up first if there is any chance you will want it again.
                """)
        ]
    )
}
