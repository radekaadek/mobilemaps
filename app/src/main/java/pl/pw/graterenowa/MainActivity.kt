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
import androidx.activity.viewModels // Import for viewModels delegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Observer // Still need this for observing AltBeacon's region state
// import androidx.lifecycle.ViewModelProvider // Not needed if using by viewModels()
import com.google.gson.Gson // Kept for potential local use, but beacon loading is in VM
import org.altbeacon.beacon.Beacon // Required
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
import pl.pw.graterenowa.data.BeaconData // Required
// import pl.pw.graterenowa.data.BeaconResponse // Not directly used if VM handles loading
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    // region Properties

    // --- ViewModel ---
    private val mainViewModel: MainViewModel by viewModels()

    // --- Views ---
    private lateinit var mapView: MapView

    // --- Map Overlays ---
    private var positionMarker: Marker? = null
    private var beaconIdtoMarker: MutableMap<String, Marker> = mutableMapOf()
    private var linesToUser: MutableList<Polyline> = mutableListOf()
    private var circlesAroundUser: MutableList<Polyline> = mutableListOf()
    private var rotationGestureOverlay: RotationGestureOverlay? = null

    // --- Beacon Data (State now mostly in ViewModel) ---
    // private var beaconMap: Map<String, BeaconData> = emptyMap() // Moved to VM
    // private var greenBeaconIds: MutableSet<String> = mutableSetOf() // Moved to VM
    // private var redBeaconIds: MutableSet<String> = mutableSetOf() // Moved to VM

    // --- State (Some moved to ViewModel) ---
    // private var currentPosition = GeoPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE) // From VM
    // private var scanningBeacons = false // From VM
    private var bluetoothEnabled = false // Activity specific state checks still needed
    private var locationEnabled = false  // Activity specific state checks still needed
    // private var currentFloor: Int? = null // From VM

    // --- Managers ---
    private var beaconManager: BeaconManager? = null

    // --- Receivers ---
    private val bluetoothReceiver by lazy { BluetoothStateReceiver() }
    private val locationReceiver by lazy { LocationStateReceiver() }

    // --- Beacon Library Objects ---
    private val region by lazy { Region(BEACON_REGION_ID, null, null, null) }
    private val monitoringObserver = Observer<Int> { state ->
        // Log.d(TAG, "Region state changed: $state")
    }
    // endregion

    //region Lifecycle Methods
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getPreferences(MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        initializeMap()
        // loadReferenceBeacons() // Moved to ViewModel's init
        observeViewModel() // Setup observers for ViewModel LiveData
        setupListeners()
        registerReceivers()
        setupMapControls()
    }

    private fun observeViewModel() {
        mainViewModel.beaconMapData.observe(this) { beacons ->
            Log.d(TAG, "Observer: Beacon map data updated with ${beacons.size} beacons.")
            if (beacons.isNotEmpty()) {
                drawBeaconsOnMap(beacons) // Draw static beacons once loaded
            }
        }

        mainViewModel.currentPosition.observe(this) { newPosition ->
            Log.d(TAG, "Observer: Current position updated to $newPosition")
            updatePositionMarker(newPosition)
            // Potentially redraw lines/circles if they depend only on currentPosition and beacons (handled in ranging update)
        }

        mainViewModel.scanningState.observe(this) { isScanning ->
            Log.d(TAG, "Observer: Scanning state updated to $isScanning")
            // Update UI based on scanning state if needed (e.g., button text/enabled state)
            if (!isScanning) {
                // Clear dynamic overlays when scanning stops and update marker title
                val windowWasOpened = positionMarker?.isInfoWindowShown ?: false
                positionMarker?.apply {
                    closeInfoWindow()
                    title = "Last Known Position"
                    if (windowWasOpened) showInfoWindow()
                }
                // Be careful with resetPreviousBeaconMarkers here. It needs the correct currentFloor.
                // The logic in handleBeaconRangingUpdate or a dedicated stop function in VM should handle this.
                clearLinesAndCircles()
                mapView.invalidate()
            } else {
                positionMarker?.title = "Current Position"
                setupPositionMarker() // Ensure marker is present and titled correctly when scanning starts
            }
        }

        mainViewModel.greenBeaconIds.observe(this) { greenIds ->
            Log.d(TAG, "Observer: Green beacon IDs updated: $greenIds")
            // This might trigger redraw logic if not handled elsewhere
            // The primary beacon update is handled via handleBeaconRangingUpdate for now
        }

        mainViewModel.redBeaconIds.observe(this) { redIds ->
            Log.d(TAG, "Observer: Red beacon IDs updated: $redIds")
            // This might trigger redraw logic
        }
        mainViewModel.currentFloor.observe(this) { floor ->
            Log.d(TAG, "Observer: Current floor updated to $floor")
            // This will trigger re-evaluation of red/grey beacons if floor changes
            // Needs to be coordinated with green beacon updates.
            // Consider a combined "beacon visual update" LiveData or event.
            // For now, rely on the logic within handleBeaconRangingUpdate to use this.
        }

        mainViewModel.toastMessage.observe(this) { message ->
            message?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                mainViewModel.clearToastMessage() // Consume the message
            }
        }
    }


    override fun onResume() {
        super.onResume()
        mapView.onResume()
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
        if (mainViewModel.scanningState.value == true) {
            stopBeaconScanningInternal()
        }
        mapView.onDetach()
    }
    //endregion

    //region Initialization
    private fun initializeMap() {
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.controller.apply {
            // Use ViewModel's initial position if needed, or keep activity's default for first setup
            setCenter(mainViewModel.currentPosition.value ?: GeoPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE))
            setZoom(INITIAL_ZOOM_LEVEL)
        }
    }

    // No longer needed here, ViewModel handles it
    // private fun loadReferenceBeacons() { ... }

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
        mapView.setMultiTouchControls(true)
        rotationGestureOverlay = RotationGestureOverlay(mapView).apply {
            isEnabled = true
        }
        mapView.overlays.add(rotationGestureOverlay)
    }

    private fun drawBeaconsOnMap(beaconDataMap: Map<String, BeaconData>) {
        // Clear existing beacon markers first to prevent duplicates if this is called multiple times
        beaconIdtoMarker.values.forEach { mapView.overlays.remove(it) }
        beaconIdtoMarker.clear()

        val defaultIcon = getScaledDrawable(R.drawable.greydot, MARKER_DIMENSIONS_GREY.first, MARKER_DIMENSIONS_GREY.second)
        if (defaultIcon == null) {
            Log.e(TAG, "Failed to create default beacon icon.")
        }

        val currentFloorVal = mainViewModel.currentFloor.value
        val greenBeaconIdsVal = mainViewModel.greenBeaconIds.value ?: emptySet()
        // Red beacons are derived from currentFloor and not green.

        for ((beaconId, beaconData) in beaconDataMap) {
            val icon = when {
                greenBeaconIdsVal.contains(beaconId) -> getScaledDrawable(R.drawable.greendot, MARKER_DIMENSIONS_GREEN.first, MARKER_DIMENSIONS_GREEN.second)
                beaconData.floorId == currentFloorVal -> getScaledDrawable(R.drawable.reddot, MARKER_DIMENSIONS_RED.first, MARKER_DIMENSIONS_RED.second)
                else -> defaultIcon
            }

            val marker = Marker(mapView).apply {
                position = GeoPoint(beaconData.latitude, beaconData.longitude)
                title = beaconId
                this.icon = icon ?: ContextCompat.getDrawable(this@MainActivity, R.drawable.greydot) // Fallback
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
            beaconIdtoMarker[beaconId] = marker
        }
        mapView.invalidate()
    }


    private fun setupPositionMarker() {
        if (positionMarker == null) {
            positionMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = getScaledDrawable(R.drawable.miku, MARKER_DIMENSIONS_USER.first, MARKER_DIMENSIONS_USER.second)
                    ?: ContextCompat.getDrawable(this@MainActivity, R.drawable.miku)
                title = if (mainViewModel.scanningState.value == true) "Current Position" else "Last Known Position"
            }
            mapView.overlays.add(positionMarker)
        }
        mainViewModel.currentPosition.value?.let { positionMarker?.position = it }
        mapView.invalidate()
    }

    private fun updatePositionMarker(newPosition: GeoPoint) {
        if (positionMarker == null) {
            setupPositionMarker() // Create if it doesn't exist
        }
        positionMarker?.position = newPosition
        // No need to invalidate map here, it will be invalidated after all updates
        // or by the observer that calls this.
    }
    //endregion

    //region Core Logic - Beacon Scanning
    private fun startBeaconScanning() {
        if (mainViewModel.scanningState.value == true) {
            Toast.makeText(this, "Scanning already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkPermissions()) return
        if (!servicesTriggerCheck()) return

        Log.d(TAG, "Starting beacon scanning...")
        mainViewModel.updateScanningState(true) // Update ViewModel state
        Toast.makeText(this, "Scanning beacons...", Toast.LENGTH_SHORT).show()

        if (beaconManager == null) {
            beaconManager = BeaconManager.getInstanceForApplication(this).apply {
                beaconParsers.clear()
                beaconParsers.addAll(listOf(
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_UID_LAYOUT),
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_TLM_LAYOUT),
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_URL_LAYOUT),
                ))
            }
        }
        setupPositionMarker() // Ensure user marker is set up

        beaconManager?.apply {
            getRegionViewModel(region).regionState.observe(this@MainActivity, monitoringObserver)
            addRangeNotifier { beacons, _ ->
                runOnUiThread { handleBeaconRangingUpdate(beacons) }
            }
            try {
                startMonitoring(region)
                startRangingBeacons(region)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting beacon scanning", e)
                Toast.makeText(this@MainActivity, "Error starting scanning", Toast.LENGTH_SHORT).show()
                mainViewModel.updateScanningState(false)
            }
        }
    }

    private fun stopBeaconScanning() {
        if (mainViewModel.scanningState.value == false) {
            return
        }
        Log.d(TAG, "Stopping beacon scanning...")
        stopBeaconScanningInternal()
        Toast.makeText(this, "Stopped scanning beacons", Toast.LENGTH_SHORT).show()
        // ViewModel observer for scanningState will handle UI updates like marker title.
    }

    private fun stopBeaconScanningInternal() {
        mainViewModel.updateScanningState(false) // Update ViewModel state
        try {
            beaconManager?.apply {
                if (getRegionViewModel(region).regionState.hasObservers()) {
                    getRegionViewModel(region).regionState.removeObserver(monitoringObserver)
                }
                stopRangingBeacons(region)
                stopMonitoring(region)
                removeAllRangeNotifiers()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping beacon scanning", e)
        }
    }


    private fun handleBeaconRangingUpdate(beacons: Collection<Beacon>) {
        if (mainViewModel.scanningState.value == false) return

        Log.d(TAG, "Detected ${beacons.size} beacons.")
        mainViewModel.processBeaconRangingUpdate(beacons) // Delegate processing to ViewModel

        // UI updates based on ViewModel's LiveData observers
        // For direct visual updates not covered by simple LiveData observation (like lines/circles):
        redrawDynamicOverlays(beacons)
        mapView.invalidate()
    }

    private fun redrawDynamicOverlays(detectedBeacons: Collection<Beacon>) {
        clearLinesAndCircles()

        val currentPos = mainViewModel.currentPosition.value ?: return // Need current position
        val beaconMapVal = mainViewModel.beaconMapData.value ?: return // Need beacon data
        val greenBeaconIdsVal = mainViewModel.greenBeaconIds.value ?: emptySet()
        val currentFloorVal = mainViewModel.currentFloor.value

        // Update beacon marker icons (green, red, grey)
        val greenIcon = getScaledDrawable(R.drawable.greendot, MARKER_DIMENSIONS_GREEN.first, MARKER_DIMENSIONS_GREEN.second)
        val redIcon = getScaledDrawable(R.drawable.reddot, MARKER_DIMENSIONS_RED.first, MARKER_DIMENSIONS_RED.second)
        val greyIcon = getScaledDrawable(R.drawable.greydot, MARKER_DIMENSIONS_GREY.first, MARKER_DIMENSIONS_GREY.second)

        for ((id, marker) in beaconIdtoMarker) {
            val beaconDetails = beaconMapVal[id]
            marker.icon = when {
                greenBeaconIdsVal.contains(id) -> greenIcon
                beaconDetails?.floorId == currentFloorVal -> redIcon
                else -> greyIcon
            }
        }


        // Draw lines and circles for currently detected (green) beacons
        detectedBeacons.forEach { beacon ->
            val beaconId = beacon.bluetoothAddress
            val beaconData = beaconMapVal[beaconId] ?: return@forEach // Continue if not in our map

            if (greenBeaconIdsVal.contains(beaconId)) { // Only for "green" beacons
                // Draw line from beacon to user
                val lineToUser = Polyline().apply {
                    addPoint(GeoPoint(beaconData.latitude, beaconData.longitude))
                    addPoint(currentPos)
                    outlinePaint.apply {
                        color = Color.BLUE
                        strokeWidth = ((beacon.rssi + RSSI_MAX_VALUE).coerceIn(1, RSSI_RANGE) * STROKE_WIDTH_RSSI_SCALE).toFloat()
                    }
                }
                mapView.overlays.add(lineToUser)
                linesToUser.add(lineToUser)

                // Draw circle around beacon
                val distance = rssiToDistance(beacon.rssi) // Or beacon.distance
                val circle = getCircle(GeoPoint(beaconData.latitude, beaconData.longitude), distance)
                mapView.overlays.add(circle)
                circlesAroundUser.add(circle)
            }
        }
    }


    // Reset markers that were highlighted (green) in the previous scan - Logic now handled by redrawDynamicOverlays observing VM
    // private fun resetPreviousBeaconMarkers() { ... }

    private fun clearLinesAndCircles() {
        linesToUser.forEach { mapView.overlays.remove(it) }
        circlesAroundUser.forEach { mapView.overlays.remove(it) }
        linesToUser.clear()
        circlesAroundUser.clear()
    }

    // Process currently detected beacons - Logic now handled by ViewModel (processBeaconRangingUpdate)
    // and UI updates by redrawDynamicOverlays
    // private fun processCurrentBeacons(beacons: Collection<Beacon>): Int? { ... }

    // Update red markers if the detected floor has changed - Logic now handled by ViewModel and redrawDynamicOverlays
    // private fun handleFloorChangeIfNeeded(detectedFloor: Int?) { ... }

    //endregion

    //region Core Logic - Position Calculation (Moved to ViewModel)
    // private fun rssiToDistance(rssi: Int): Double { ... }
    // private fun updatePosition(beacons: Collection<Beacon>) { ... }
    // private fun calculatePositionTrilateration(beaconData: List<Triple<Double, Double, Double>>): GeoPoint { ... }
    // private fun calculatePositionWeightedAverage(beaconData: List<Triple<Double, Double, Double>>): GeoPoint { ... }
    //endregion

    //region Permissions and State Checks (Remains in Activity)
    private fun checkPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        return if (requiredPermissions.isNotEmpty()) {
            requestPermissions(requiredPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            var allGranted = true
            grantResults.forEachIndexed { index, result ->
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    showPermissionDeniedExplanation(permissions[index])
                }
            }
            if (allGranted) {
                if (servicesTriggerCheck()) {
                    Toast.makeText(this, "Permissions granted. Ready to scan.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Some permissions were denied. Cannot scan beacons.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun servicesTriggerCheck(): Boolean {
        return checkBluetoothON() && checkLocationON()
    }

    private fun checkBluetoothON(): Boolean {
        val btManager = getSystemService(BluetoothManager::class.java)
        bluetoothEnabled = btManager?.adapter?.isEnabled == true
        if (!bluetoothEnabled) {
            showExplanation("Bluetooth Disabled", "Bluetooth must be enabled to scan for beacons.")
        }
        return bluetoothEnabled
    }

    private fun checkLocationON(): Boolean {
        val locManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationEnabled) {
            showExplanation("Location Disabled", "Location services must be enabled for beacon scanning.")
        }
        return locationEnabled
    }
    //endregion

    //region UI and Map Helpers (Some can stay, some might be adapted if VM provides direct drawables/colors)
    private fun showExplanation(title: String, message: String) {
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
            Manifest.permission.ACCESS_FINE_LOCATION -> "Fine location access is required to find beacons."
            Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scanning permission is required (Android 12+)."
            Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth connect permission is needed (Android 12+)."
            else -> "This permission is required."
        }
        showExplanation("Permission Denied", message)
    }

    fun getScaledDrawable(drawableId: Int, width: Int, height: Int): Drawable? {
        return try {
            ContextCompat.getDrawable(this, drawableId)?.let { originalDrawable ->
                val bitmap = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
                val canvas = Canvas(bitmap)
                originalDrawable.setBounds(0, 0, canvas.width, canvas.height)
                originalDrawable.draw(canvas)
                bitmap.toDrawable(resources)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scaling drawable $drawableId", e)
            null
        }
    }
    //rssiToDistance is used by redrawDynamicOverlays for circle radius
    private fun rssiToDistance(rssi: Int): Double {
        return 10.0.pow((TX_POWER - rssi) / (10 * PATH_LOSS_EXPONENT))
    }

    private fun getCircle(center: GeoPoint, radiusMeters: Double): Polyline {
        val earthRadius = 6378137.0
        val centerLatRad = Math.toRadians(center.latitude)
        val points = (0..360 step 20).map { i ->
            val angleRad = Math.toRadians(i.toDouble())
            val deltaLat = (radiusMeters / earthRadius) * cos(angleRad)
            val deltaLon = (radiusMeters / (earthRadius * cos(centerLatRad))) * sin(angleRad)
            GeoPoint(center.latitude + Math.toDegrees(deltaLat), center.longitude + Math.toDegrees(deltaLon))
        }
        return Polyline().apply {
            setPoints(points)
            outlinePaint.strokeWidth = CIRCLE_STROKE_WIDTH
            // You might want to set the color of the circle here too
            // outlinePaint.color = Color.parseColor("#550000FF") // Example: semi-transparent blue
        }
    }
    //endregion

    //region Broadcast Receivers (Remains in Activity)
    private fun registerReceivers() {
        try {
            registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        } catch (e: Exception) { Log.e(TAG, "Error registering receivers", e) }
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(locationReceiver)
        } catch (e: Exception) { Log.e(TAG, "Error unregistering receivers", e) }
    }

    inner class BluetoothStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val previousState = bluetoothEnabled
                bluetoothEnabled = (state == BluetoothAdapter.STATE_ON)
                if (previousState != bluetoothEnabled) {
                    if (!bluetoothEnabled && mainViewModel.scanningState.value == true) {
                        stopBeaconScanningInternal()
                        showExplanation("Bluetooth Disabled", "Bluetooth turned off. Scanning stopped.")
                    } else if (bluetoothEnabled && mainViewModel.scanningState.value == false) {
                        Toast.makeText(context, "Bluetooth enabled.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    inner class LocationStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val locManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
                val previousState = locationEnabled
                locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locManager.isLocationEnabled
                } else {
                    @Suppress("DEPRECATION")
                    locManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
                if (previousState != locationEnabled) {
                    if (!locationEnabled && mainViewModel.scanningState.value == true) {
                        stopBeaconScanningInternal()
                        showExplanation("Location Disabled", "Location turned off. Scanning stopped.")
                    } else if (locationEnabled && mainViewModel.scanningState.value == false) {
                        Toast.makeText(context, "Location enabled.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    //endregion

    //region Companion Object (Constants)
    companion object {
        private const val TAG = "MainActivity" // Log Tag for Activity

        // Map Defaults (Can be kept here or moved to ViewModel if they influence VM logic directly)
        private const val INITIAL_LATITUDE = 52.2204685 // Used if VM value is null initially
        private const val INITIAL_LONGITUDE = 21.0101522 // Used if VM value is null initially
        private const val INITIAL_ZOOM_LEVEL = 19.5

        // Permissions
        private const val PERMISSION_REQUEST_CODE = 101

        // Beacon Settings
        private const val BEACON_REGION_ID = "all-beacons-region"
        // TX_POWER and PATH_LOSS_EXPONENT are now in ViewModel (TX_POWER_VM, PATH_LOSS_EXPONENT_VM)
        // but rssiToDistance is still in Activity, so we need them here or pass them.
        // For simplicity, keeping a version here for the Activity's rssiToDistance.
        private const val TX_POWER = -59.0
        private const val PATH_LOSS_EXPONENT = 4.0


        // Marker Dimensions (Width, Height in Pixels)
        private val MARKER_DIMENSIONS_GREY = Pair(10, 10)
        private val MARKER_DIMENSIONS_RED = Pair(15, 15)
        private val MARKER_DIMENSIONS_GREEN = Pair(30, 30)
        private val MARKER_DIMENSIONS_USER = Pair(75, 100)

        // Line/Circle Drawing
        private const val STROKE_WIDTH_RSSI_SCALE = 0.5f
        private const val RSSI_MAX_VALUE = 110
        private const val RSSI_RANGE = 110
        private const val CIRCLE_STROKE_WIDTH = 3f
        // CIRCLE_POINTS can remain or be removed if not used directly

        // Data Files (Now in ViewModel: BEACON_JSON_FILES_VM)
        // private val beaconJsonFileNames = listOf(...)
    }
    //endregion
}