package com.example.myapplication.pw16.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.example.myapplication.facilities.GeoPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Джерело позиції; інтерфейс дозволяє підмінити GPS у модульних тестах. */
fun interface LocationSource {
    fun updates(): Flow<GeoPoint>
}

/**
 * Позиція від FusedLocationProviderClient, який поєднує GPS, Wi-Fi та мобільну мережу.
 * callbackFlow перетворює callback API на Flow: підписка на оновлення існує, поки потік збирають,
 * а awaitClose гарантує відписку й економить заряд батареї.
 */
class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    // Дозвіл перевіряє екран і ViewModel: цей потік збирається лише після його надання
    @SuppressLint("MissingPermission")
    override fun updates(): Flow<GeoPoint> = callbackFlow {
        // Остання відома позиція дає результат одразу, ще до першого нового вимірювання
        runCatching { client.lastLocation.await() }.getOrNull()?.let {
            trySend(GeoPoint(it.latitude, it.longitude))
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(GeoPoint(it.latitude, it.longitude)) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { error -> close(error) }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 5_000L
        const val MIN_DISTANCE_M = 10f
    }
}
