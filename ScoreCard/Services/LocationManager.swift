//
//  LocationManager.swift
//  ScoreCard
//
//  Thin CoreLocation wrapper that grabs a one-shot fix (plus a friendly place
//  name via reverse geocoding) when a new game is created. Designed to fail
//  softly: if permission is denied or no fix arrives, the game is simply saved
//  without a location.
//

import Foundation
import CoreLocation
import Observation

/// A plain value capturing where a game was created. Decoupled from CoreLocation
/// so it is easy to store on the `Game` model and pass around SwiftUI.
struct CapturedLocation: Equatable {
    var latitude: Double
    var longitude: Double
    var placeName: String?
}

@Observable
@MainActor
final class LocationManager: NSObject, CLLocationManagerDelegate {
    /// Most recent capture, if any. Observed by the new-game screen.
    private(set) var lastCapture: CapturedLocation?

    /// Whether a capture is currently in flight (drives a spinner in the UI).
    private(set) var isCapturing: Bool = false

    /// Current system authorization status.
    private(set) var authorizationStatus: CLAuthorizationStatus

    private let manager = CLLocationManager()
    private let geocoder = CLGeocoder()

    // Continuation used to bridge the delegate callback into async/await.
    private var pendingContinuation: CheckedContinuation<CapturedLocation?, Never>?

    override init() {
        self.authorizationStatus = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// Ask for "when in use" permission if we have not decided yet.
    func requestAuthorizationIfNeeded() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
    }

    /// Request a single location fix. Returns nil if location is unavailable or
    /// the user has denied access. Never throws — location is best-effort.
    func captureCurrentLocation() async -> CapturedLocation? {
        let status = manager.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            return nil
        }

        // If a previous request is still pending, don't start another.
        guard pendingContinuation == nil else { return lastCapture }

        isCapturing = true
        let fix: CapturedLocation? = await withCheckedContinuation { continuation in
            pendingContinuation = continuation
            manager.requestLocation()
        }
        isCapturing = false

        guard let fix else { return nil }

        // Best-effort reverse geocode; keep the raw fix even if naming fails.
        let named = await reverseGeocode(fix)
        lastCapture = named
        return named
    }

    private func reverseGeocode(_ capture: CapturedLocation) async -> CapturedLocation {
        let location = CLLocation(latitude: capture.latitude, longitude: capture.longitude)
        do {
            let placemarks = try await geocoder.reverseGeocodeLocation(location)
            if let placemark = placemarks.first {
                var result = capture
                result.placeName = Self.describe(placemark)
                return result
            }
        } catch {
            // Geocoding is optional; swallow and return the unnamed fix.
        }
        return capture
    }

    private static func describe(_ placemark: CLPlacemark) -> String? {
        let parts = [placemark.name, placemark.locality, placemark.country]
            .compactMap { $0 }
            .reduce(into: [String]()) { acc, part in
                if !acc.contains(part) { acc.append(part) }
            }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    // MARK: - CLLocationManagerDelegate

    nonisolated func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        Task { @MainActor in
            self.authorizationStatus = status
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let capture = CapturedLocation(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            placeName: nil
        )
        Task { @MainActor in
            self.resume(with: capture)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.resume(with: nil)
        }
    }

    private func resume(with capture: CapturedLocation?) {
        guard let continuation = pendingContinuation else { return }
        pendingContinuation = nil
        continuation.resume(returning: capture)
    }
}
