//
//  ContentView.swift
//  ScoreCard
//
//  Root tab bar: Games, Players, Teams, and Settings.
//

import SwiftUI
import SwiftData

struct ContentView: View {
    var body: some View {
        TabView {
            GamesView()
                .tabItem { Label("Games", systemImage: "suit.club.fill") }

            PlayersView()
                .tabItem { Label("Players", systemImage: "person.fill") }

            TeamsView()
                .tabItem { Label("Teams", systemImage: "person.2.fill") }

            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
    }
}

#Preview {
    ContentView()
        .modelContainer(SampleData.container)
        .environment(LocationManager())
}
