package pl.pw.graterenowa

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.google.gson.Gson
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.Region
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import pl.pw.graterenowa.data.BeaconData
import pl.pw.graterenowa.data.BeaconResponse

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var positionMarker: Marker? = null
    private var beaconMap: Map<String, BeaconData> = emptyMap()
    private var currentPosition = GeoPoint(52.2204685, 21.0101522)
    private var scanningBeacons = false
    private var bluetoothEnabled = false
    private var locationEnabled = false
    private var beaconManager: BeaconManager? = null

    private val bluetoothReceiver by lazy { BluetoothStateReceiver() }
    private val locationReceiver by lazy { LocationStateReceiver() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeMap()
        loadReferenceBeacons()
        setupListeners()
        registerReceivers()
        // Enable multi-touch controls (zoom and rotation)
        mapView.setMultiTouchControls(true)
        // Optional: Enable rotation gestures
        val rotationGestureOverlay = RotationGestureOverlay(mapView)
        rotationGestureOverlay.isEnabled = true
        mapView.overlays.add(rotationGestureOverlay)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceivers()
    }

    private fun initializeMap() {
        Configuration.getInstance().load(applicationContext, getPreferences(MODE_PRIVATE))
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.controller.apply {
            setCenter(currentPosition)
            setZoom(19.5)
        }
    }

    private fun loadReferenceBeacons() {
        val beaconResponses = beaconJsonFileNames.flatMap { fileName ->
            try {
                assets.open(fileName).bufferedReader().use { reader ->
                    Gson().fromJson(reader.readText(), BeaconResponse::class.java).items
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading $fileName", e)
                emptyList()
            }
        }
        beaconMap = beaconResponses.associateBy { it.beaconUid }
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.start).setOnClickListener {
            if (checkPermissions() && servicesTriggerCheck()) {
                startBeaconScanning()
            }
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            if (scanningBeacons) {
                stopBeaconScanning()
            }
        }
    }

    private fun registerReceivers() {
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
    }

    private fun unregisterReceivers() {
        unregisterReceiver(bluetoothReceiver)
        unregisterReceiver(locationReceiver)
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (!checkLocationPermission()) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!checkBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 101)
            return false
        }
        return true
    }

    private fun checkLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun checkBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Prior to S, Bluetooth permissions are handled differently
        }

    private fun servicesTriggerCheck(): Boolean = checkBluetoothON() && checkLocationON()

    private fun checkBluetoothON(): Boolean {
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        bluetoothEnabled = bluetoothAdapter?.isEnabled == true
        if (!bluetoothEnabled) showExplanation("Bluetooth must be enabled", "This app needs Bluetooth to be enabled.")
        return bluetoothEnabled
    }

    private fun checkLocationON(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!locationEnabled) showExplanation("Location must be enabled", "This app needs Location to be enabled.")
        return locationEnabled
    }

    private fun showExplanation(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun startBeaconScanning() {
        if (scanningBeacons) return
        if (!checkPermissions()) return
        if (!servicesTriggerCheck()) return
        scanningBeacons = true

        Toast.makeText(this, "Scanning beacons...", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Scanning beacons...")

        if (beaconManager == null)
            beaconManager = BeaconManager.getInstanceForApplication(this)
        beaconManager?.beaconParsers?.addAll(listOf(
            BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_UID_LAYOUT),
            BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_TLM_LAYOUT),
            BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_URL_LAYOUT)
        ))

        val region = Region("all-beacons-region", null, null, null)
        beaconManager?.getRegionViewModel(region)?.regionState?.observe(this, monitoringObserver)
        beaconManager?.startMonitoring(region)

        if (positionMarker == null) {
            positionMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Current Position"
            }
            mapView.overlays.add(positionMarker)
        }

        positionMarker?.let {
            it.position = currentPosition
        }

        mapView.invalidate()
        beaconManager?.addRangeNotifier { beacons, _ ->
            if (beacons.isNotEmpty()) {
                updatePosition(beacons)
            }
        }
        beaconManager?.startRangingBeacons(region)
    }

    private fun stopBeaconScanning() {
        if (!scanningBeacons) return
        scanningBeacons = false
        Toast.makeText(this, "Stopped scanning beacons", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Stopped scanning beacons")

        if (beaconManager == null)
            beaconManager = BeaconManager.getInstanceForApplication(this)
        val region = Region("all-beacons-region", null, null, null)
        beaconManager?.stopRangingBeacons(region)
        beaconManager?.stopMonitoring(region)
        positionMarker?.title = "Position before beacon scanning stop"
//        positionMarker?.let {
//            mapView.overlays.remove(it)
//            positionMarker = null
//        }
        mapView.invalidate()
    }

    private fun updatePosition(beacons: Collection<Beacon>) {
        val beaconLats = mutableListOf<Double>()
        val beaconLons = mutableListOf<Double>()
        val beaconDistances = mutableListOf<Double>()

        beacons.forEach { beacon ->
            beaconMap[beacon.bluetoothAddress]?.let { beaconData ->
                beaconLons.add(beaconData.longitude)
                beaconLats.add(beaconData.latitude)
                beaconDistances.add(beacon.distance)
            }
        }

        if (beaconLats.isNotEmpty()) {
            currentPosition = calculatePosition(beaconLats, beaconLons, beaconDistances)
            positionMarker?.position = currentPosition
            mapView.invalidate()
            Log.d("MainActivity", "Detected ${beaconLats.size} beacons, position: $currentPosition")
        }
    }

    private fun calculatePosition(beaconLats: List<Double>, beaconLons: List<Double>, beaconDistances: List<Double>): GeoPoint {
        var weightedLat = 0.0
        var weightedLon = 0.0
        var totalWeight = 0.0

        for (i in beaconLats.indices) {
            val weight = 1.0 / beaconDistances[i]
            weightedLat += beaconLats[i] * weight
            weightedLon += beaconLons[i] * weight
            totalWeight += weight
        }
        return GeoPoint(weightedLat / totalWeight, weightedLon / totalWeight)
    }

    private val monitoringObserver = Observer<Int> { state ->
        Log.d("MainActivity", if (state == MonitorNotifier.INSIDE) "Detected beacons(s)" else "Stopped detecting beacons")
    }

    inner class BluetoothStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                bluetoothEnabled = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
            }
        }
    }

    inner class LocationStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
                locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }
    }

    companion object {
        private val beaconJsonFileNames = listOf(
            "beacons_gg0.txt",
            "beacons_gg1.txt",
            "beacons_gg2b1.txt",
            "beacons_gg3b2.txt",
            "beacons_gg3b3.txt",
            "beacons_gg4.txt",
            "beacons_gg_out.txt"
        )
    }
}