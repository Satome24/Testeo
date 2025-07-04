package com.example.testeo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment

object BluetoothUtil {

    interface PermissionGrantedCallback {
        fun call()
    }

    /**
     * More efficient caching of name than BluetoothDevice which always does RPC
     */
    data class Device(@SuppressLint("MissingPermission") val device: BluetoothDevice) : Comparable<Device> {
        @SuppressLint("MissingPermission")
        val name: String? = device.name

        override fun equals(other: Any?): Boolean {
            return if (other is Device) {
                device == other.device
            } else false
        }

        override fun hashCode(): Int = device.hashCode()

        /**
         * Sort by name, then address. Sort named devices first
         */
        override fun compareTo(other: Device): Int {
            val thisValid = !name.isNullOrEmpty()
            val otherValid = !other.name.isNullOrEmpty()

            return when {
                thisValid && otherValid -> {
                    val nameComparison = name!!.compareTo(other.name!!)
                    if (nameComparison != 0) nameComparison
                    else device.address.compareTo(other.device.address)
                }
                thisValid -> -1
                otherValid -> 1
                else -> device.address.compareTo(other.device.address)
            }
        }
    }

    /**
     * Android 12 permission handling
     */
    private fun showRationaleDialog(fragment: Fragment, onContinue: () -> Unit) {
        AlertDialog.Builder(fragment.requireActivity()).apply {
            setTitle(fragment.getString(R.string.bluetooth_permission_title))
            setMessage(fragment.getString(R.string.bluetooth_permission_grant))
            setNegativeButton("Cancel", null)
            setPositiveButton("Continue") { _, _ -> onContinue() }
            show()
        }
    }

    private fun showSettingsDialog(fragment: Fragment) {
        val nearbyDevicesLabel = try {
            val resId = fragment.resources.getIdentifier(
                "@android:string/permgrouplab_nearby_devices",
                null,
                null
            )
            if (resId != 0) {
                fragment.resources.getString(resId)
            } else {
                "Nearby devices"
            }
        } catch (e: Exception) {
            "Nearby devices"
        }

        AlertDialog.Builder(fragment.requireActivity()).apply {
            setTitle(fragment.getString(R.string.bluetooth_permission_title))
            setMessage(
                String.format(
                    fragment.getString(R.string.bluetooth_permission_denied),
                    nearbyDevicesLabel
                )
            )
            setNegativeButton("Cancel", null)
            setPositiveButton("Settings") { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${fragment.requireContext().packageName}")
                )
                fragment.startActivity(intent)
            }
            show()
        }
    }

    /**
     * CONNECT + SCAN are granted together in same permission group,
     * so actually no need to check/request both, but one never knows
     */
    fun hasPermissions(
        fragment: Fragment,
        requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        val activity = fragment.requireActivity()
        val missingPermissions = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED

        val showRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)

        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )

        return if (missingPermissions) {
            if (showRationale) {
                showRationaleDialog(fragment) {
                    requestPermissionLauncher.launch(permissions)
                }
            } else {
                requestPermissionLauncher.launch(permissions)
            }
            false
        } else {
            true
        }
    }

    fun onPermissionsResult(
        fragment: Fragment,
        grants: Map<String, Boolean>,
        callback: PermissionGrantedCallback
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val showRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)

        val granted = grants.values.all { it }

        when {
            granted -> callback.call()
            showRationale -> showRationaleDialog(fragment) { callback.call() }
            else -> showSettingsDialog(fragment)
        }
    }
}