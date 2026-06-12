//
//  StatusMessage.swift
//  ScoreCard
//
//  Small identifiable wrapper so a success/error result can drive an .alert.
//

import Foundation

struct StatusMessage: Identifiable {
    let id = UUID()
    let title: String
    let body: String
}
