package com.example.testeo

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.ArrayDeque
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager

/**
 * Actividad principal que integra ubicación, monitoreo de sensores vía Bluetooth
 * desde Arduino, y guardado de puntos en Firebase.
 */
class MainActivity : AppCompatActivity(), ServiceConnection, SerialListener, FragmentManager.OnBackStackChangedListener {

    // =============================================================================================
    // Vistas de la UI
    // =============================================================================================
    private lateinit var txtStatus: TextView
    private lateinit var txtDistancia: TextView
    private lateinit var txtHumedad: TextView
    private lateinit var txtNivelSonido: TextView
    private lateinit var btnDemo: Button
    private lateinit var btnGuardarPunto: Button
    private lateinit var mapsButton: Button
    private lateinit var btnConectarBluetooth: Button
    private lateinit var fragmentContainer: FrameLayout
    private lateinit var mainContent: ScrollView
    private lateinit var toolbar: Toolbar

    // =============================================================================================
    // Sistema de Alertas
    // =============================================================================================
    private val UMBRAL_SONIDO = 20000
    private val UMBRAL_HUMEDAD = 80.0
    private val UMBRAL_CAMBIO_DISTANCIA = 5.0

    private var canPlaySoundAlert = true
    private var canPlayHumidityAlert = true
    private var canPlayDistanceAlert = true

    private val SOUND_ALERT_COOLDOWN_MS = 5000L
    private val HUMIDITY_ALERT_COOLDOWN_MS = 5000L
    private val DISTANCE_ALERT_COOLDOWN_MS = 5000L

    private val handler = Handler(Looper.getMainLooper())
    private var previousDistance: Double? = null

    // =============================================================================================
    // Ubicación
    // =============================================================================================
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            getLastLocation()
        } else {
            Toast.makeText(this, "Permiso de ubicación denegado.", Toast.LENGTH_SHORT).show()
        }
    }

    // =============================================================================================
    // Firebase
    // =============================================================================================
    private lateinit var database: DatabaseReference

    // =============================================================================================
    // Bluetooth BLE
    // =============================================================================================
    private var serialService: SerialService? = null
    private var bluetoothConnected = false
    private var bluetoothDeviceAddress: String? = null
    private var bluetoothDeviceName: String? = null

    // =============================================================================================
    // Ciclo de Vida
    // =============================================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupToolbar()
        initializeServices()
        setupButtonListeners()
        requestLocationPermissions()
        setupDevicesFragmentResultListener()

        txtStatus.text = "Aplicación iniciada. Conecte dispositivo Bluetooth para recibir datos de sensores."
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SerialService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        serialService?.detach()
        unbindService(this)
    }

    // =============================================================================================
    // Inicialización
    // =============================================================================================
    private fun initializeViews() {
        txtStatus = findViewById(R.id.txtStatus)
        txtDistancia = findViewById(R.id.txtDistancia)
        txtHumedad = findViewById(R.id.txtHumedad)
        txtNivelSonido = findViewById(R.id.txtNivelSonido)
        btnDemo = findViewById(R.id.btnDemo)
        btnGuardarPunto = findViewById(R.id.btnGuardarPunto)
        mapsButton = findViewById(R.id.mapsButton)
        btnConectarBluetooth = findViewById(R.id.btnConectarBluetooth)
        fragmentContainer = findViewById(R.id.fragment_container)
        mainContent = findViewById(R.id.main_content)
        toolbar = findViewById(R.id.toolbar)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportFragmentManager.addOnBackStackChangedListener(this)
        // Inicialmente ocultar el toolbar
        toolbar.visibility = View.GONE
    }

    private fun initializeServices() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        database = Firebase.database.reference
    }

    private fun setupButtonListeners() {
        btnDemo.setOnClickListener { showDemoDialog() }
        btnGuardarPunto.setOnClickListener { savePointToFirebase() }
        mapsButton.setOnClickListener { abrirMaps() }
        btnConectarBluetooth.setOnClickListener { showDevicesFragment() }
    }

    // =============================================================================================
    // Manejo del Toolbar y Back Stack
    // =============================================================================================
    override fun onBackStackChanged() {
        val hasFragments = supportFragmentManager.backStackEntryCount > 0
        supportActionBar?.setDisplayHomeAsUpEnabled(hasFragments)

        // Mostrar/ocultar toolbar según si hay fragmentos
        toolbar.visibility = if (hasFragments) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            // Si hay fragmentos en el back stack, hacer pop
            supportFragmentManager.popBackStack()
            // Mostrar el contenido principal y ocultar el fragmentContainer
            fragmentContainer.visibility = View.GONE
            mainContent.visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    // =============================================================================================
    // Demo de Alertas
    // =============================================================================================
    private fun showDemoDialog() {
        val opciones = arrayOf("Alerta sonido", "Alerta distancia", "Alerta humedad", "Cerrar")
        AlertDialog.Builder(this)
            .setTitle("Demo de alertas")
            .setItems(opciones) { dialog, which ->
                when (which) {
                    0 -> playSoundAlert()
                    1 -> playDistanceAlert()
                    2 -> playHumidityAlert()
                    3 -> dialog.dismiss()
                }
            }
            .show()
    }

    // =============================================================================================
    // Sistema de Alertas de Audio
    // =============================================================================================
    private fun playSoundAlert() {
        try {
            val mp = MediaPlayer.create(this, R.raw.alerta_sonido)
            mp?.let {
                it.setOnCompletionListener { player -> player.release() }
                it.start()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error reproduciendo alerta de sonido", e)
            Toast.makeText(this, "Error alerta sonido", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playDistanceAlert() {
        try {
            val mp = MediaPlayer.create(this, R.raw.alarma_distancia)
            mp?.let {
                it.setOnCompletionListener { player -> player.release() }
                it.start()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error reproduciendo alerta de distancia", e)
            Toast.makeText(this, "Error alerta distancia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playHumidityAlert() {
        try {
            val mp = MediaPlayer.create(this, R.raw.alarma_humedad)
            mp?.let {
                it.setOnCompletionListener { player -> player.release() }
                it.start()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error reproduciendo alerta de humedad", e)
            Toast.makeText(this, "Error alerta humedad", Toast.LENGTH_SHORT).show()
        }
    }

    // =============================================================================================
    // Análisis de Sensores y Alertas Automáticas
    // =============================================================================================
    private fun checkDistanceChange(currentDistance: Double) {
        val prev = previousDistance
        if (prev == null) {
            previousDistance = currentDistance
            return
        }
        val diff = kotlin.math.abs(currentDistance - prev)
        if (diff > UMBRAL_CAMBIO_DISTANCIA && canPlayDistanceAlert) {
            playDistanceAlert()
            canPlayDistanceAlert = false
            handler.postDelayed({ canPlayDistanceAlert = true }, DISTANCE_ALERT_COOLDOWN_MS)
            Log.d("MainActivity", "Alerta de distancia activada. Cambio: $diff cm")
        }
        previousDistance = currentDistance
    }

    private fun checkHumidityThreshold(humedad: Double) {
        if (humedad > UMBRAL_HUMEDAD && canPlayHumidityAlert) {
            playHumidityAlert()
            canPlayHumidityAlert = false
            handler.postDelayed({ canPlayHumidityAlert = true }, HUMIDITY_ALERT_COOLDOWN_MS)
            Log.d("MainActivity", "Alerta de humedad activada. Nivel: $humedad%")
        }
    }

    private fun checkSoundThreshold(sonido: Int) {
        if (sonido > UMBRAL_SONIDO && canPlaySoundAlert) {
            playSoundAlert()
            canPlaySoundAlert = false
            handler.postDelayed({ canPlaySoundAlert = true }, SOUND_ALERT_COOLDOWN_MS)
            Log.d("MainActivity", "Alerta de sonido activada. Nivel: $sonido")
        }
    }

    // =============================================================================================
    // Ubicación
    // =============================================================================================
    private fun requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            getLastLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        lastLocation = location
                        txtStatus.text = "Ubicación: Lat ${location.latitude}, Lon ${location.longitude}"
                    } else {
                        txtStatus.text = "Ubicación no disponible."
                    }
                }
                .addOnFailureListener { e ->
                    txtStatus.text = "Error al obtener ubicación: ${e.message}"
                    Log.e("MainActivity", "Error getting location", e)
                }
        }
    }

    // =============================================================================================
    // Firebase - Guardar Puntos
    // =============================================================================================
    private fun savePointToFirebase() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissions()
            return
        }

        txtStatus.text = "Obteniendo ubicación…"
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val opciones = arrayOf("Escaleras", "Baños", "Otro")
                    AlertDialog.Builder(this)
                        .setTitle("Selecciona tipo de punto")
                        .setItems(opciones) { _, which ->
                            val tipo = opciones[which]
                            guardarPuntoConTipo(lat, lon, tipo)
                        }
                        .show()
                } else {
                    txtStatus.text = "No se pudo obtener la ubicación."
                    Toast.makeText(this, "Ubicación no disponible.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                txtStatus.text = "Error ubicando: ${e.message}"
                Toast.makeText(this, "Error al obtener ubicación: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun guardarPuntoConTipo(lat: Double, lon: Double, tipo: String) {
        txtStatus.text = "Guardando punto..."

        val punto = mapOf(
            "lat" to lat,
            "lon" to lon,
            "label" to tipo
        )

        database.child("puntos")
            .child(tipo)
            .push()
            .setValue(punto)
            .addOnSuccessListener {
                txtStatus.text = "Punto guardado con éxito: $tipo"
                Toast.makeText(this, "Punto guardado: $tipo", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                txtStatus.text = "Error al guardar: ${e.message}"
                Toast.makeText(this, "Error al guardar punto: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("MainActivity", "Error guardando punto", e)
            }
    }

    // =============================================================================================
    // Google Maps
    // =============================================================================================
    private fun abrirMaps() {
        val uri = lastLocation?.let {
            Uri.parse("geo:${it.latitude},${it.longitude}?q=${it.latitude},${it.longitude}(Mi Ubicación)")
        } ?: Uri.parse("geo:0,0?q=Arequipa")

        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "No se encontró la aplicación de Google Maps.", Toast.LENGTH_SHORT).show()
        }
    }

    // =============================================================================================
    // Manejo de DevicesFragment
    // =============================================================================================
    private fun showDevicesFragment() {
        mainContent.visibility = View.GONE
        fragmentContainer.visibility = View.VISIBLE

        val devicesFragment = DevicesFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, devicesFragment, "devices")
            .addToBackStack(null)
            .commit()
    }

    private fun setupDevicesFragmentResultListener() {
        supportFragmentManager.setFragmentResultListener(
            DevicesFragment.REQUEST_KEY_DEVICE_SELECTED,
            this
        ) { requestKey, bundle ->
            val deviceAddress = bundle.getString(DevicesFragment.BUNDLE_KEY_DEVICE_ADDRESS)
            val deviceName = bundle.getString(DevicesFragment.BUNDLE_KEY_DEVICE_NAME)

            fragmentContainer.visibility = View.GONE
            mainContent.visibility = View.VISIBLE

            if (deviceAddress != null) {
                bluetoothDeviceAddress = deviceAddress
                bluetoothDeviceName = deviceName
                txtStatus.text = "Dispositivo seleccionado: $deviceName ($deviceAddress)"
                connectToSelectedBluetoothDevice(deviceAddress)
            } else {
                txtStatus.text = "No se seleccionó ningún dispositivo Bluetooth."
            }
        }
    }

    // =============================================================================================
    // Conexión Bluetooth
    // =============================================================================================
    private fun connectToSelectedBluetoothDevice(address: String) {
        if (serialService == null) {
            Toast.makeText(this, "Servicio Bluetooth no disponible.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter == null) {
                Toast.makeText(this, "Bluetooth no disponible.", Toast.LENGTH_SHORT).show()
                return
            }

            val device = bluetoothAdapter.getRemoteDevice(address)
            val serialSocket = SerialSocket(applicationContext, device)
            serialService?.connect(serialSocket)
            txtStatus.text = "Intentando conectar a ${bluetoothDeviceName ?: address}..."
            bluetoothConnected = true
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnectBluetooth() {
        serialService?.disconnect()
        bluetoothConnected = false
        bluetoothDeviceAddress = null
        bluetoothDeviceName = null
        txtStatus.text = "Bluetooth desconectado."
        Toast.makeText(this, "Dispositivo Bluetooth desconectado.", Toast.LENGTH_SHORT).show()
    }

    // =============================================================================================
    // ServiceConnection Implementation
    // =============================================================================================
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        serialService = (service as SerialService.SerialBinder).getService()
        serialService?.attach(this)
        txtStatus.text = "Servicio Bluetooth conectado."
        if (bluetoothDeviceAddress != null && !bluetoothConnected) {
            connectToSelectedBluetoothDevice(bluetoothDeviceAddress!!)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        serialService = null
        bluetoothConnected = false
        txtStatus.text = "Servicio Bluetooth desconectado."
    }

    // =============================================================================================
    // SerialListener Implementation
    // =============================================================================================
    override fun onSerialConnect() {
        runOnUiThread {
            txtStatus.text = "Conectado a ${bluetoothDeviceName ?: "dispositivo Bluetooth"}."
            Toast.makeText(this, "Conectado a ${bluetoothDeviceName ?: "dispositivo Bluetooth"}", Toast.LENGTH_SHORT).show()
            bluetoothConnected = true
        }
    }

    override fun onSerialConnectError(e: Exception) {
        runOnUiThread {
            bluetoothConnected = false
            txtStatus.text = "Error de conexión Bluetooth: ${e.message}"
            Toast.makeText(this, "Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "Error de conexión Bluetooth", e)
            disconnectBluetooth()
        }
    }

    override fun onSerialRead(data: ByteArray) {
        val receivedString = String(data, Charsets.UTF_8).trim()
        Log.d("MainActivity", "Datos BLE recibidos: $receivedString")
        updateSensorDataFromBluetooth(receivedString)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        runOnUiThread {
            for (data in datas) {
                val receivedString = String(data, Charsets.UTF_8).trim()
                Log.d("MainActivity", "Datos BLE en cola recibidos: $receivedString")
                updateSensorDataFromBluetooth(receivedString)
            }
        }
    }

    override fun onSerialIoError(e: Exception) {
        runOnUiThread {
            bluetoothConnected = false
            txtStatus.text = "Error de I/O Bluetooth: ${e.message}"
            Toast.makeText(this, "Error de I/O: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "Error de I/O Bluetooth", e)
            disconnectBluetooth()
        }
    }

    // =============================================================================================
    // Procesamiento de Datos de Sensores
    // =============================================================================================
    private fun updateSensorDataFromBluetooth(dataString: String) {
        val parts = dataString.split(',')

        var distance: Double? = null
        var humidity: Double? = null
        var sound: Int? = null

        for (part in parts) {
            val keyValue = part.split(':')
            if (keyValue.size == 2) {
                val key = keyValue[0].trim()
                val value = keyValue[1].trim()
                try {
                    when (key) {
                        "D" -> distance = value.toDouble()
                        "H" -> humidity = value.toDouble()
                        "S" -> sound = value.toInt()
                    }
                } catch (e: NumberFormatException) {
                    Log.e("MainActivity", "Error al parsear valor: $part", e)
                }
            }
        }

        // Actualizar UI y verificar alertas
        runOnUiThread {
            distance?.let {
                txtDistancia.text = "Distancia: %.2f cm".format(it)
                checkDistanceChange(it)
            }
            humidity?.let {
                txtHumedad.text = "Humedad: %.1f %%".format(it)
                checkHumidityThreshold(it)
            }
            sound?.let { soundLevel ->
                txtNivelSonido.text = "Nivel sonido: $soundLevel"
                checkSoundThreshold(soundLevel)
            }
        }
    }
}