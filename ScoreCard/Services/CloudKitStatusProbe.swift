//
//  CloudKitStatusProbe.swift
//  ScoreCard
//
//  Isolates the one bit of direct CloudKit usage in the app: reading the
//  iCloud account status so Settings can show whether sync is active. SwiftData
//  performs the actual syncing automatically.
//

import Foundation
import CloudKit

enum CloudKitStatusProbe {
    /// Maps CloudKit's account status onto the app's friendly status enum.
    static func accountStatus() async -> CloudAccountStatus {
        do {
            let status = try await CKContainer.default().accountStatus()
            switch status {
            case .available:
                return .available
            case .noAccount:
                return .noAccount
            case .restricted:
                return .restricted
            case .couldNotDetermine, .temporarilyUnavailable:
                return .unavailable
            @unknown default:
                return .unavailable
            }
        } catch {
            return .unavailable
        }
    }
}
