package com.example.testeo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.ListFragment
import androidx.fragment.app.setFragmentResult
import java.util.*

/**
 * Show list of BLE devices
 */
class DevicesFragment : ListFragment() {

    private enum class ScanState { NONE, LE_SCAN, DISCOVERY, DISCOVERY_FINISHED }

    private var scanState = ScanState.NONE
    private var menu: Menu? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val listItems = ArrayList<BluetoothUtil.Device>()
    private lateinit var listAdapter: ArrayAdapter<BluetoothUtil.Device>

    private lateinit var requestBluetoothPermissionLauncherForStartScan: ActivityResultLauncher<Array<String>>
    private lateinit var requestLocationPermissionLauncherForStartScan: ActivityResultLauncher<String>

    companion object {
        private const val LE_SCAN_PERIOD = 10000L // similar to bluetoothAdapter.startDiscovery
        const val REQUEST_KEY_DEVICE_SELECTED = "device_selected"
        const val BUNDLE_KEY_DEVICE_ADDRESS = "device_address"
        const val BUNDLE_KEY_DEVICE_NAME = "device_name"
    }

    private val leScanStopHandler = Handler(Looper.getMainLooper())

    private val leScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
        if (device != null && activity != null) {
            activity?.runOnUiThread { updateScan(device) }
        }
    }

    private val leScanStopCallback = Runnable { stopScan() }

    private val discoveryBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    // ERROR CORREGIDO: Verificar null antes de pasar a updateScan
                    if (device != null && device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC && activity != null) {
                        activity?.runOnUiThread { updateScan(device) }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanState = ScanState.DISCOVERY_FINISHED // don't cancel again
                    stopScan()
                }
            }
        }
    }

    private val discoveryIntentFilter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        // Initialize permission launchers
        requestBluetoothPermissionLauncherForStartScan = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            // ERROR CORREGIDO: Crear callback apropiado
            BluetoothUtil.onPermissionsResult(this, granted, object : BluetoothUtil.PermissionGrantedCallback {
                override fun call() {
                    startScan()
                }
            })
        }

        requestLocationPermissionLauncherForStartScan = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                Handler(Looper.getMainLooper()).postDelayed({ startScan() }, 1)
            } else {
                AlertDialog.Builder(requireActivity()).apply {
//                    setTitle(getText(R.string.location_permission_title))
                    setMessage(getText(R.string.location_permission_denied))
                    setPositiveButton(android.R.string.ok, null)
                    show()
                }
            }
        }

        // Initialize Bluetooth adapter
        if (requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }

        // Initialize list adapter
        listAdapter = object : ArrayAdapter<BluetoothUtil.Device>(requireActivity(), 0, listItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val device = listItems[position]
                val view = convertView ?: requireActivity().layoutInflater.inflate(R.layout.device_list_item, parent, false)

                val text1 = view.findViewById<TextView>(R.id.text1)
                val text2 = view.findViewById<TextView>(R.id.text2)

                val deviceName = device.name?.takeIf { it.isNotEmpty() } ?: "<unnamed>"
                text1.text = deviceName
                text2.text = device.device.address

                return view
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        // ERROR CORREGIDO: No asignar null
        // listAdapter = null

        val header = requireActivity().layoutInflater.inflate(R.layout.device_list_header, null, false)
        listView.addHeaderView(header, null, false)
        setEmptyText("initializing...")
        (listView.emptyView as TextView).textSize = 18f
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_devices, menu)
        this.menu = menu

        if (bluetoothAdapter == null) {
            menu.findItem(R.id.bt_settings).isEnabled = false
            menu.findItem(R.id.ble_scan).isEnabled = false
        } else if (bluetoothAdapter?.isEnabled != true) {
            menu.findItem(R.id.ble_scan).isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().registerReceiver(discoveryBroadcastReceiver, discoveryIntentFilter)

        when {
            bluetoothAdapter == null -> {
                setEmptyText("<bluetooth LE not supported>")
            }
            bluetoothAdapter?.isEnabled != true -> {
                setEmptyText("<bluetooth is disabled>")
                menu?.let {
                    listItems.clear()
                    listAdapter.notifyDataSetChanged()
                    it.findItem(R.id.ble_scan).isEnabled = false
                }
            }
            else -> {
                setEmptyText("<use SCAN to refresh devices>")
                menu?.findItem(R.id.ble_scan)?.isEnabled = true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        requireActivity().unregisterReceiver(discoveryBroadcastReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        menu = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.ble_scan -> {
                startScan()
                true
            }
            R.id.ble_scan_stop -> {
                stopScan()
                true
            }
            R.id.bt_settings -> {
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startScan() {
        if (scanState != ScanState.NONE) return

        var nextScanState = ScanState.LE_SCAN

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (!BluetoothUtil.hasPermissions(this, requestBluetoothPermissionLauncherForStartScan)) {
                    return
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (requireActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    scanState = ScanState.NONE
                    AlertDialog.Builder(requireActivity()).apply {
                        setTitle(R.string.location_permission_title)
                        setMessage(R.string.location_permission_grant)
                        setPositiveButton(android.R.string.ok) { _, _ ->
                            requestLocationPermissionLauncherForStartScan.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        show()
                    }
                    return
                }

                val locationManager = requireActivity().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var locationEnabled = false

                try {
                    locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                } catch (ignored: Exception) {}

                try {
                    locationEnabled = locationEnabled || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                } catch (ignored: Exception) {}

                if (!locationEnabled) {
                    nextScanState = ScanState.DISCOVERY
                }
            }
        }

        scanState = nextScanState
        listItems.clear()
        listAdapter.notifyDataSetChanged()
        setEmptyText("<scanning...>")

        menu?.let {
            it.findItem(R.id.ble_scan).isVisible = false
            it.findItem(R.id.ble_scan_stop).isVisible = true
        }

        when (scanState) {
            ScanState.LE_SCAN -> {
                leScanStopHandler.postDelayed(leScanStopCallback, LE_SCAN_PERIOD)
                Thread({
                    bluetoothAdapter?.startLeScan(null, leScanCallback)
                }, "startLeScan").start()
            }
            else -> {
                bluetoothAdapter?.startDiscovery()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateScan(device: BluetoothDevice) {
        if (scanState == ScanState.NONE) return

        val device2 = BluetoothUtil.Device(device) // slow getName() only once
        val pos = Collections.binarySearch(listItems, device2)

        if (pos < 0) {
            listItems.add(-pos - 1, device2)
            listAdapter.notifyDataSetChanged()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanState == ScanState.NONE) return

        setEmptyText("<no bluetooth devices found>")
        menu?.let {
            it.findItem(R.id.ble_scan).isVisible = true
            it.findItem(R.id.ble_scan_stop).isVisible = false
        }

        when (scanState) {
            ScanState.LE_SCAN -> {
                leScanStopHandler.removeCallbacks(leScanStopCallback)
                bluetoothAdapter?.stopLeScan(leScanCallback)
            }
            ScanState.DISCOVERY -> {
                bluetoothAdapter?.cancelDiscovery()
            }
            else -> {
                // already canceled
            }
        }

        scanState = ScanState.NONE
    }

    // MÉTODO MODIFICADO: En lugar de navegar al TerminalFragment,
    // envía el resultado y cierra el fragment
    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        stopScan()
        val device = listItems[position - 1]

        // Crear bundle con datos del dispositivo seleccionado
        val result = Bundle().apply {
            putString(BUNDLE_KEY_DEVICE_ADDRESS, device.device.address)
            putString(BUNDLE_KEY_DEVICE_NAME, device.name ?: "<unnamed>")
        }

        // Enviar resultado al Activity padre
        setFragmentResult(REQUEST_KEY_DEVICE_SELECTED, result)

        // Cerrar el fragment (regresar al Activity principal)
        parentFragmentManager.popBackStack()
    }
}