//
//  Theme.swift
//  ScoreCard
//
//  Shared "warm & playful" visual language: a coral/teal/amber palette, a warm
//  gradient background, floating rounded-card tiles with soft shadows, and
//  colorful name avatars. Screens opt in via the helpers here so the look stays
//  consistent and is defined in exactly one place.
//

import SwiftUI
import UIKit

enum Theme {
    // Core accents. Each adapts to light/dark so the warmth survives both.
    static let coral = Color(light: 0xFF6B6B, dark: 0xFF8A8A)
    static let teal  = Color(light: 0x12B3A6, dark: 0x32D6C6)
    static let amber = Color(light: 0xF5A314, dark: 0xFBBF24)
    static let plum  = Color(light: 0x7C5CFF, dark: 0x9F8BFF)
    static let sky   = Color(light: 0x3B82F6, dark: 0x60A5FA)

    /// The primary brand/accent color (tab bar, prominent buttons).
    static let accent = coral

    /// Surface for the floating card tiles. A warm white that lifts off the
    /// gradient, and an elevated warm charcoal in the dark.
    static let cardSurface = Color(light: 0xFFFFFF, dark: 0x2A2632)

    // Gradient backdrop endpoints — a soft, cool near-white that keeps the
    // focus on the card tiles. The accents (coral/amber) stay warm against it.
    static let backgroundTop    = Color(light: 0xF7FAFC, dark: 0x14171C)
    static let backgroundBottom = Color(light: 0xE9F0F5, dark: 0x171D24)

    /// A stable, pleasant accent color for a given name — used for avatars and
    /// per-competitor highlights so the same person always gets the same hue.
    static func accent(for name: String) -> Color {
        let palette = [coral, teal, amber, plum, sky]
        let hash = name.unicodeScalars.reduce(5381) { ($0 &* 33) &+ Int($1.value) }
        return palette[abs(hash) % palette.count]
    }
}

// MARK: - Background

/// The soft diagonal gradient that sits behind every screen.
struct AppBackground: View {
    var body: some View {
        LinearGradient(
            colors: [Theme.backgroundTop, Theme.backgroundBottom],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }
}

// MARK: - Card tile

/// Wraps content in a rounded, softly-shadowed surface — the "playing card"
/// look used for list rows and panels.
private struct CardTile: ViewModifier {
    var padding: CGFloat
    var cornerRadius: CGFloat

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(
                Theme.cardSurface,
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
            .shadow(color: .black.opacity(0.10), radius: 10, x: 0, y: 5)
    }
}

extension View {
    /// Render the view as a floating card tile.
    func cardTile(padding: CGFloat = 16, cornerRadius: CGFloat = 22) -> some View {
        modifier(CardTile(padding: padding, cornerRadius: cornerRadius))
    }

    /// Strip a `List`/`ScrollView`'s opaque background so the gradient shows
    /// through. Apply to the scrolling container.
    func appScreen() -> some View {
        scrollContentBackground(.hidden)
            .background(AppBackground())
    }

    /// Standard chrome for a card-tile row inside a `List`: transparent row,
    /// no separators, and breathing room around each card.
    func cardRow() -> some View {
        listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
    }
}

// MARK: - Avatar

/// A colorful circular badge for a person or team — initials by default, or an
/// SF Symbol. The hue is derived from the name so it's stable and recognizable.
struct Avatar: View {
    let name: String
    var systemImage: String? = nil
    var size: CGFloat = 44

    var body: some View {
        let color = Theme.accent(for: name)
        ZStack {
            Circle().fill(color.gradient)
            Group {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: size * 0.44, weight: .semibold))
                } else {
                    Text(initials)
                        .font(.system(size: size * 0.40, weight: .bold, design: .rounded))
                }
            }
            .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .shadow(color: color.opacity(0.45), radius: 5, x: 0, y: 2)
    }

    private var initials: String {
        let parts = name.split(separator: " ")
        let first = parts.first?.first.map(String.init) ?? ""
        let second = parts.dropFirst().first?.first.map(String.init) ?? ""
        let combined = first + second
        return combined.isEmpty ? "?" : combined.uppercased()
    }
}

// MARK: - Section header

/// A small, friendly section title for use as a `Section` header.
struct PlayfulSectionHeader: View {
    let title: String
    var systemImage: String? = nil

    var body: some View {
        Label {
            Text(title)
        } icon: {
            if let systemImage { Image(systemName: systemImage) }
        }
        .font(.subheadline.weight(.bold))
        .foregroundStyle(Theme.accent)
        .textCase(nil)
        .padding(.leading, 4)
    }
}

// MARK: - Color hex helpers

extension Color {
    /// Build a color from two hex values, one per interface style.
    init(light: UInt64, dark: UInt64) {
        self = Color(uiColor: UIColor { traits in
            UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

private extension UIColor {
    convenience init(rgb: UInt64) {
        self.init(
            red:   CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue:  CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}
