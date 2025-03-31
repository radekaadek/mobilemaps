package pl.pw.graterenowa

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import android.preference.PreferenceManager
import android.widget.Toast
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
import org.osmdroid.views.MapController
import org.osmdroid.views.MapView
import pl.pw.graterenowa.data.BeaconData
import pl.pw.graterenowa.data.BeaconResponse

class MainActivity : AppCompatActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your
                // app.
                Toast.makeText(
                    this,
                    "Permission granted",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
            }
        }

    private var permissionsGranted = false
    private var bluetoothEnabled = false
    private var locationEnabled = false
    private var scanningBeacons = false
    private val beaconJsonFileNames = listOf(
        "beacons_gg0.txt",
        "beacons_gg1.txt",
        "beacons_gg2b1.txt",
        "beacons_gg3b2.txt",
        "beacons_gg3b3.txt",
        "beacons_gg4.txt",
        "beacons_gg_out.txt"
    )

    private val mapView: MapView by lazy {
        findViewById(R.id.mapView)
    }

    private val mapControl by lazy {
        mapView.controller
    }
    private var currentPosition: GeoPoint = GeoPoint(51.0, 21.0)

    private var beaconMap: Map<String, BeaconData>? = null

    private fun loadReferenceBeacons(): MutableList<BeaconResponse> {
        val assetManager = this.assets
        val gson = Gson()
        val beaconResponses = mutableListOf<BeaconResponse>()

        for (fileName in beaconJsonFileNames) {
            try {
                // Read the JSON file
                val inputStream = assetManager.open(fileName)
                val json = inputStream.bufferedReader().use { it.readText() }

                // Parse JSON into BeaconResponse and add to list
                val beaconResponse = gson.fromJson(json, BeaconResponse::class.java)
                beaconResponses.add(beaconResponse)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val beacons = mutableListOf<BeaconData>()
        beaconResponses.forEach { beaconResponse ->
            beaconResponse.items.forEach { beaconData ->
                beacons.add(beaconData)
            }
        }
        beaconMap = beacons.associateBy { it.beaconUid }
        return beaconResponses
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        loadReferenceBeacons()
        Log.d("MainActivity", "Loaded ${beaconMap?.size} beacons")
        mapView.setTileSource(TileSourceFactory.MAPNIK) // TODO: doesn't work
        mapControl.setCenter(currentPosition)
        mapControl.setZoom(20.0)
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart")
        findViewById<Button>(R.id.start).setOnClickListener {
            if (servicesTriggerCheck()) {
                startBeaconScanning()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause")
        mapView.onPause()
    }

    private fun showExplanation(title: String, explanation: String) {
        // show an explanation to the user that this app requires bluetooth
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(explanation)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
        alertDialog.show()
    }

    private fun checkBluetoothStatus() : Boolean {
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            showExplanation("Bluetooth not found on the device", "This app needs Bluetooth in order to scan beacons.")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
                showExplanation("Bluetooth must be enabled", "This app needs Bluetooth to be enabled in order to scan beacons.")
            return false
        }
        return true
    }

    private fun checkBluetoothON() : Boolean {
        val status = checkBluetoothStatus()
        bluetoothEnabled = status
        return status
    }

    private fun checkLocationON(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationON = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!locationON) {
            showExplanation("Location not enabled", "This app needs Location to be enabled in order to scan beacons.")
        }
        locationEnabled = locationON
        return locationON
    }

    private fun servicesTriggerCheck(): Boolean {
        return checkBluetoothON() && checkLocationON()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        bluetoothEnabled = true
                        Toast.makeText(context, "Bluetooth is ON", Toast.LENGTH_SHORT).show()
                        if (locationEnabled) {
                            startBeaconScanning()
                        }
                    }
                    else -> {
                        bluetoothEnabled = false
                        Toast.makeText(context, "Bluetooth is not ON", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
//                val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
//                val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

                if (locationEnabled) {
                    locationEnabled = true
                    if (bluetoothEnabled) {
                        startBeaconScanning()
                    }
                } else {
                    locationEnabled = false
                }
            }
        }
    }

    private fun runApp() {
        checkPermissions()
        if (!permissionsGranted) {
            showExplanation("Permissions not granted", "This app needs the Location and Bluetooth permissions in order to scan beacons.")
            return
        }

        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, btFilter)
        val locationFilter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        registerReceiver(locationReceiver, locationFilter)

        if (servicesTriggerCheck()) {
            startBeaconScanning()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
        mapView.onResume()
        runApp()
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy")
    }

    override fun onRestart() {
        super.onRestart()
        Log.d("MainActivity", "onRestart")
    }

    private fun checkPermissions(): Boolean {
        permissionsGranted = (checkLocationPermission() && checkBluetoothPermission())
        return permissionsGranted
    }

    private fun checkLocationPermission(): Boolean {
        return checkPermission(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "Location permission is required for scanning beacons"
        )
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkPermission(
                Manifest.permission.BLUETOOTH_SCAN,
                "Bluetooth scanning permission is required for scanning beacons"
            ) && checkPermission(
                Manifest.permission.BLUETOOTH_CONNECT,
                "Bluetooth connecting permission is required for scanning beacons"
            )
        } else {
            TODO("VERSION.SDK_INT < S")
            return false
        }
    }

    private fun checkPermission(permission: String, rationale: String): Boolean {
        when {
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is granted. Continue the action or workflow in your
                // app.
                return true
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                permission
            ) -> {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
                Toast.makeText(
                    this,
                    rationale,
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                requestPermissionLauncher.launch(permission)
                return ActivityCompat.checkSelfPermission(
                        this,
                        permission
                    ) != PackageManager.PERMISSION_DENIED
            }
        }
    }

    private fun calculatePosition(beaconLats: List<Double>,
                                  beaconLons: List<Double>,
                                  beaconDistances: List<Double>): GeoPoint {
        if (beaconLats.isEmpty() || beaconLons.isEmpty() || beaconDistances.isEmpty()) {
            return currentPosition
        }
        // Weighting beacons based on inverse distance (closer beacons matter more)
        var weightedLat = 0.0
        var weightedLon = 0.0
        var totalWeight = 0.0

        for (i in beaconLats.indices) {
            val weight = 1.0 / beaconDistances[i] // Closer beacons have higher weight
            weightedLat += beaconLats[i] * weight
            weightedLon += beaconLons[i] * weight
            totalWeight += weight
        }
        currentPosition = GeoPoint(weightedLat / totalWeight, weightedLon / totalWeight)
        return currentPosition
    }


    private fun startBeaconScanning() {
        if (scanningBeacons) {
            return
        }
        scanningBeacons = true
        Toast.makeText(this, "Scanning beacons...", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Permissions granted, scanning beacons...")
        val beaconManager =  BeaconManager.getInstanceForApplication(this)
        listOf(
            BeaconParser.EDDYSTONE_UID_LAYOUT,
            BeaconParser.EDDYSTONE_TLM_LAYOUT,
            BeaconParser.EDDYSTONE_URL_LAYOUT
        ).forEach {
            beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(it))
        }
        val region = Region("all-beacons-region", null, null, null)
        // Set up a Live Data observer so this Activity can get monitoring callbacks
        // observer will be called each time the monitored regionState changes (inside vs. outside region)
        beaconManager.getRegionViewModel(region).regionState.observe(this, monitoringObserver)
        beaconManager.startMonitoring(region)
        beaconManager.addRangeNotifier { beacons, _ ->
            val beaconCount = beacons.size
            // find the beacon by its id
            if (beaconCount > 0) {
                var beaconLons = mutableListOf<Double>()
                var beaconLats = mutableListOf<Double>()
                var beaconDistances = mutableListOf<Double>()
                for (beacon in beacons) {
                    val beaconData = beaconMap?.get(beacon.bluetoothAddress)
                    beaconLons.add(beaconData?.longitude ?: 0.0)
                    beaconLats.add(beaconData?.latitude ?: 0.0)
                    beaconDistances.add(beacon.distance)
                }
                val position = calculatePosition(beaconLats, beaconLons, beaconDistances)
                mapControl.setCenter(position)
                val num = beaconLats.size
                Log.d("MainActivity", "Beacons: $num")
                Log.d("MainActivity", "Position: $position")
            }
        }
        // observer will be called each time a new rangedBeacons is measured
        beaconManager.startRangingBeacons(region)
    }

    val monitoringObserver = Observer<Int> { state ->
        if (state == MonitorNotifier.INSIDE) {
            Log.d("MainActivity", "Detected beacons(s)")
        }
        else {
            Log.d("MainActivity", "Stopped detecteing beacons")
        }
    }
}
