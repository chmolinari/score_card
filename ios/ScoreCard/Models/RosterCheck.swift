//
//  RosterCheck.swift
//  ScoreCard
//
//  The roster rules that guard destructive edits: how big a team has to be, what
//  deleting a player would do to the teams they belong to, and which teams are
//  too small to take into a game.
//
//  Deliberately pure (no SwiftUI, no ModelContext) so the rules are unit-tested
//  directly and the two platforms can be compared line for line — the Android
//  port mirrors this in domain/CompetitorSelection.kt.
//
//  Note this is an *editing* rule, not a storage invariant: a smaller team can
//  still arrive from an older backup or from the other platform, and restore must
//  keep accepting it. Such a team is flagged here, never rejected.
//

import Foundation
import SwiftData

enum RosterCheck {

    /// How many members a team needs before it can be saved or played.
    static let minimumTeamSize = 2

    /// What deleting a player would leave behind in one of their teams.
    struct TeamImpact: Equatable {
        var teamName: String
        /// Members the team would have left once the player is gone.
        var remainingMembers: Int

        /// True when the team would be left too small to be picked for a game.
        var fallsBelowMinimum: Bool { remainingMembers < RosterCheck.minimumTeamSize }
    }

    /// A team too small to compete. Teams like this can't be created any more,
    /// but they still exist in older data and in backups.
    static func isUnderStrength(_ team: Team) -> Bool {
        team.sortedMembers.count < minimumTeamSize
    }

    /// The teams `player` belongs to and what each would be left with, in the
    /// order they are shown to the user.
    static func impact(ofDeleting player: Player) -> [TeamImpact] {
        player.sortedTeams.map { team in
            let remaining = team.sortedMembers.filter { $0.persistentModelID != player.persistentModelID }.count
            return TeamImpact(teamName: team.name, remainingMembers: remaining)
        }
    }

    /// Names of the chosen team competitors that are too small to play. Player
    /// competitors are never under strength.
    static func underStrengthNames(in competitors: [GameCompetitor]) -> [String] {
        competitors.compactMap { competitor in
            guard case .team(let team) = competitor, isUnderStrength(team) else { return nil }
            return team.name
        }
    }

    // MARK: - Confirmation copy
    //
    // Built from plain values rather than model objects so the wording is
    // covered by unit tests without standing up a container.

    /// The body of the "delete this player?" confirmation.
    static func playerDeletionMessage(playerName: String, impacts: [TeamImpact]) -> String {
        guard !impacts.isEmpty else {
            return "\(playerName) will be removed from this device. Past game results are not affected."
        }

        let teams = sentenceList(impacts.map(\.teamName))
        var message = "\(playerName) will be removed from \(teams)."

        let broken = impacts.filter(\.fallsBelowMinimum).map(\.teamName)
        if !broken.isEmpty {
            let subject = broken.count == 1 ? "it can" : "they can"
            message += " \(sentenceList(broken)) would be left with too few members, "
                + "so \(subject)'t be picked for a game until you add someone."
        }
        return message + " Past game results are not affected."
    }

    /// The body of the "delete this team?" confirmation.
    static func teamDeletionMessage(teamName: String, memberCount: Int) -> String {
        let people = memberCount == 1 ? "Its 1 member stays" : "Its \(memberCount) members stay"
        return "\(teamName) will be removed from this device. \(people) on the Players tab. Past game results are not affected."
    }

    /// "A", "A and B", "A, B and C" — prose form, unlike `Team.rosterSummary`,
    /// which uses an ampersand for a compact row subtitle.
    static func sentenceList(_ names: [String]) -> String {
        switch names.count {
        case 0: return ""
        case 1: return names[0]
        default: return "\(names.dropLast().joined(separator: ", ")) and \(names.last!)"
        }
    }
}
