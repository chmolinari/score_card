//
//  HelpView.swift
//  ScoreCard
//
//  "How to Use ScoreCard", reached from Settings: an index of help topics and the
//  page for one topic. All of the words come from HelpTopic (specified in
//  docs/help-content.md); this file only decides how the four block kinds look.
//

import SwiftUI

/// The help index — one card per topic, in the order `HelpTopic.all` defines.
struct HelpView: View {
    var body: some View {
        List {
            ForEach(HelpTopic.all) { topic in
                NavigationLink {
                    HelpTopicView(topic: topic)
                } label: {
                    HelpTopicRow(topic: topic)
                }
                .cardRow()
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(AppBackground())
        .navigationTitle("Help")
    }
}

/// A topic's row in the index: its tinted icon and its title.
private struct HelpTopicRow: View {
    let topic: HelpTopic

    var body: some View {
        HStack(spacing: 14) {
            HelpTopicIcon(systemImage: topic.systemImage, tint: topic.tint)
            Text(topic.title)
                .font(.headline)
                // Long titles wrap rather than truncate at large text sizes.
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
        }
        .cardTile()
        .contentShape(Rectangle())
    }
}

/// The circular, tinted badge that identifies a topic. Sized in points on purpose
/// — it carries no text, so it does not need to grow with Dynamic Type.
private struct HelpTopicIcon: View {
    let systemImage: String
    let tint: Color

    var body: some View {
        ZStack {
            Circle().fill(tint.gradient)
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
        }
        .frame(width: 44, height: 44)
        .shadow(color: tint.opacity(0.45), radius: 5, x: 0, y: 2)
        // Purely decorative, so the row reads to VoiceOver as just its title.
        .accessibilityHidden(true)
    }
}

// MARK: - One topic

/// A single help topic, rendering its blocks in order. Nothing here constrains a
/// text height, so the page simply grows at the largest Dynamic Type sizes.
struct HelpTopicView: View {
    let topic: HelpTopic

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                // Blocks have no identity of their own, so index them positionally.
                ForEach(Array(topic.blocks.enumerated()), id: \.offset) { _, block in
                    blockView(block)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .background(AppBackground())
        .navigationTitle(topic.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func blockView(_ block: HelpBlock) -> some View {
        switch block {
        case .paragraph(let text):
            Text(text)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)

        case .steps(let items):
            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        StepBadge(number: index + 1, tint: topic.tint)
                        Text(item)
                            .font(.body)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }

        case .bullets(let items):
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text("•")
                            .font(.body.weight(.bold))
                            .foregroundStyle(topic.tint)
                            .accessibilityHidden(true)
                        Text(item)
                            .font(.body)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }

        case .note(let text):
            HelpNote(text: text, tint: topic.tint)
        }
    }
}

/// The number beside a step. Padding rather than a fixed frame, so the badge
/// grows with the text inside it.
private struct StepBadge: View {
    let number: Int
    let tint: Color

    var body: some View {
        Text("\(number)")
            .font(.footnote.weight(.bold))
            .foregroundStyle(.white)
            .padding(7)
            .background(tint.gradient, in: Circle())
            // The digit reads as part of the sentence beside it.
            .accessibilityHidden(true)
    }
}

/// A `note` block: a tinted callout card, deliberately unlike body text — it
/// carries the rule behind the behaviour, not more prose.
private struct HelpNote: View {
    let text: String
    let tint: Color

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "lightbulb.fill")
                .font(.subheadline)
                .foregroundStyle(tint)
                .accessibilityHidden(true)
            Text(text)
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(
            tint.opacity(0.12),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(tint.opacity(0.35), lineWidth: 1)
        )
    }
}

#Preview("Index") {
    NavigationStack {
        HelpView()
    }
}

#Preview("Topic") {
    NavigationStack {
        HelpTopicView(topic: HelpTopic.all[0])
    }
}
