//
//  ActionLogRecorder.swift
//  ScoreCard
//
//  Turns SwiftData's save notifications into action-log entries.
//
//  This observes **willSave**, not didSave, and that ordering is the whole
//  trick: at willSave the objects being deleted are still readable, so the log
//  can say "deleted Adriano, member of Adriano e Christian". By didSave only
//  bare identifiers remain and the interesting detail is gone.
//
//  One observer covers every mutation in the app — no call site has to remember
//  to log. What it cannot do is tell a local edit apart from a CloudKit merge
//  arriving in the same context; for the destructive actions that matters most,
//  the views also write an explicit `userConfirmed…` breadcrumb, and the pair
//  answers "did someone on *this* device do it".
//

import Foundation
import SwiftData

@MainActor
final class ActionLogRecorder {

    /// Only set when a test injects one. Otherwise the shared log is resolved
    /// *at use*, not at construction: the recorder is built when the app's view
    /// tree is created, which is before the launch arguments get a chance to
    /// redirect the shared log, so capturing it here would pin the recorder to
    /// the original file while everything else read the new one.
    private let injectedLog: ActionLog?
    private var log: ActionLog { injectedLog ?? .shared }

    /// Read through closures rather than straight from `UserDefaults`, so a
    /// test is not at the mercy of whatever the last run left switched on or
    /// off on that simulator. Production supplies the defaults below.
    private let enabledProvider: () -> Bool
    private let maxMiBProvider: () -> Int

    private var observer: NSObjectProtocol?
    private var didSaveObserver: NSObjectProtocol?

    /// Models seen at willSave whose entries are written once the save lands.
    ///
    /// Identifiers are deliberately resolved late. A freshly inserted object's
    /// `persistentModelID` is temporary and *changes* when it is first saved —
    /// the same trap `GameDraft` documents — so writing a game's creation line
    /// at willSave would stamp it with an id that nothing else ever uses again,
    /// and the game could not be followed through the log. Deleted objects are
    /// the opposite case and are written at willSave, while their names and
    /// relationships can still be read.
    private var pending: [(model: any PersistentModel, verb: String)] = []

    init(log: ActionLog? = nil,
         isEnabled: @escaping () -> Bool = ActionLogRecorder.storedIsEnabled,
         maxMiB: @escaping () -> Int = ActionLogRecorder.storedMaxMiB) {
        self.injectedLog = log
        self.enabledProvider = isEnabled
        self.maxMiBProvider = maxMiB
    }

    /// Absent key means the feature has never been turned off — which is "on".
    static let storedIsEnabled: () -> Bool = {
        UserDefaults.standard.object(forKey: ActionLogSize.enabledKey) as? Bool ?? true
    }

    static let storedMaxMiB: () -> Int = {
        let stored = UserDefaults.standard.integer(forKey: ActionLogSize.maxMiBKey)
        return stored > 0 ? stored : ActionLogSize.defaultMiB
    }

    /// Begin recording. Called once, for the app's lifetime.
    func start() {
        guard observer == nil else { return }
        // queue: nil is load-bearing. Passing an OperationQueue makes the block
        // run *after* the post returns, by which time the save has completed
        // and the deleted objects are gone — exactly the detail willSave was
        // chosen to capture. A nil queue runs it synchronously on the posting
        // thread, which for the main context is the main thread.
        observer = NotificationCenter.default.addObserver(
            forName: ModelContext.willSave,
            object: nil,
            queue: nil
        ) { [weak self] notification in
            MainActor.assumeIsolated {
                guard let context = notification.object as? ModelContext else { return }
                self?.record(context)
            }
        }
        didSaveObserver = NotificationCenter.default.addObserver(
            forName: ModelContext.didSave,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.flushPending() }
        }
    }

    func stop() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        if let didSaveObserver { NotificationCenter.default.removeObserver(didSaveObserver) }
        observer = nil
        didSaveObserver = nil
        pending = []
    }

    // MARK: - Recording

    private var isEnabled: Bool { enabledProvider() }
    private var maxMiB: Int { maxMiBProvider() }

    private func record(_ context: ModelContext) {
        guard isEnabled else { return }
        // Deletions are written now, while the object can still be read.
        let limit = maxMiB
        for model in context.deletedModelsArray { emit(model, verb: "Deleted", maxMiB: limit) }
        // Creations and changes wait for didSave, so their ids are the permanent
        // ones every later line will use.
        pending += context.insertedModelsArray.map { ($0, "Created") }
        pending += context.changedModelsArray.map { ($0, "Changed") }
    }

    private func flushPending() {
        guard !pending.isEmpty else { return }
        let queued = pending
        pending = []
        guard isEnabled else { return }
        let limit = maxMiB
        for (model, verb) in queued { emit(model, verb: verb, maxMiB: limit) }
    }

    private func emit(_ model: any PersistentModel, verb: String, maxMiB: Int) {
        guard let entry = describe(model, verb: verb) else { return }
        log.append(entry, maxMiB: maxMiB)
    }

    /// Map a model to a log line. Relationship access is kept defensive: these
    /// objects may be mid-delete.
    private func describe(_ model: any PersistentModel, verb: String) -> ActionLogEntry? {
        let id = Self.identifier(model.persistentModelID)

        switch model {
        case let player as Player:
            return ActionLogEntry(
                ts: .now, action: "player\(verb)", entity: "Player", entityId: id,
                gameId: nil, name: player.name,
                detail: teamsDetail(player))

        case let team as Team:
            return ActionLogEntry(
                ts: .now, action: "team\(verb)", entity: "Team", entityId: id,
                gameId: nil, name: team.name,
                detail: ["members": team.sortedMembers.map(\.name).joined(separator: ", ")])

        case let game as Game:
            var detail = ["title": game.title, "hand": "\(game.currentHand)"]
            if let closedAt = game.closedAt {
                detail["closedAt"] = Self.stamp(closedAt)
            }
            if let target = game.targetPoints { detail["target"] = "\(target)" }
            return ActionLogEntry(
                ts: .now, action: "game\(verb)", entity: "Game", entityId: id,
                gameId: id, name: game.title, detail: detail)

        case let participant as GameParticipant:
            return ActionLogEntry(
                ts: .now, action: "participant\(verb)", entity: "GameParticipant", entityId: id,
                gameId: participant.game.map { Self.identifier($0.persistentModelID) },
                name: participant.displayName,
                detail: ["total": "\(participant.totalScore)"])

        // Every scoring change lands here: points added, and points undone.
        case let entry as ScoreEntry:
            let participant = entry.participant
            return ActionLogEntry(
                ts: .now, action: "score\(verb == "Created" ? "Added" : verb)",
                entity: "ScoreEntry", entityId: id,
                gameId: participant?.game.map { Self.identifier($0.persistentModelID) },
                name: participant?.displayName,
                detail: ["points": "\(entry.points)"])

        case let seat as Seat:
            return ActionLogEntry(
                ts: .now, action: "seat\(verb)", entity: "Seat", entityId: id,
                gameId: seat.game.map { Self.identifier($0.persistentModelID) },
                name: seat.player?.name,
                detail: ["position": "\(seat.position)"])

        // A correction to a finished game, carrying the reason the user gave.
        case let edit as GameEdit:
            return ActionLogEntry(
                ts: .now, action: "gameEdit\(verb)", entity: "GameEdit", entityId: id,
                gameId: edit.game.map { Self.identifier($0.persistentModelID) },
                name: nil,
                detail: ["reason": edit.reason])

        case let name as GameName:
            return ActionLogEntry(
                ts: .now, action: "gameName\(verb)", entity: "GameName", entityId: id,
                gameId: nil, name: name.name, detail: nil)

        default:
            return nil
        }
    }

    private func teamsDetail(_ player: Player) -> [String: String]? {
        let teams = player.sortedTeams.map(\.name)
        guard !teams.isEmpty else { return nil }
        return ["teams": teams.joined(separator: ", ")]
    }

    // MARK: - Helpers

    /// A short, stable-enough string for correlating lines about one object.
    /// SwiftData identifiers have no public stable text form, so this uses the
    /// hash — good enough to tie a game's lines together, never persisted.
    static func identifier(_ id: PersistentIdentifier) -> String {
        "\(abs(id.hashValue))"
    }

    static func stamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }
}

// MARK: - Explicit breadcrumbs

extension ActionLogRecorder {

    /// Record something the user did on purpose, as opposed to a change merely
    /// observed in the store. Pairing these with the store-level lines is what
    /// distinguishes a deletion made here from one that arrived over iCloud.
    ///
    /// `force` writes the line even when recording is switched off. It exists
    /// for exactly one case: switching recording off is itself the thing most
    /// worth knowing about, and the flag is already false by the time we are
    /// told about it, so an unforced write would silently drop it — leaving a
    /// log that simply stops, with nothing to say why.
    @MainActor
    static func note(_ action: String, name: String? = nil, gameId: String? = nil,
                     detail: [String: String]? = nil, force: Bool = false) {
        let enabled = UserDefaults.standard.object(forKey: ActionLogSize.enabledKey) as? Bool ?? true
        guard enabled || force else { return }
        let stored = UserDefaults.standard.integer(forKey: ActionLogSize.maxMiBKey)
        ActionLog.shared.append(
            ActionLogEntry(ts: .now, action: action, entity: "UserAction", entityId: "-",
                           gameId: gameId, name: name, detail: detail),
            maxMiB: stored > 0 ? stored : ActionLogSize.defaultMiB)
    }
}
