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
import android.graphics.Color // Use explicit import for Color
import android.graphics.drawable.Drawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
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
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import pl.pw.graterenowa.data.BeaconData
import pl.pw.graterenowa.data.BeaconResponse
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    // region Properties

    // --- Views ---
    private lateinit var mapView: MapView

    // --- Map Overlays ---
    private var positionMarker: Marker? = null
    private var beaconIdtoMarker: MutableMap<String, Marker> = mutableMapOf()
    private var linesToUser: MutableList<Polyline> = mutableListOf()
    private var circlesAroundUser: MutableList<Polyline> = mutableListOf()
    private var rotationGestureOverlay: RotationGestureOverlay? = null // Hold reference if needed later

    // --- Beacon Data ---
    private var beaconMap: Map<String, BeaconData> = emptyMap()
    private var greenBeaconIds: MutableSet<String> = mutableSetOf() // Currently detected beacons
    private var redBeaconIds: MutableSet<String> = mutableSetOf() // Beacons on the current floor (not detected)

    // --- State ---
    private var currentPosition = GeoPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE) // Use constants
    private var scanningBeacons = false
    private var bluetoothEnabled = false
    private var locationEnabled = false
    private var currentFloor: Int? = null

    // --- Managers ---
    private var beaconManager: BeaconManager? = null

    // --- Receivers ---
    private val bluetoothReceiver by lazy { BluetoothStateReceiver() }
    private val locationReceiver by lazy { LocationStateReceiver() }

    // --- Beacon Library Objects ---
    private val region by lazy { Region(BEACON_REGION_ID, null, null, null) }
    // Observer for monitoring state changes (entering/exiting region), currently unused but kept for potential future use.
    private val monitoringObserver = Observer<Int> { state ->
        // Log.d(TAG, "Region state changed: $state") // Example usage
    }
    // endregion

    //region Lifecycle Methods
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load osmdroid configuration BEFORE setContentView
        Configuration.getInstance().load(applicationContext, getPreferences(MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        initializeMap()
        loadReferenceBeacons()
        drawBeaconsOnMap() // Draw static beacons after loading
        setupListeners()
        registerReceivers()
        setupMapControls()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // Re-check state in case it changed while paused
        checkBluetoothON()
        checkLocationON()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceivers()
        // Consider stopping scanning if activity is destroyed while scanning
        if (scanningBeacons) {
            stopBeaconScanningInternal()
        }
        // Release map resources if necessary (osmdroid handles much of this)
        mapView.onDetach()
    }
    //endregion

    //region Initialization
    private fun initializeMap() {
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.controller.apply {
            setCenter(currentPosition)
            setZoom(INITIAL_ZOOM_LEVEL)
        }
    }

    private fun loadReferenceBeacons() {
        beaconMap = beaconJsonFileNames.flatMap { fileName ->
            try {
                assets.open(fileName).bufferedReader().use { reader ->
                    Gson().fromJson(reader.readText(), BeaconResponse::class.java).items
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading reference beacons from $fileName", e)
                Toast.makeText(this, "Error loading beacon data: $fileName", Toast.LENGTH_LONG).show()
                emptyList()
            }
        }.associateBy { it.beaconUid } // Use UID as the key
        Log.d(TAG, "Loaded ${beaconMap.size} reference beacons.")
    }
    //endregion

    //region UI Setup
    private fun setupListeners() {
        findViewById<Button>(R.id.start).setOnClickListener {
            startBeaconScanning()
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            stopBeaconScanning()
        }
    }

    private fun setupMapControls() {
        // Enable multi-touch controls (zoom and rotation)
        mapView.setMultiTouchControls(true)

        // Enable rotation gestures
        rotationGestureOverlay = RotationGestureOverlay(mapView).apply {
            isEnabled = true
        }
        mapView.overlays.add(rotationGestureOverlay)
    }

    private fun drawBeaconsOnMap() {
        val defaultIcon = getScaledDrawable(R.drawable.greydot, MARKER_DIMENSIONS_GREY.first, MARKER_DIMENSIONS_GREY.second)
        if (defaultIcon == null) {
            Log.e(TAG, "Failed to create default beacon icon.")
            // Potentially show an error or use a fallback
        }

        for ((beaconId, beaconData) in beaconMap) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(beaconData.latitude, beaconData.longitude)
                title = beaconId // Or potentially a more user-friendly name if available
                icon = defaultIcon ?: ContextCompat.getDrawable(this@MainActivity, R.drawable.greydot) // Fallback
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                // Optionally add more info for info window
                // snippet = "Floor: ${beaconData.floorId}"
                // subDescription = "Lat: ${beaconData.latitude}, Lon: ${beaconData.longitude}"
            }
            mapView.overlays.add(marker)
            beaconIdtoMarker[beaconId] = marker
        }
        mapView.invalidate() // Redraw map with new markers
    }

    private fun setupPositionMarker() {
        if (positionMarker == null) {
            positionMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                // Use a dedicated icon for the user's position
                icon = getScaledDrawable(R.drawable.miku, MARKER_DIMENSIONS_USER.first, MARKER_DIMENSIONS_USER.second)
                   ?: ContextCompat.getDrawable(this@MainActivity, R.drawable.miku) // Fallback
                title = "Current Position" // Initial title
            }
            mapView.overlays.add(positionMarker)
        }
        // Ensure marker is at the current position when starting
        positionMarker?.position = currentPosition
        mapView.invalidate()
    }
    //endregion

    //region Core Logic - Beacon Scanning
    private fun startBeaconScanning() {
        if (scanningBeacons) {
            Toast.makeText(this, "Scanning already in progress", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Attempted to start scanning, but already scanning.")
            return
        }
        // Check permissions and required services first
        if (!checkPermissions()) {
            Log.d(TAG, "Permissions not granted. Requesting...")
            // Request is handled by checkPermissions calling requestPermissions
            return
        }
        if (!servicesTriggerCheck()) {
            Log.d(TAG, "Bluetooth or Location services are not enabled.")
            // Explanation dialogs are shown by servicesTriggerCheck
            return
        }

        Log.d(TAG, "Starting beacon scanning...")
        scanningBeacons = true
        Toast.makeText(this, "Scanning beacons...", Toast.LENGTH_SHORT).show()

        // Initialize BeaconManager if needed
        if (beaconManager == null) {
            beaconManager = BeaconManager.getInstanceForApplication(this).apply {
                // Add parsers for different beacon types (Eddystone UID/TLM/URL are common)
                // Adjust based on the beacons you expect to encounter
                beaconParsers.clear() // Clear existing parsers first
                beaconParsers.addAll(listOf(
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_UID_LAYOUT),
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_TLM_LAYOUT),
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_URL_LAYOUT),
                    // Add iBeacon parser if needed:
                    // BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24")
                ))
                // Optional: Adjust scan periods for battery/performance trade-off
                // setForegroundScanPeriod(1100L) // Scan for 1.1 seconds
                // setForegroundBetweenScanPeriod(0L) // Scan continuously
            }
        }

        // Setup user position marker on the map
        setupPositionMarker()

        // Start monitoring and ranging
        beaconManager?.apply {
            getRegionViewModel(region).regionState.observe(this@MainActivity, monitoringObserver)
            addRangeNotifier { beacons, _ ->
                // Process detected beacons on the main thread
                runOnUiThread { handleBeaconRangingUpdate(beacons) }
            }
            try {
                startMonitoring(region)
                startRangingBeacons(region)
                Log.d(TAG, "Beacon monitoring and ranging started for region: $region")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting beacon scanning", e)
                Toast.makeText(this@MainActivity, "Error starting scanning", Toast.LENGTH_SHORT).show()
                scanningBeacons = false // Reset state on error
            }
        }
    }

    private fun stopBeaconScanning() {
        if (!scanningBeacons) {
            Log.d(TAG, "Attempted to stop scanning, but not currently scanning.")
            return
        }
        Log.d(TAG, "Stopping beacon scanning...")
        stopBeaconScanningInternal()
        Toast.makeText(this, "Stopped scanning beacons", Toast.LENGTH_SHORT).show()

        // Update position marker state after stopping
        val windowWasOpened = positionMarker?.isInfoWindowShown ?: false
        positionMarker?.apply {
            closeInfoWindow()
            title = "Last Known Position" // Update title
            // Optionally, change icon to indicate inactive state
            if (windowWasOpened) {
                showInfoWindow() // Re-open info window if it was open before
            }
        }
        // Clear dynamic overlays (lines, circles, beacon highlights)
        resetPreviousBeaconMarkers() // Turn highlights back to default
        clearLinesAndCircles()
        mapView.invalidate() // Redraw map
    }

    // Internal function to handle the BeaconManager stop logic
    private fun stopBeaconScanningInternal() {
        scanningBeacons = false
        try {
            beaconManager?.apply {
                if (getRegionViewModel(region).regionState.hasObservers()) {
                    getRegionViewModel(region).regionState.removeObserver(monitoringObserver)
                }
                stopRangingBeacons(region)
                stopMonitoring(region)
                removeAllRangeNotifiers() // Important to remove notifiers
                Log.d(TAG, "Beacon monitoring and ranging stopped.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping beacon scanning", e)
            // Handle error appropriately, maybe notify user
        }
        // Do NOT set beaconManager to null here, keep instance for potential restart
    }

    // --- Beacon Ranging Update Handling ---

    private fun handleBeaconRangingUpdate(beacons: Collection<Beacon>) {
        if (!scanningBeacons) return // Don't process if scanning has been stopped

        Log.d(TAG, "Detected ${beacons.size} beacons.")
        if (beacons.isEmpty()) {
            // Consider what happens when no beacons are in range.
            // Maybe slowly fade out indicators or keep last known position?
            // For now, just clear dynamic elements if no beacons are seen.
            resetPreviousBeaconMarkers()
            clearLinesAndCircles()
            mapView.invalidate()
            return
        }

        // Update user position based on detected beacons
        updatePosition(beacons)

        // --- Update Beacon Markers and Visualizations ---
        resetPreviousBeaconMarkers() // Set previously green/red back to default (grey or red if floor matches)
        clearLinesAndCircles()     // Remove old lines and circles

        val detectedFloor = processCurrentBeacons(beacons) // Process newly detected beacons (set green, draw lines/circles)

        handleFloorChangeIfNeeded(detectedFloor) // Update red markers if floor changed

        mapView.invalidate() // Redraw map with updated overlays
    }

    // Reset markers that were highlighted (green) in the previous scan
    private fun resetPreviousBeaconMarkers() {
        for (beaconId in greenBeaconIds) {
            val marker = beaconIdtoMarker[beaconId] ?: continue
            val beaconData = beaconMap[beaconId]
            val (iconRes, dims) = when {
                // If it belongs to the *current* known floor, mark it red, else grey
                beaconData?.floorId == currentFloor -> Pair(R.drawable.reddot, MARKER_DIMENSIONS_RED)
                else -> Pair(R.drawable.greydot, MARKER_DIMENSIONS_GREY)
            }
            marker.icon = getScaledDrawable(iconRes, dims.first, dims.second)
            // Note: redBeaconIds contains beacons *on the current floor* that are *not currently detected*
            // So, if a beacon was green and matches current floor, it shouldn't be in redBeaconIds yet.
            // If it was green and *doesn't* match the current floor, it definitely shouldn't be red.
        }
        greenBeaconIds.clear() // Clear the set for the new scan results
    }

    // Remove lines and circles drawn in the previous scan
    private fun clearLinesAndCircles() {
        linesToUser.forEach { mapView.overlays.remove(it) }
        circlesAroundUser.forEach { mapView.overlays.remove(it) }
        linesToUser.clear()
        circlesAroundUser.clear()
    }

    // Process currently detected beacons: highlight green, draw lines/circles, detect floor
    private fun processCurrentBeacons(beacons: Collection<Beacon>): Int? {
        var floorDetectedThisScan: Int? = null
        val greenIcon = getScaledDrawable(R.drawable.greendot, MARKER_DIMENSIONS_GREEN.first, MARKER_DIMENSIONS_GREEN.second)

        for (beacon in beacons) {
            val beaconId = beacon.bluetoothAddress // Use MAC address as ID
            val marker = beaconIdtoMarker[beaconId] ?: continue // Skip if marker not found
            val beaconData = beaconMap[beaconId] ?: continue // Skip if data not found

            // Highlight detected beacon as green
            marker.icon = greenIcon
            greenBeaconIds.add(beaconId) // Add to the set of currently detected beacons
            redBeaconIds.remove(beaconId) // Remove from red set if it was there

            // Draw line from beacon to user, thickness based on RSSI
            val rssi = beacon.rssi
            Log.v(TAG, "Beacon $beaconId RSSI: $rssi, Dist: ${beacon.distance}") // Use verbose for frequent logs
            val lineToUser = Polyline().apply {
                addPoint(GeoPoint(beaconData.latitude, beaconData.longitude))
                addPoint(currentPosition) // Use the *updated* currentPosition
                outlinePaint.apply {
                    color = Color.BLUE // Maybe use a different color for lines?
                    // Ensure stroke width is positive and reasonable
                    strokeWidth = ((rssi + RSSI_MAX_VALUE).coerceIn(1, RSSI_RANGE) * STROKE_WIDTH_RSSI_SCALE).toFloat()
                }
            }
            mapView.overlays.add(lineToUser)
            linesToUser.add(lineToUser)

            // Draw circle around beacon based on estimated distance
            // Use AltBeacon's distance which already accounts for path loss model
            val distance = rssiToDistance(rssi) // Or use rssiToDistance(rssi) if you prefer your calculation
            val circle = getCircle(GeoPoint(beaconData.latitude, beaconData.longitude), distance)
            mapView.overlays.add(circle)
            circlesAroundUser.add(circle)

            // Determine the floor based on the *closest* detected beacon's floor ID
            // Or use a more robust method like majority floor ID among nearby beacons
            // Simple approach: use the first detected beacon's floor
            if (floorDetectedThisScan == null) {
                floorDetectedThisScan = beaconData.floorId
            }
            // More robust: Use floor of the closest beacon
            // if (beacon.distance < closestDistance) {
            //     closestDistance = beacon.distance
            //     floorDetectedThisScan = beaconData.floorId
            // }
        }
        return floorDetectedThisScan
    }

    // Update red markers if the detected floor has changed
    private fun handleFloorChangeIfNeeded(detectedFloor: Int?) {
        if (detectedFloor != null && detectedFloor != currentFloor) {
            Log.d(TAG, "Floor change detected: $currentFloor -> $detectedFloor")
            val previousFloor = currentFloor
            currentFloor = detectedFloor // Update the current floor

            // 1. Reset all markers that were red (from the *previous* floor) to grey
            val greyIcon = getScaledDrawable(R.drawable.greydot, MARKER_DIMENSIONS_GREY.first, MARKER_DIMENSIONS_GREY.second)
            for (redId in redBeaconIds) { // These were red because they matched the *old* floor
                beaconIdtoMarker[redId]?.icon = greyIcon
            }
            redBeaconIds.clear() // Clear the old set

            // 2. Mark beacons on the *new* current floor as red, *unless* they are currently detected (green)
            val redIcon = getScaledDrawable(R.drawable.reddot, MARKER_DIMENSIONS_RED.first, MARKER_DIMENSIONS_RED.second)
            for ((id, data) in beaconMap) {
                if (data.floorId == currentFloor && id !in greenBeaconIds) {
                    beaconIdtoMarker[id]?.icon = redIcon
                    redBeaconIds.add(id) // Add to the new set of red beacons
                }
                // Also ensure beacons from the *previous* floor that are *not* green are now grey
                else if (data.floorId == previousFloor && id !in greenBeaconIds) {
                     beaconIdtoMarker[id]?.icon = greyIcon
                }
            }
        }
        // If detectedFloor is null, maybe revert to a default state or keep the last known floor?
        // Current implementation keeps the last known floor if no beacons provide floor info.
    }

    //endregion

    //region Core Logic - Position Calculation
    // Calculate estimated distance from RSSI (Consider using AltBeacon's calculated distance instead)
    private fun rssiToDistance(rssi: Int): Double {
        // Formula: distance = 10 ^ ((TxPower - RSSI) / (10 * N))
        // TxPower: Received power at 1 meter (-59 dBm is a common default for iBeacons/Eddystone)
        // N: Path loss exponent (ranges from 2 in free space to 4+ in obstructed environments)
        return 10.0.pow((TX_POWER - rssi) / (10 * PATH_LOSS_EXPONENT))
    }

    // Update the user's position based on detected beacons using weighted average
    private fun updatePosition(beacons: Collection<Beacon>) {
        val validBeacons = beacons.mapNotNull { beacon ->
            beaconMap[beacon.bluetoothAddress]?.let { beaconData ->
                // Use AltBeacon's distance estimate which includes filtering and calibration
                val distance = beacon.distance.coerceAtLeast(MIN_BEACON_DISTANCE) // Avoid division by zero or near-zero
                Triple(beaconData.latitude, beaconData.longitude, distance)
            }
        }

        if (validBeacons.isEmpty()) {
            Log.w(TAG, "UpdatePosition called with no matching beacons in beaconMap.")
            return // No data to calculate position
        }

        if (validBeacons.size == 1) {
            // If only one beacon, we can't triangulate.
            // Option 1: Stay at the last known position (current behavior).
            // Option 2: Move towards the beacon (less accurate).
            // Option 3: Show a larger uncertainty radius.
             Log.d(TAG, "Only one beacon detected, position not updated.")
            // currentPosition = GeoPoint(validBeacons[0].first, validBeacons[0].second) // Option 2 example
        } else {
            // Use weighted average based on inverse distance
            var totalWeight = 0.0
            var weightedLat = 0.0
            var weightedLon = 0.0

            validBeacons.forEach { (lat, lon, distance) ->
                // Weight inversely proportional to distance (closer beacons have more influence)
                // Adding a small epsilon to distance prevents division by zero if distance is extremely small.
                val weight = 1.0 / (distance + 1e-6)
                weightedLat += lat * weight
                weightedLon += lon * weight
                totalWeight += weight
            }

            if (totalWeight > 0) {
                val newLat = weightedLat / totalWeight
                val newLon = weightedLon / totalWeight
                currentPosition = GeoPoint(newLat, newLon)
                Log.d(TAG, "Position updated to: Lat=$newLat, Lon=$newLon")
            } else {
                 Log.w(TAG, "Total weight is zero, cannot calculate position.")
            }
        }

        // Update the marker's position on the map
        positionMarker?.position = currentPosition
        // No need to call mapView.invalidate() here, it's called after all updates in handleBeaconRangingUpdate
    }

    // Calculate position using trilateration (more complex, potentially more accurate if distances are good)
    // This is a placeholder - a robust trilateration implementation is non-trivial.
    private fun calculatePositionTrilateration(beaconData: List<Triple<Double, Double, Double>>): GeoPoint {
        // Requires at least 3 beacons
        if (beaconData.size < 3) {
            Log.w(TAG, "Trilateration requires at least 3 beacons.")
            // Fallback to weighted average or return current position
            return calculatePositionWeightedAverage(beaconData) // Example fallback
        }
        // --- Complex Trilateration Logic Would Go Here ---
        // This involves converting lat/lon to a local Cartesian coordinate system,
        // solving a system of equations based on circle intersections,
        // and converting the result back to lat/lon.
        // Libraries like Apache Commons Math might be helpful.
        Log.w(TAG, "Trilateration calculation not implemented, using weighted average.")
        return calculatePositionWeightedAverage(beaconData)
    }

     // Extracted weighted average logic for potential reuse (e.g., by trilateration fallback)
    private fun calculatePositionWeightedAverage(beaconData: List<Triple<Double, Double, Double>>): GeoPoint {
        if (beaconData.isEmpty()) return currentPosition

        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLon = 0.0

        beaconData.forEach { (lat, lon, distance) ->
            val weight = 1.0 / (distance.coerceAtLeast(MIN_BEACON_DISTANCE)) // Ensure positive distance
            weightedLat += lat * weight
            weightedLon += lon * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) {
            GeoPoint(weightedLat / totalWeight, weightedLon / totalWeight)
        } else {
            Log.w(TAG, "Weighted average failed (total weight zero).")
            currentPosition // Fallback to last known position
        }
    }
    //endregion

    //region Permissions and State Checks
    private fun checkPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()

        // Location Permission (Needed for BLE scanning)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Bluetooth Permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // BLUETOOTH_CONNECT is needed by the AltBeacon library internally, even if not directly connecting.
                 requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
         // Optional: Background Location (If scanning needs to continue when app is not in foreground)
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        //     if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        //         // Explain why background location is needed before requesting
        //         // requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        //     }
        // }


        return if (requiredPermissions.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${requiredPermissions.joinToString()}")
            requestPermissions(requiredPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            false // Permissions are not granted yet
        } else {
            Log.d(TAG, "All required permissions are granted.")
            true // All permissions are already granted
        }
    }

    // Handle the result of the permission request
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            var allGranted = true
            for (i in grantResults.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    Log.w(TAG, "Permission denied: ${permissions[i]}")
                    // Explain to the user why the permission is necessary
                    showPermissionDeniedExplanation(permissions[i])
                }
            }

            if (allGranted) {
                Log.d(TAG, "Permissions granted after request.")
                // Try starting scan again if appropriate, or inform user permissions are ready
                // Consider calling checkBluetoothON() and checkLocationON() here as well
                if (servicesTriggerCheck()) {
                     // Optionally attempt to start scanning immediately after permissions granted
                     // startBeaconScanning() // Be careful not to loop if services are off
                     Toast.makeText(this, "Permissions granted. Ready to scan.", Toast.LENGTH_SHORT).show()
                }
            } else {
                 Log.w(TAG, "Some permissions were denied after request.")
                Toast.makeText(this, "Some permissions were denied. Cannot scan beacons.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Check if Bluetooth and Location services are enabled
    private fun servicesTriggerCheck(): Boolean {
        // Chain checks: if Bluetooth is off, no need to check Location for starting scan
        return checkBluetoothON() && checkLocationON()
    }

    private fun checkBluetoothON(): Boolean {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter
        bluetoothEnabled = bluetoothAdapter?.isEnabled == true
        if (!bluetoothEnabled) {
            Log.w(TAG, "Bluetooth is disabled.")
            showExplanation("Bluetooth Disabled", "Bluetooth must be enabled to scan for beacons.")
        }
        return bluetoothEnabled
    }

    private fun checkLocationON(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled // Simpler check for Android 9+
        } else {
            // Deprecated but necessary for older versions
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationEnabled) {
            Log.w(TAG, "Location services are disabled.")
            showExplanation("Location Disabled", "Location services must be enabled for beacon scanning to work (required by Android for Bluetooth scans).")
        }
        return locationEnabled
    }
    //endregion

    //region UI and Map Helpers
    private fun showExplanation(title: String, message: String) {
        // Ensure dialogs run on the UI thread
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

     private fun showPermissionDeniedExplanation(permission: String) {
        val message = when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "Fine location access is required to find beacons nearby."
            Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scanning permission is required to detect beacons (Android 12+)."
            Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth connect permission is needed by the beacon library (Android 12+)."
            // Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Background location access is needed to scan for beacons when the app is not in the foreground."
            else -> "This permission is required for the app to function correctly."
        }
        showExplanation("Permission Denied", message)
    }


    // Helper to create scaled drawables for markers
    fun getScaledDrawable(drawableId: Int, width: Int, height: Int): Drawable? {
        return try {
            ContextCompat.getDrawable(this, drawableId)?.let { originalDrawable ->
                // Create a mutable bitmap
                val bitmap = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1)) // Ensure positive dimensions
                val canvas = Canvas(bitmap)
                originalDrawable.setBounds(0, 0, canvas.width, canvas.height)
                originalDrawable.draw(canvas)
                bitmap.toDrawable(resources)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scaling drawable $drawableId", e)
            null // Return null if scaling fails
        }
    }

    // Helper function to create a Polyline representing a circle
    private fun getCircle(center: GeoPoint, radiusMeters: Double): Polyline {
        val earthRadius = 6378137.0 // WGS84 radius in meters
        val centerLatRad = Math.toRadians(center.latitude)

        val points = (0..360 step 20).map { i ->
            val angleRad = Math.toRadians(i.toDouble())

            // Offsets in radians
            val deltaLat = (radiusMeters / earthRadius) * cos(angleRad)
            val deltaLon = (radiusMeters / (earthRadius * cos(centerLatRad))) * sin(angleRad)

            // Convert back to degrees
            val pointLat = center.latitude + Math.toDegrees(deltaLat)
            val pointLon = center.longitude + Math.toDegrees(deltaLon)

            GeoPoint(pointLat, pointLon)
        }

        return Polyline().apply {
            setPoints(points)
            outlinePaint.strokeWidth = 3f
        }
    }
    //endregion

    //region Broadcast Receivers
    private fun registerReceivers() {
        try {
            registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
            Log.d(TAG, "State receivers registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receivers", e)
        }
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(locationReceiver)
            Log.d(TAG, "State receivers unregistered.")
        } catch (e: IllegalArgumentException) {
            // Can happen if receivers were already unregistered or never registered
            Log.w(TAG, "Receivers already unregistered or never registered.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
    }

    // Listens for Bluetooth state changes
    inner class BluetoothStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val previousState = bluetoothEnabled
                bluetoothEnabled = (state == BluetoothAdapter.STATE_ON)

                if (previousState != bluetoothEnabled) {
                     Log.i(TAG, "Bluetooth state changed: ${if (bluetoothEnabled) "ON" else "OFF"}")
                    if (!bluetoothEnabled && scanningBeacons) {
                        Log.w(TAG, "Bluetooth turned off during scanning. Stopping scan.")
                        stopBeaconScanningInternal() // Stop scan if BT turned off
                         showExplanation("Bluetooth Disabled", "Bluetooth was turned off. Beacon scanning stopped.")
                    } else if (bluetoothEnabled && !scanningBeacons) {
                        // Optionally notify user they can now start scanning
                        Toast.makeText(context, "Bluetooth enabled.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Listens for Location Provider state changes
    inner class LocationStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Checking for PROVIDERS_CHANGED_ACTION is sufficient as it covers enable/disable
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                 val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
                 val previousState = locationEnabled
                 locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locationManager.isLocationEnabled
                 } else {
                    @Suppress("DEPRECATION")
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                 }

                 if (previousState != locationEnabled) {
                     Log.i(TAG, "Location state changed: ${if (locationEnabled) "ON" else "OFF"}")
                     if (!locationEnabled && scanningBeacons) {
                         Log.w(TAG, "Location services turned off during scanning. Stopping scan.")
                         stopBeaconScanningInternal() // Stop scan if Location turned off
                         showExplanation("Location Disabled", "Location services were turned off. Beacon scanning stopped.")
                     } else if (locationEnabled && !scanningBeacons) {
                        // Optionally notify user they can now start scanning
                         Toast.makeText(context, "Location enabled.", Toast.LENGTH_SHORT).show()
                    }
                 }
            }
        }
    }
    //endregion

    //region Companion Object (Constants)
    companion object {
        private const val TAG = "MainActivity" // Log Tag

        // Map Defaults
        private const val INITIAL_LATITUDE = 52.2204685
        private const val INITIAL_LONGITUDE = 21.0101522
        private const val INITIAL_ZOOM_LEVEL = 19.5

        // Permissions
        private const val PERMISSION_REQUEST_CODE = 101

        // Beacon Settings
        private const val BEACON_REGION_ID = "all-beacons-region"
        private const val TX_POWER = -59.0 // Assumed beacon transmission power at 1m (in dBm) - Adjust if known
        private const val PATH_LOSS_EXPONENT = 4.0 // Environmental factor (2=free space, 3-4=typical indoor) - Adjust based on environment
        private const val MIN_BEACON_DISTANCE = 0.1 // Minimum distance to consider (meters) to avoid issues with weights

        // Marker Dimensions (Width, Height in Pixels)
        private val MARKER_DIMENSIONS_GREY = Pair(10, 10)
        private val MARKER_DIMENSIONS_RED = Pair(15, 15)
        private val MARKER_DIMENSIONS_GREEN = Pair(30, 30)
        private val MARKER_DIMENSIONS_USER = Pair(75, 100) // Miku icon size

        // Line/Circle Drawing
        private const val STROKE_WIDTH_RSSI_SCALE = 0.5f // Multiplier for line width based on RSSI
        private const val RSSI_MAX_VALUE = 110 // Rough max RSSI value for scaling stroke width offset
        private const val RSSI_RANGE = 110 // Range of RSSI values for scaling stroke width (e.g., -110 to 0)
        private const val CIRCLE_STROKE_WIDTH = 3f
        private const val CIRCLE_POINTS = 36 // Number of points to draw for circle smoothness (higher = smoother)


        // Data Files
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
    //endregion
}