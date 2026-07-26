package com.christianmolinari.scorecard.domain

// The in-app help page — "How to Use ScoreCard", reached from Settings.
//
// The wording is NOT authored here. It is transcribed verbatim from
// `docs/help-content.md`, which is the single source both apps agree with; the
// iOS app carries the same nine topics in the same order. What the help says is
// a product decision, so change the document first and both transcriptions
// after, never the other way round. Blocks marked "iOS only" in the document are
// omitted here and the "Android only" ones are included.
//
// The topic identifiers and their order are the cross-platform contract, pinned
// by HelpContentTest on this side and by the iOS test suite on the other.
//
// Pure Kotlin, like the rest of domain/: no Android and no Compose types, so it
// unit-tests on the Java Virtual Machine. The icon and the tint are therefore
// expressed as intents (HelpIcon, HelpTint) and resolved to a real icon and
// color by the UI layer.

// The four kinds of content a topic is built from. The vocabulary is
// deliberately small — every kind has to be designed, styled and kept in step on
// both platforms — so prefer rewording a topic over adding a fifth.
sealed interface HelpBlock {
    /** Body text. */
    data class Paragraph(val text: String) : HelpBlock

    /** A numbered list — an ordered procedure the reader follows. */
    data class Steps(val items: List<String>) : HelpBlock

    /** A bulleted list — unordered, for options or related facts. */
    data class Bullets(val items: List<String>) : HelpBlock

    /**
     * A tinted callout, visually distinct from body text. Reserved for the rule
     * behind the behaviour — the thing a reader would otherwise file as a bug.
     */
    data class Note(val text: String) : HelpBlock
}

// The icon intent for a topic. Naming the intent rather than the glyph keeps
// this file free of Compose types; ui/settings/HelpScreen.kt maps each case to a
// Material icon.
enum class HelpIcon {
    Rocket,
    Play,
    Cards,
    Hand,
    Flag,
    Pencil,
    Clock,
    People,
    Cloud,
}

// The accent a topic is tinted with, resolved to a ThemeColors value in the UI
// layer. The cases are the app's five palette accents.
enum class HelpTint {
    Coral,
    Teal,
    Amber,
    Plum,
    Sky,
}

// One help topic: a stable identifier (the routing key and the contract with
// iOS), a title, an icon intent, a tint, and an ordered list of blocks.
data class HelpTopic(
    val id: String,
    val title: String,
    val icon: HelpIcon,
    val tint: HelpTint,
    val blocks: List<HelpBlock>,
)

// The nine topics, in the order docs/help-content.md pins.
val helpTopics: List<HelpTopic> = listOf(
    HelpTopic(
        id = "gettingStarted",
        title = "Getting Started",
        icon = HelpIcon.Rocket,
        tint = HelpTint.Coral,
        blocks = listOf(
            HelpBlock.Paragraph(
                "ScoreCard keeps the score of your card games — Scopa, Briscola, and anything " +
                    "else you play round after round. You set up who is playing, then tap points in " +
                    "as the hands go by; the app remembers every game, who won, and how often each " +
                    "person plays.",
            ),
            HelpBlock.Bullets(
                listOf(
                    "Games — every match you have played or are playing right now, and where you " +
                        "start a new one.",
                    "Players — the people who keep score, each with their record of games played " +
                        "and won.",
                    "Teams — named groups of players, for games played in pairs or sides.",
                    "Settings — dealing and scoring preferences, plus backing up and restoring your " +
                        "data.",
                ),
            ),
            HelpBlock.Steps(
                listOf(
                    "Open the Games tab, tap +, and choose \"New Game\".",
                    "Under Game, tap \"New Game Name\" and type the game you are playing (Scopa, " +
                        "Briscola, and so on) — the first time there are no names yet.",
                    "Tap at least two players so they get a checkmark, then tap \"Next\".",
                    "On the seating step, check who deals first and tap \"Start Game\". The " +
                        "scoreboard opens straight away.",
                ),
            ),
            HelpBlock.Note(
                "You do not have to fill in the Players tab first. Inside New Game the button " +
                    "\"New Player\" creates someone on the spot and adds them to the game " +
                    "automatically — the Players and Teams tabs are just where you manage them " +
                    "later.",
            ),
        ),
    ),
    HelpTopic(
        id = "startingGame",
        title = "Starting a Game",
        icon = HelpIcon.Play,
        tint = HelpTint.Teal,
        blocks = listOf(
            HelpBlock.Paragraph(
                "On the Games tab, tap +. The menu offers \"New Game\" for a match you are about " +
                    "to play, and \"Register Past Game\" for one you already finished on paper. New " +
                    "Game takes two steps: the setup form, then the seating.",
            ),
            HelpBlock.Steps(
                listOf(
                    "Pick the game under Game. The name you used most recently is already " +
                        "selected, so playing the same game again takes no taps; \"New Game Name\" adds " +
                        "one that is not in the list.",
                    "Decide how the game ends. Turn on \"Play to a target score\" and set \"Target\" " +
                        "when the first to reach a number wins, as in Scopa. Leave it off for an " +
                        "open-ended game where you just track running totals, as in Briscola.",
                    "Tap the players — or the teams — who are competing. You need at least two; " +
                        "the \"Playing\" list shows them in the order you added them.",
                    "Tap \"Next\", then \"Start Game\" once the seating looks right.",
                ),
            ),
            HelpBlock.Note(
                "A game is between individual players or between teams, never a mix. As soon as " +
                    "you pick a team, any players you had already chosen are dropped and the players " +
                    "list goes away — pick your competitors one way or the other.",
            ),
            HelpBlock.Bullets(
                listOf(
                    "\"Most Used Players\" and \"Most Used Teams\" sit above the full lists, so your " +
                        "regular group is at the top instead of buried in the alphabet.",
                    "\"New Player\" and \"New Team\" create someone without leaving the screen, and " +
                        "they are selected for this game as soon as you save them.",
                    "The first dealer is picked at random. Tap the dealer to choose someone else, " +
                        "or tap \"Shuffle\" for another random pick. In team games the dealer is still " +
                        "one person, not the team.",
                    "Put everyone else in the order they sit around the table — the deal passes to " +
                        "the next seat each hand.",
                ),
            ),
            // Android only: seating is reordered with arrows, and the game's
            // details show a place name rather than a map.
            HelpBlock.Paragraph(
                "Use the up and down arrows to move the other players into their seating order. " +
                    "If you have allowed location access, the game is tagged with where you played " +
                    "when you tap \"Start Game\", and the game's details show the place name " +
                    "afterwards — there is no map. The form tells you which of the two applies " +
                    "before you start.",
            ),
        ),
    ),
    HelpTopic(
        id = "keepingScore",
        title = "Keeping Score",
        icon = HelpIcon.Cards,
        tint = HelpTint.Amber,
        blocks = listOf(
            HelpBlock.Paragraph(
                "Every competitor gets a row on the scoreboard, with their running total in big " +
                    "type on the right. For most hands you never need more than the four buttons on " +
                    "the row: tap \"+1\", \"+2\", \"+3\" or \"+5\" and the points land straight away.",
            ),
            HelpBlock.Paragraph(
                "For anything else, tap \"Score options\" — the \"…\" button at the end of the row. " +
                    "It opens a sheet just for that competitor, showing their total and four things " +
                    "you can do with it.",
            ),
            HelpBlock.Bullets(
                listOf(
                    "\"Quick Add\" — +1, +2, +3, +5 and +10.",
                    "\"Quick Subtract\" — the same amounts, taken off instead: −1, −2, −3, −5 and −10.",
                    "\"Custom\" — dial any amount between −100 and +100, then tap the button to apply " +
                        "it.",
                    "\"Entries\" — every point you have given this competitor, newest first, with the " +
                        "time it was added. Swipe one away and that exact entry is undone; the total " +
                        "corrects itself at once.",
                ),
            ),
            HelpBlock.Paragraph(
                "The rows sit in dealing order, so they never shuffle around while you play. The " +
                    "small number tucked onto each avatar is that competitor's place at the table — " +
                    "1 is the first dealer — not their position in the race. To see who is actually " +
                    "ahead, read the totals.",
            ),
            HelpBlock.Paragraph(
                "Once the hand's scores have changed, the \"+1\" / \"+2\" / \"+3\" / \"+5\" buttons on " +
                    "every row go quiet, so you cannot accidentally score the same hand twice. " +
                    "Tapping \"Next Hand\" brings them back for the new hand. \"Score options\" is never " +
                    "switched off, so a mistake can always be corrected — add the missing points " +
                    "there, or swipe away the entry that should not be there.",
            ),
            HelpBlock.Note(
                "By default a total stops at zero. Subtracting more than a competitor has takes " +
                    "them down to zero and no further, and the subtract buttons go dim once they are " +
                    "already at zero. If you play something that genuinely goes negative — Spades, " +
                    "Pinochle — turn on \"Allow scores below zero\" in Settings. That switch applies " +
                    "everywhere a score is written, including \"Edit Scores\" and \"Register Past " +
                    "Game\".",
            ),
        ),
    ),
    HelpTopic(
        id = "handsAndDealer",
        title = "Hands & the Dealer",
        icon = HelpIcon.Hand,
        tint = HelpTint.Plum,
        blocks = listOf(
            HelpBlock.Paragraph(
                "At the top of the board, the dealer card tells you where you are: who is " +
                    "dealing this hand, the hand number on the right, and a \"Next to deal:\" line " +
                    "naming whoever the deal passes to next.",
            ),
            HelpBlock.Paragraph(
                "\"Next Hand\" stays greyed out until this hand's scores have actually moved. " +
                    "Score the hand, then tap it: the deal passes on, the hand counter goes up by " +
                    "one, and the quick-add buttons on the rows open again for the new hand.",
            ),
            HelpBlock.Note(
                "The button watches the totals, not how many taps you made. It compares against " +
                    "where every total stood when the hand began — so if you add points and then " +
                    "swipe them back off, you are exactly where you started and \"Next Hand\" switches " +
                    "off again. A hand that leaves every score untouched can only be finished as a " +
                    "draw, which is precisely what it was.",
            ),
            HelpBlock.Paragraph(
                "That is what \"Hand Was a Draw\" is for. It is available only while nothing has " +
                    "been scored this hand, and it starts the next hand anyway. The hand counter " +
                    "always moves on; only the question of who deals depends on your \"After a Draw\" " +
                    "setting in Settings.",
            ),
            HelpBlock.Bullets(
                listOf(
                    "Same dealer deals again — a drawn hand does not move the deal.",
                    "Pass to the next dealer — the deal moves on, just as after a scored hand.",
                    "Ask each time — the app asks you every time a hand is drawn, naming both " +
                        "choices. This is the default; dismissing the question leaves the hand as it " +
                        "was.",
                ),
            ),
            HelpBlock.Paragraph(
                "Which way the deal travels is also up to you: \"Dealing order\" in Settings is " +
                    "counter-clockwise by default, or clockwise. It decides both who deals next and " +
                    "the order the rows appear in. If a game has no seating yet, the dealer card is " +
                    "replaced by \"Set Up Seating & Dealer\" — put everyone in the order they are " +
                    "sitting and save, and the first person in that order starts as dealer with the " +
                    "hand counter back at 1.",
            ),
        ),
    ),
    HelpTopic(
        id = "finishingGame",
        title = "Finishing a Game",
        icon = HelpIcon.Flag,
        tint = HelpTint.Sky,
        blocks = listOf(
            HelpBlock.Paragraph(
                "If your game has a target score, a \"Target reached!\" banner appears at the top " +
                    "of the scoreboard the moment someone's total reaches it, and you are asked once " +
                    "whether to end the game.",
            ),
            HelpBlock.Bullets(
                listOf(
                    "Choose \"End Game\" to stop there and record the result.",
                    "Choose \"Not Yet\" to keep the game open. Scoring then locks: the quick-add " +
                        "buttons, \"Next Hand\" and \"Hand Was a Draw\" all stay off until the over-target " +
                        "total comes back down.",
                    "To bring it down, tap \"Score options\" on that competitor's row and swipe away " +
                        "the wrong entry in the \"Entries\" list. As soon as the total drops below the " +
                        "target, the board unlocks and you carry on.",
                ),
            ),
            HelpBlock.Paragraph(
                "You can also finish at any time with \"End Game\" at the top of the scoreboard, " +
                    "target or no target. You are asked to confirm, and confirming is final: the " +
                    "scores are saved and no more points can be added.",
            ),
            HelpBlock.Paragraph(
                "A finished game leaves \"In Progress\" and moves into \"History\" on the Games " +
                    "screen. Its badge tells you how it ended: the name of the single highest " +
                    "scorer, or \"Draw\" when two or more competitors finish level at the top. If you " +
                    "got a score wrong, see \"Correcting a Result\".",
            ),
            HelpBlock.Note(
                "Reaching the target only offers to end the game, it never ends it for you. A " +
                    "mistapped point should not decide the match, so the board locks instead and " +
                    "waits for you to correct the score or confirm the win.",
            ),
        ),
    ),
    HelpTopic(
        id = "correctingResult",
        title = "Correcting a Result",
        icon = HelpIcon.Pencil,
        tint = HelpTint.Coral,
        blocks = listOf(
            HelpBlock.Paragraph(
                "Wrote down the wrong number? A finished game can be corrected without being " +
                    "reopened.",
            ),
            HelpBlock.Steps(
                listOf(
                    "On the Games screen, open the game from \"History\".",
                    "Tap \"Edit Scores\".",
                    "Type why you are changing the scores. This is required: \"Continue\" stays " +
                        "unavailable until you write something.",
                    "Tap \"Continue\", then set each competitor's total. You type the new final " +
                        "total, not the number of points to add or take away.",
                    "Tap \"Save\".",
                ),
            ),
            HelpBlock.Note(
                "Nothing is recorded unless a total actually moves. If every figure ends up the " +
                    "same as before, \"Save\" stays unavailable and leaving the editor changes nothing " +
                    "at all.",
            ),
            HelpBlock.Paragraph(
                "The game stays finished and keeps its place in History; only the scores change, " +
                    "so a correction can change who won. Afterwards the game carries an \"Edited\" " +
                    "badge wherever it appears, and your reason, along with the date and time of the " +
                    "correction, is kept in the \"Edit History\" section of the game. Correct it again " +
                    "later and each correction is listed separately.",
            ),
        ),
    ),
    HelpTopic(
        id = "registeringPastGame",
        title = "Registering a Past Game",
        icon = HelpIcon.Clock,
        tint = HelpTint.Teal,
        blocks = listOf(
            HelpBlock.Paragraph(
                "For a game you played on paper, or one from before you had the app: register it " +
                    "and it joins your history as if you had scored it here.",
            ),
            HelpBlock.Steps(
                listOf(
                    "On the Games screen, tap + and choose \"Register Past Game\".",
                    "Pick the game name and at least two competitors. The same rule as a new game " +
                        "applies: either all individual players or all teams, never a mix.",
                    "Tap \"Next\" and type each competitor's final total.",
                    "Set when it was played, and add the place under \"Location (optional)\" if you " +
                        "want to.",
                    "Tap \"Save Game\".",
                ),
            ),
            HelpBlock.Bullets(
                listOf(
                    "Date and time set: the game is filed under that exact day and time.",
                    "Date only: the game is filed under that day, and only the date is ever shown.",
                    "\"Set the date\" turned off: the game is filed under today.",
                    "You cannot pick a date in the future.",
                ),
            ),
            HelpBlock.Note(
                "A registered game is saved already finished, with one final total per " +
                    "competitor and no target, no seating and no dealer, because nobody is going to " +
                    "deal another hand. It goes straight into \"History\" under its date, gets the " +
                    "usual winner or \"Draw\" badge, and can be corrected later with \"Edit Scores\" " +
                    "like any other finished game.",
            ),
        ),
    ),
    HelpTopic(
        id = "playersAndTeams",
        title = "Players & Teams",
        icon = HelpIcon.People,
        tint = HelpTint.Amber,
        blocks = listOf(
            HelpBlock.Paragraph(
                "The Players tab is your roster of people. The Teams tab holds teams — a team is " +
                    "just a named group of players. A player can belong to more than one team and " +
                    "can still play on their own, so you never have to choose one or the other.",
            ),
            HelpBlock.Bullets(
                listOf(
                    "Add someone with the + button at the top of the list — \"Add Player\" or \"Add " +
                        "Team\". Names have to be unique, and a team needs at least one member, so " +
                        "\"Save\" stays dimmed until both are true.",
                    "Tap a row to edit it: rename a player, or rename a team and tick and untick who " +
                        "is in it. While you are building a team you can tap \"New Player\" to create a " +
                        "missing person on the spot.",
                    "Swipe a row to delete that player or team.",
                ),
            ),
            HelpBlock.Paragraph(
                "Tap \"Sort\" to reorder the list by \"Name (A–Z)\", \"Name (Z–A)\", \"Wins (high to " +
                    "low)\" or \"Wins (low to high)\". Your choice is remembered for next time, and the " +
                    "Players tab and the Teams tab each keep their own.",
            ),
            HelpBlock.Paragraph(
                "Each row carries a small record badge: a trophy with the number of games won, " +
                    "an equals sign with the number of draws (shown only when there are any), a " +
                    "chequered flag with the number of finished games played, the win rate as a " +
                    "percentage of those finished games, and a green marker with the number of games " +
                    "still in progress. Before anyone has played, the badge just says \"No games " +
                    "yet\".",
            ),
            HelpBlock.Note(
                "A win means the single top score of a finished game. If two or more competitors " +
                    "finish level on the top score, nobody wins it — it counts as a draw for " +
                    "everyone who tied, and for nobody below them. Games still in progress count " +
                    "only towards the in-progress number; they are left out of games played and out " +
                    "of the win rate until you end them.",
            ),
            HelpBlock.Note(
                "Deleting a player or a team never damages your history. Every past game keeps " +
                    "the name it was played under, so old scoreboards and results stay exactly as " +
                    "they were.",
            ),
        ),
    ),
    HelpTopic(
        id = "dataAndBackups",
        title = "Your Data & Backups",
        icon = HelpIcon.Cloud,
        tint = HelpTint.Plum,
        blocks = listOf(
            // Android only: there is no account sync to describe, and the iOS
            // note about deleting data propagating to other devices is omitted.
            HelpBlock.Paragraph(
                "Your players, teams and games are kept on this device only — there is no " +
                    "syncing to an account or to another phone. Backup files are saved on the " +
                    "device, and sharing one is how you move your data somewhere else.",
            ),
            HelpBlock.Bullets(
                listOf(
                    "\"Back Up Now\" saves a snapshot of everything — every player, team and game — " +
                        "into a single backup file, and tells you how much it saved.",
                    "\"Share Latest Backup\" appears once you have made a backup, and sends that file " +
                        "anywhere you like: a message, a mail, or another app.",
                    "\"Restore from Backup…\" lists the backups you have saved, newest first, with the " +
                        "date and size of each. Tap one to load it, or tap \"Import from Files…\" to pick " +
                        "a backup file from somewhere else. Swipe a backup in the list to delete the " +
                        "file.",
                ),
            ),
            HelpBlock.Paragraph(
                "A backup made on iPhone can be restored on Android, and one made on Android can " +
                    "be restored on iPhone. That is how you move your card-playing history between " +
                    "the two apps: back up on the old phone, share the file to yourself, then use " +
                    "\"Restore from Backup…\" and \"Import from Files…\" on the new one.",
            ),
            HelpBlock.Note(
                "Restoring replaces everything currently in ScoreCard with the contents of the " +
                    "backup — it does not merge — which is why it asks you to confirm with \"Replace " +
                    "All Data\". \"Delete All Data\" wipes every player, team and game to start fresh. " +
                    "Neither can be undone, so make a backup before you do either.",
            ),
        ),
    ),
)
