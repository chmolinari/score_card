//
//  ScoreCardApp.swift
//  ScoreCard
//
//  Created by Christian Molinari on 30/05/2026.
//

import SwiftUI
import SwiftData

@main
struct ScoreCardApp: App {
    /// One shared LocationManager for the whole app, injected via the environment.
    @State private var locationManager = LocationManager()

    let sharedModelContainer: ModelContainer = ScoreCardApp.makeModelContainer()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(locationManager)
        }
        .modelContainer(sharedModelContainer)
    }

    /// Builds the persistent SwiftData container with CloudKit sync enabled.
    ///
    /// SwiftData mirrors the store to the user's private CloudKit database when
    /// `cloudKitDatabase` is set, using the iCloud container declared in the
    /// app's entitlements. If that fails (for example, no iCloud account is
    /// signed in or the container isn't provisioned), we fall back to a
    /// local-only store so the app still works offline.
    @MainActor
    private static func makeModelContainer() -> ModelContainer {
        let schema = Schema(ScoreCardSchema.models)

        // UI tests pass "-uitesting" to run against a throwaway in-memory store,
        // so they start from a clean slate and never touch real or synced data.
        if ProcessInfo.processInfo.arguments.contains("-uitesting") {
            let testConfiguration = ModelConfiguration(
                schema: schema,
                isStoredInMemoryOnly: true,
                cloudKitDatabase: .none
            )
            do {
                return try ModelContainer(for: schema, configurations: [testConfiguration])
            } catch {
                fatalError("Could not create in-memory ModelContainer for UI tests: \(error)")
            }
        }

        // Diagnostic: "-noCloudKit" runs against the same on-disk store but with
        // sync disabled, to isolate whether CloudKit mirroring is the cause of
        // on-device slowness. Harmless to ship; only active when the flag is set.
        let cloudKitDatabase: ModelConfiguration.CloudKitDatabase =
            ProcessInfo.processInfo.arguments.contains("-noCloudKit") ? .none : .automatic

        let cloudConfiguration = ModelConfiguration(
            schema: schema,
            isStoredInMemoryOnly: false,
            cloudKitDatabase: cloudKitDatabase
        )

        let container: ModelContainer
        do {
            container = try ModelContainer(for: schema, configurations: [cloudConfiguration])
        } catch {
            // CloudKit unavailable — degrade gracefully to a local store.
            let localConfiguration = ModelConfiguration(
                schema: schema,
                isStoredInMemoryOnly: false,
                cloudKitDatabase: .none
            )
            do {
                container = try ModelContainer(for: schema, configurations: [localConfiguration])
            } catch {
                fatalError("Could not create ModelContainer: \(error)")
            }
        }

        // Optional one-time sample data for testing: launch with "-seedSampleData".
        if ProcessInfo.processInfo.arguments.contains("-seedSampleData") {
            SampleDataSeeder.seedIfNeeded(into: container.mainContext)
        }

        return container
    }
}
