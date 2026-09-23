package jp.liki.pockethid

import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences

internal object RecentDevice {
    private const val ADDRESS_KEY = "last_device_address"

    @JvmStatic
    fun remember(preferences: SharedPreferences, address: String) {
        preferences.edit().putString(ADDRESS_KEY, address).apply()
    }

    @JvmStatic
    fun preferredAddress(preferences: SharedPreferences, currentAddress: String?): String? =
        currentAddress ?: preferences.getString(ADDRESS_KEY, null)

    @JvmStatic
    fun indexOf(devices: List<BluetoothDevice>, address: String?): Int =
        devices.indexOfFirst { it.address == address }
}
