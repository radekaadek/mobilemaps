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
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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
import org.altbeacon.beacon.Region
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import pl.pw.graterenowa.data.BeaconData
import pl.pw.graterenowa.data.BeaconResponse
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import org.osmdroid.views.overlay.Polyline

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
    private val region by lazy { Region("all-beacons-region", null, null, null) }
    private val monitoringObserver = Observer<Int> { state ->
    }
    private var beaconIdtoMarker: MutableMap<String, Marker> = mutableMapOf()
    private var greenBeaconIds: MutableSet<String> = mutableSetOf() // Performance
    private var redBeaconIds: MutableSet<String> = mutableSetOf() // Performance
    private var linesToUser: MutableList<Polyline> = mutableListOf()
    private var circlesAroundUser: MutableList<Polyline> = mutableListOf()
    private var currentFloor: Int? = null

    fun getScaledDrawable(drawableId: Int, width: Int, height: Int): Drawable? {
        val originalDrawable = ContextCompat.getDrawable(this, drawableId)
        return originalDrawable?.let { drawable ->
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.toDrawable(resources)
        }
    }

    fun drawBeaconsOnMap() {
        for ((beaconId, beaconData) in beaconMap) {
            val marker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            val originalDrawable = ContextCompat.getDrawable(this, R.drawable.greydot)
            val scaledDrawable = originalDrawable?.let { drawable ->
                val bitmap = createBitmap(10, 10)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.toDrawable(resources)
            }
            marker.icon = scaledDrawable
            marker.position = GeoPoint(beaconData.latitude, beaconData.longitude)
            marker.title = beaconId
            mapView.overlays.add(marker)
            beaconIdtoMarker[beaconId] = marker
        }
    }

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
        drawBeaconsOnMap()
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
        if (scanningBeacons) {
            Toast.makeText(this, "Beacon scanning is already in progress", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Beacon scanning is already in progress")
            return
        }
        if (!checkPermissions()) {
            Toast.makeText(this, "Some permissions were not granted", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Some permissions were not granted")
            return
        }
        if (!servicesTriggerCheck()) {
            Log.d("MainActivity", "Services not triggered")
            return
        }
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

        beaconManager?.getRegionViewModel(region)?.regionState?.observe(this, monitoringObserver)
        beaconManager?.startMonitoring(region)

        if (positionMarker == null) {
            positionMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(positionMarker)
            val originalDrawable = ContextCompat.getDrawable(this, R.drawable.miku)
            val scaledDrawable = originalDrawable?.let { drawable ->
                val bitmap = createBitmap(75, 100)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.toDrawable(resources)
            }
            positionMarker?.icon = scaledDrawable
        }
        positionMarker?.let {
            it.position = currentPosition
            it.title = "Current Position"
        }

        mapView.invalidate()
        beaconManager?.addRangeNotifier { beacons, _ ->
            if (beacons.isNotEmpty()) {
                updatePosition(beacons)
            }
            for (beaconId in greenBeaconIds) {
                val marker = beaconIdtoMarker[beaconId]
                var icon = R.drawable.greydot
                if (beaconMap[beaconId]?.floorId == currentFloor) {
                    icon = R.drawable.reddot
                }
                marker?.icon = getScaledDrawable(icon, 10, 10)

                for (polyline in linesToUser) {
                    mapView.overlays.remove(polyline)
                }
                for (polyline in circlesAroundUser) {
                    mapView.overlays.remove(polyline)
                }
            }
            greenBeaconIds.clear()
            linesToUser.clear()

            var floorDetected: Int? = null
            for (beacon in beacons) {
                val beaconId = beacon.bluetoothAddress
                val marker = beaconIdtoMarker[beaconId]
                marker?.icon = getScaledDrawable(R.drawable.greendot, 30, 30)
                greenBeaconIds.add(beaconId)
                val beaconData = beaconMap[beaconId]
                if (beaconData != null) {
                    val rssi = beacon.rssi
                    Log.d("MainActivity", "RSSI: $rssi")
                    val lineToUser = Polyline().apply {
                        addPoint(GeoPoint(beaconData.latitude, beaconData.longitude))
                        addPoint(currentPosition)
                    }
                    lineToUser.outlinePaint.apply {
                        color = android.graphics.Color.RED
                        strokeWidth = (rssi.toFloat() + 110) * 0.5f
                    }
                    mapView.overlays.add(lineToUser)
                    linesToUser.add(lineToUser)

                    // Add circle
                    val circle = getCircle(GeoPoint(beaconData.latitude, beaconData.longitude), (rssi.toFloat() + 110.0))
                    mapView.overlays.add(circle)
                    circlesAroundUser.add(circle)

                    floorDetected = beaconData.floorId
                }
            }
            if (floorDetected != currentFloor) {
                val t = Toast(this)
                t.setText("Changing floor to: ${floorDetected}")
                t.show()

                // turn all red markers into grey
                for (redId in redBeaconIds) {
                    val marker = beaconIdtoMarker[redId]
                    marker?.icon = getScaledDrawable(R.drawable.greydot, 10, 10)
                }
                redBeaconIds.clear()
                for ((id, data) in beaconMap) {
                    if (data.floorId == floorDetected && greenBeaconIds.contains(id).not()) {
                        val marker = beaconIdtoMarker[id]
                        marker?.icon = getScaledDrawable(R.drawable.reddot, 10, 10)
                        redBeaconIds.add(id)
                    }
                }
                currentFloor = floorDetected
            }
        }
        beaconManager?.startRangingBeacons(region)
    }

    private fun getCircle(center: GeoPoint, radius: Double): Polyline {
        val l = Polyline()
        val points = (0..360 step 20).map { i ->
            val angleInRadians = Math.toRadians(i.toDouble())
            GeoPoint(
                center.latitude + radius * Math.cos(angleInRadians),
                center.longitude + radius * Math.sin(angleInRadians)
            )
        }
        for (p in points) {
            l.addPoint(p)
        }
        return l
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
        val windowWasOpened = positionMarker?.isInfoWindowShown
        positionMarker?.apply {
            closeInfoWindow()
            position = currentPosition
            title = "Last Known Position"
            if (windowWasOpened == true)
                showInfoWindow()
        }
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
        }
    }

    private fun calculatePosition(beaconLats: List<Double>, beaconLons: List<Double>, beaconDistances: List<Double>): GeoPoint {
        var weightedLat = 0.0
        var weightedLon = 0.0
        var totalWeight = 0.0

        if (beaconLats.size < 2)
            return currentPosition

        for (i in beaconLats.indices) {
            val weight = 1.0 / beaconDistances[i]
            weightedLat += beaconLats[i] * weight
            weightedLon += beaconLons[i] * weight
            totalWeight += weight
        }
        return GeoPoint(weightedLat / totalWeight, weightedLon / totalWeight)
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