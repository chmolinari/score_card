package com.christianmolinari.scorecard.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// A plain value capturing where a game was created. Decoupled from the
// platform location types so it is easy to store on the game and pass around
// the UI.
data class CapturedLocation(
    val latitude: Double,
    val longitude: Double,
    val placeName: String?,
)

// Thin location wrapper that grabs a one-shot fix (plus a friendly place name
// via reverse geocoding) when a new game is created. Port of the iOS
// LocationManager. Designed to fail softly: if permission is denied or no fix
// arrives, the game is simply saved without a location — nothing here ever
// throws.
class LocationCapture(private val context: Context) {

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // Request a single location fix. Returns null if location is unavailable
    // or the user has denied access. Never throws — location is best-effort.
    @SuppressLint("MissingPermission")
    suspend fun capture(): CapturedLocation? {
        if (!hasPermission()) return null
        val manager =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val fix = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                currentLocation(manager)
            } else {
                bestLastKnownLocation(manager)
            }
        } catch (_: Exception) {
            // Any platform failure (provider gone, security race, ...) just
            // means "no fix".
            null
        } ?: return null

        // Best-effort reverse geocode; keep the raw fix even if naming fails.
        val placeName = reverseGeocode(context, fix.latitude, fix.longitude)
        return CapturedLocation(fix.latitude, fix.longitude, placeName)
    }

    // One-shot fix via LocationManager.getCurrentLocation (API 30+), bridging
    // the callback into a coroutine. Prefers the fused provider when present.
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun currentLocation(manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    manager.allProviders.contains(LocationManager.FUSED_PROVIDER) ->
                    LocationManager.FUSED_PROVIDER
                manager.allProviders.contains(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                manager.allProviders.contains(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    // Pre-API-30 fallback: the freshest cached fix across all providers.
    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(manager: LocationManager): Location? =
        manager.allProviders
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (_: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }

    companion object {
        // Best-effort reverse geocode of a coordinate into a readable place
        // name. Shared with the game views so they can resolve an address on
        // the fly for games that only stored raw coordinates. Returns null on
        // any failure — geocoding is optional.
        suspend fun reverseGeocode(
            context: Context,
            latitude: Double,
            longitude: Double,
        ): String? {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context)
            val address: Address? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocation(
                            latitude,
                            longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    if (continuation.isActive) {
                                        continuation.resume(addresses.firstOrNull())
                                    }
                                }

                                override fun onError(errorMessage: String?) {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            },
                        )
                    }
                } else {
                    // The synchronous variant is deprecated but the only option
                    // below API 33; it does network I/O, so hop off the main
                    // thread.
                    withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                    }
                }
            } catch (_: Exception) {
                // Geocoding is optional; swallow and report no name.
                null
            }
            return address?.let { describe(it) }
        }

        // Turn an address into a readable line, e.g. "Via Toledo 1, Napoli,
        // Italy", falling back through coarser components when finer ones are
        // missing. Mirrors LocationManager.describe on iOS (parts deduped and
        // joined with ", ").
        private fun describe(address: Address): String? {
            // Prefer an explicit street (thoroughfare + house number);
            // otherwise use the feature name the geocoder provides.
            val street = address.thoroughfare?.let { thoroughfare ->
                address.subThoroughfare?.let { number -> "$thoroughfare $number" } ?: thoroughfare
            } ?: address.featureName

            val parts = mutableListOf<String>()
            for (part in listOfNotNull(street, address.locality, address.countryName)) {
                if (part !in parts) parts.add(part)
            }
            return if (parts.isEmpty()) null else parts.joinToString(", ")
        }
    }
}
