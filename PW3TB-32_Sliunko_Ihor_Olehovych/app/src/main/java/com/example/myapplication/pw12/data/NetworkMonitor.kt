package com.example.myapplication.pw12.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Перевірка підключення до мережі (інтерфейс — щоб у тестах підставити фейк). */
interface NetworkMonitor {
    /** Миттєва перевірка перед виконанням запиту. */
    fun isOnline(): Boolean

    /** Потік змін стану підключення — UI показує банер «Немає з'єднання» без запиту. */
    val online: Flow<Boolean>
}

/**
 * Реалізація на ConnectivityManager: мережа вважається доступною, якщо в неї є
 * NET_CAPABILITY_INTERNET і NET_CAPABILITY_VALIDATED (система перевірила вихід в інтернет).
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override val online: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline())
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(isOnline())
            }
        }
        trySend(isOnline())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
