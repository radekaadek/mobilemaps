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
import android.graphics.Color
// import android.graphics.drawable.Drawable // Not directly used here after refactor
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer // Keep this for BeaconManager's RegionViewModel
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
import pl.pw.graterenowa.utils.MapUtils

class MainActivity : AppCompatActivity() {

    // region Properties
    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var mapView: MapView

    private var positionMarker: Marker? = null
    private var beaconIdToMarker: MutableMap<String, Marker> = mutableMapOf()
    private var linesToUser: MutableList<Polyline> = mutableListOf()
    private var circlesAroundBeacons: MutableList<Polyline> = mutableListOf()
    private var rotationGestureOverlay: RotationGestureOverlay? = null

    private var bluetoothEnabled = false
    private var locationEnabled = false

    private var beaconManager: BeaconManager? = null
    private val bluetoothReceiver by lazy { BluetoothStateReceiver() }
    private val locationReceiver by lazy { LocationStateReceiver() }

    private val region by lazy { Region(BEACON_REGION_ID, null, null, null) }
    private val monitoringObserver = Observer<Int> { /* state -> Log.d(TAG, "Region state changed: $state") */ }
    // endregion

    //region Lifecycle Methods
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getPreferences(MODE_PRIVATE))
        // IMPORTANT: Make sure you have a layout file named "activity_main.xml" in res/layout
        setContentView(R.layout.activity_main)

        // IMPORTANT: Make sure your activity_main.xml contains a MapView with this ID
        mapView = findViewById(R.id.mapView)


        initializeMapSettings() // Renamed from initializeMap to avoid conflict with mapView init
        observeViewModel()
        setupListeners()
        registerReceivers()
        setupMapControls()
    }

    private fun initializeMapSettings() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.controller.apply {
            setCenter(mainViewModel.currentPosition.value ?: GeoPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE))
            setZoom(INITIAL_ZOOM_LEVEL)
        }
    }

    private fun observeViewModel() {
        // Obsługa beaconMapData: Sprawdzamy, czy 'it' nie jest null
        mainViewModel.beaconMapData.observe(this) { beacons ->
            // Użyjemy 'let' do bezpiecznego wywołania kodu tylko, gdy 'beacons' nie jest null
            beacons?.let { // Dodana obsługa nullability
                Log.d(TAG, "Observer: Beacon map data updated with ${it.size} beacons.")
                if (it.isNotEmpty()) {
                    drawStaticBeaconMarkers(it)
                }
            } ?: run {
                // Opcjonalnie: obsłuż sytuację, gdy beacons jest null, np. wyczyść markery
                Log.w(TAG, "Observer: beaconMapData received null.")
                beaconIdToMarker.values.forEach { mapView.overlays.remove(it) }
                beaconIdToMarker.clear()
                mapView.invalidate()
            }
        }

        // Obsługa currentPosition: Sprawdzamy, czy 'it' nie jest null
        mainViewModel.currentPosition.observe(this) { newPosition ->
            newPosition?.let { // Dodana obsługa nullability
                Log.d(TAG, "Observer: Current position updated to $it")
                updatePositionMarker(it)
            } ?: run {
                Log.w(TAG, "Observer: currentPosition received null.")
                // Opcjonalnie: obsłuż sytuację, gdy newPosition jest null
            }
        }

        // Obsługa scanningState: Sprawdzamy, czy 'it' nie jest null
        mainViewModel.scanningState.observe(this) { isScanning ->
            isScanning?.let { // Dodana obsługa nullability
                Log.d(TAG, "Observer: Scanning state updated to $it")
                val windowWasOpened = positionMarker?.isInfoWindowShown == true
                if (!it) { // Używamy 'it' zamiast 'isScanning' wewnątrz let
                    positionMarker?.apply {
                        closeInfoWindow()
                        // IMPORTANT: Replace with your actual string resource
                        title = getString(R.string.last_known_position)
                        if (windowWasOpened) showInfoWindow()
                    }
                    clearDynamicOverlays()
                    // Bezpiecznie wywołujemy mainViewModel.beaconMapData.value?.let
                    mainViewModel.beaconMapData.value?.let { beaconMap ->
                        updateBeaconMarkerVisuals(beaconMap, emptySet(), mainViewModel.currentFloor.value)
                    }
                } else {
                    positionMarker?.title = getString(R.string.current_position) // IMPORTANT: Replace
                    setupPositionMarker() // Ensure marker is present and titled correctly
                }
                mapView.invalidate()
            } ?: run {
                Log.w(TAG, "Observer: scanningState received null.")
                // Opcjonalnie: obsłuż sytuację, gdy isScanning jest null
            }
        }

        // Wspólny obserwator dla greenBeaconIds, redBeaconIds, currentFloor
        // Ten obserwator jest typu Observer<Any>, co sugeruje, że 'it' może być różnego typu
        // Dodajemy obsługę nullability dla 'it' oraz sprawdzamy typ, jeśli jest to konieczne.
        val visualUpdateObserver = Observer<Any?> { // Zmieniono na Observer<Any?> dla obsługi nullability
            // Sprawdzamy, czy 'it' nie jest null przed użyciem
            it?.let { data -> // Dodana obsługa nullability i nazwanie parametru 'data'
                if (mainViewModel.scanningState.value == true) {
                    // W trybie skanowania, redrawDynamicOverlays bazuje na lastDetectedBeacons.value
                    // Niezależnie od tego, co zostało zaktualizowane (green, red, floor),
                    // chcemy przerysować dynamiczne elementy, jeśli skanujemy.
                    // Dodajemy bezpieczne wywołanie .value?
                    mainViewModel.lastDetectedBeacons.value?.let { beacons ->
                        redrawDynamicOverlays(beacons)
                    }
                } else {
                    if (data is Int? && mainViewModel.scanningState.value == false) { // Sprawdzamy typ, jeśli nie skanujemy
                        // Logika dla aktualizacji wizualnych na podstawie currentFloor, gdy nie skanujemy
                        // Bezpiecznie wywołujemy .value?.let
                        mainViewModel.beaconMapData.value?.let { beaconMap ->
                            updateBeaconMarkerVisuals(beaconMap, emptySet(), data) // data to aktualna wartość currentFloor
                            mapView.invalidate()
                        }
                    }
                    else {}
                }
            } ?: run {
                Log.w(TAG, "Observer: visualUpdateObserver received null data.")
                // Opcjonalnie: obsłuż sytuację, gdy dane są null
            }
        }

        // Rejestrujemy obserwatorów dla poszczególnych LiveData
        mainViewModel.greenBeaconIds.observe(this, visualUpdateObserver as Observer<in Set<String>?>) // Rzutowanie z obsługą nullability
        mainViewModel.redBeaconIds.observe(this, visualUpdateObserver as Observer<in Set<String>?>) // Rzutowanie z obsługą nullability
        mainViewModel.currentFloor.observe(this, visualUpdateObserver as Observer<in Int?>) // Rzutowanie z obsługą nullability


        // Obsługa toastMessage: Sprawdzamy, czy 'it' nie jest null
        mainViewModel.toastMessage.observe(this) { message ->
            message?.let { // Dodana obsługa nullability
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                mainViewModel.clearToastMessage()
            } ?: run {
                Log.w(TAG, "Observer: toastMessage received null.")
                // Obsługa null dla toastMessage nie jest zazwyczaj konieczna,
                // ponieważ clearToastMessage ustawia wartość na null,
                // ale obsługa let/run{} jest dobrym nawykiem.
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

    //region UI Setup
    private fun setupListeners() {
        // IMPORTANT: Make sure your activity_main.xml contains Buttons with these IDs
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
        if (!mapView.overlays.contains(rotationGestureOverlay)) { // Add only if not already present
            mapView.overlays.add(rotationGestureOverlay)
        }
    }

    private fun drawStaticBeaconMarkers(beaconDataMap: Map<String, BeaconData>) {
        beaconIdToMarker.values.forEach { mapView.overlays.remove(it) }
        beaconIdToMarker.clear()

        // IMPORTANT: Replace with your actual drawable resource
        val defaultIcon = MapUtils.getScaledDrawable(this, R.drawable.greydot, MARKER_DIMENSIONS_GREY.first, MARKER_DIMENSIONS_GREY.second)
        if (defaultIcon == null) Log.e(TAG, "Failed to create default beacon icon.")

        beaconDataMap.forEach { (beaconId, beaconData) ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(beaconData.latitude, beaconData.longitude)
                title = beaconId
                // IMPORTANT: Replace with your actual drawable resource
                icon = defaultIcon ?: ContextCompat.getDrawable(this@MainActivity, R.drawable.greydot)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
            beaconIdToMarker[beaconId] = marker
        }
        updateBeaconMarkerVisuals(beaconDataMap, emptySet(), mainViewModel.currentFloor.value)
        mapView.invalidate()
    }

    private fun setupPositionMarker() {
        if (positionMarker == null) {
            positionMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                // IMPORTANT: Replace with your actual drawable resource
                icon = MapUtils.getScaledDrawable(this@MainActivity, R.drawable.miku, MARKER_DIMENSIONS_USER.first, MARKER_DIMENSIONS_USER.second)
                    ?: ContextCompat.getDrawable(this@MainActivity, R.drawable.miku)
                title = if (mainViewModel.scanningState.value == true) getString(R.string.current_position) else getString(R.string.last_known_position) // IMPORTANT: Replace
            }
            if(!mapView.overlays.contains(positionMarker)) { // Add only if not already present
                mapView.overlays.add(positionMarker)
            }
        }
        mainViewModel.currentPosition.value?.let { positionMarker?.position = it }
    }

    private fun updatePositionMarker(newPosition: GeoPoint) {
        if (positionMarker == null) {
            setupPositionMarker()
        }
        positionMarker?.position = newPosition
    }
    //endregion

    //region Core Logic - Beacon Scanning
    private fun startBeaconScanning() {
        if (mainViewModel.scanningState.value == true) {
            Toast.makeText(this, R.string.scanning_already_in_progress, Toast.LENGTH_SHORT).show() // IMPORTANT: Replace
            return
        }
        if (!checkPermissions()) return
        if (!servicesTriggerCheck()) return

        Log.d(TAG, "Starting beacon scanning...")
        mainViewModel.updateScanningState(true)
        Toast.makeText(this, R.string.scanning_beacons_start, Toast.LENGTH_SHORT).show() // IMPORTANT: Replace

        if (beaconManager == null) {
            beaconManager = BeaconManager.getInstanceForApplication(this).apply {
                beaconParsers.clear()
                beaconParsers.addAll(listOf(
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_UID_LAYOUT),
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_TLM_LAYOUT),
                    BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_URL_LAYOUT),
                    BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24") // iBeacon
                ))
            }
        }
        setupPositionMarker()

        beaconManager?.apply {
            try {
                val regionViewModel = getRegionViewModel(region)
                regionViewModel.regionState.removeObserver(monitoringObserver) // Ensure not doubly added
                regionViewModel.regionState.observe(this@MainActivity, monitoringObserver)

                removeAllRangeNotifiers()
                addRangeNotifier { beacons, _ ->
                    runOnUiThread { handleBeaconRangingUpdate(beacons) }
                }
                startMonitoring(region)
                startRangingBeacons(region)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting beacon scanning", e)
                Toast.makeText(this@MainActivity, R.string.error_starting_scanning, Toast.LENGTH_SHORT).show() // IMPORTANT: Replace
                mainViewModel.updateScanningState(false)
            }
        }
    }

    private fun stopBeaconScanning() {
        if (mainViewModel.scanningState.value != true) return // Simplified check
        Log.d(TAG, "Stopping beacon scanning...")
        stopBeaconScanningInternal()
        Toast.makeText(this, R.string.stopped_scanning_beacons, Toast.LENGTH_SHORT).show() // IMPORTANT: Replace
    }

    private fun stopBeaconScanningInternal() {
        mainViewModel.updateScanningState(false)
        try {
            beaconManager?.apply {
                getRegionViewModel(region).regionState.removeObserver(monitoringObserver)
                stopRangingBeacons(region)
                stopMonitoring(region)
                removeAllRangeNotifiers()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping beacon scanning", e)
        }
    }

    private fun handleBeaconRangingUpdate(beacons: Collection<Beacon>) {
        if (mainViewModel.scanningState.value != true) return

        Log.d(TAG, "Detected ${beacons.size} beacons.")
        mainViewModel.processBeaconRangingUpdate(beacons) // This will update LiveData

        // Visual updates are now primarily handled by observers on greenBeaconIds, redBeaconIds, currentFloor
        // However, we still need to call redrawDynamicOverlays to update lines/circles
        // and ensure marker icons are in sync if observers didn't catch all nuances.
        redrawDynamicOverlays(beacons) // Keep this for lines/circles and ensuring icon sync
        mapView.invalidate()
    }

    private fun redrawDynamicOverlays(detectedBeacons: Collection<Beacon>) {
        clearDynamicOverlays()

        val currentPos = mainViewModel.currentPosition.value ?: return
        val beaconMapVal = mainViewModel.beaconMapData.value ?: return
        val greenBeaconIdsVal = mainViewModel.greenBeaconIds.value ?: emptySet()
        val currentFloorVal = mainViewModel.currentFloor.value

        updateBeaconMarkerVisuals(beaconMapVal, greenBeaconIdsVal, currentFloorVal)
        drawUserBeaconProximityElements(detectedBeacons, beaconMapVal, greenBeaconIdsVal, currentPos)
    }

    private fun updateBeaconMarkerVisuals(
        allBeacons: Map<String, BeaconData>,
        greenBeaconIds: Set<String>,
        currentFloor: Int?
    ) {
        // IMPORTANT: Replace with your actual drawable resources
        val greenIcon = MapUtils.getScaledDrawable(this, R.drawable.greendot, MARKER_DIMENSIONS_GREEN.first, MARKER_DIMENSIONS_GREEN.second)
        val redIcon = MapUtils.getScaledDrawable(this, R.drawable.reddot, MARKER_DIMENSIONS_RED.first, MARKER_DIMENSIONS_RED.second)
        val greyIcon = MapUtils.getScaledDrawable(this, R.drawable.greydot, MARKER_DIMENSIONS_GREY.first, MARKER_DIMENSIONS_GREY.second)

        for ((id, marker) in beaconIdToMarker) {
            val beaconDetails = allBeacons[id]
            marker.icon = when {
                greenBeaconIds.contains(id) -> greenIcon
                beaconDetails?.floorId == currentFloor -> redIcon
                else -> greyIcon
            } ?: ContextCompat.getDrawable(this, R.drawable.greydot) // Fallback
        }
    }

    private fun drawUserBeaconProximityElements(
        detectedBeacons: Collection<Beacon>,
        allBeaconsMap: Map<String, BeaconData>,
        greenBeaconIds: Set<String>,
        currentUserPosition: GeoPoint
    ) {
        detectedBeacons.forEach beaconLoop@{ beacon ->
            val beaconId = beacon.bluetoothAddress
            if (!greenBeaconIds.contains(beaconId)) return@beaconLoop

            val beaconData = allBeaconsMap[beaconId] ?: return@beaconLoop

            val lineToUser = Polyline().apply {
                addPoint(GeoPoint(beaconData.latitude, beaconData.longitude))
                addPoint(currentUserPosition)
                outlinePaint.apply {
                    color = Color.BLUE
                    val rawStrokeWidth = (beacon.rssi + MapUtils.RSSI_MAX_VALUE).coerceIn(1, MapUtils.RSSI_RANGE) * MapUtils.STROKE_WIDTH_RSSI_SCALE
                    strokeWidth = rawStrokeWidth.toFloat().coerceAtLeast(1.0f)
                }
            }
            mapView.overlays.add(lineToUser)
            linesToUser.add(lineToUser)

            val distance = MapUtils.rssiToDistance(
                beacon.rssi,
                mainViewModel.txPower,
                mainViewModel.pathLossExponent
            )
            val circle = MapUtils.getCircle(GeoPoint(beaconData.latitude, beaconData.longitude), distance)
            circle.outlinePaint.color = Color.argb(85, 0, 0, 255)
            mapView.overlays.add(circle)
            circlesAroundBeacons.add(circle)
        }
    }

    private fun clearDynamicOverlays() {
        linesToUser.forEach { mapView.overlays.remove(it) }
        circlesAroundBeacons.forEach { mapView.overlays.remove(it) }
        linesToUser.clear()
        circlesAroundBeacons.clear()
    }
    //endregion

    //region Permissions and State Checks
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
                    // IMPORTANT: Replace with your actual string resource
                    Toast.makeText(this, R.string.permissions_granted, Toast.LENGTH_SHORT).show()
                }
            } else {
                // IMPORTANT: Replace with your actual string resource
                Toast.makeText(this, R.string.permissions_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun servicesTriggerCheck(): Boolean {
        val bluetoothOk = checkBluetoothON()
        val locationOk = checkLocationON()
        return bluetoothOk && locationOk
    }

    private fun checkBluetoothON(): Boolean {
        val btManager = applicationContext.getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothEnabled = btManager?.adapter?.isEnabled == true // Corrected elvis usage
        if (!bluetoothEnabled) {
            // IMPORTANT: Replace with your actual string resources
            showExplanation(getString(R.string.bluetooth_disabled_title), getString(R.string.bluetooth_disabled_message))
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
            // IMPORTANT: Replace with your actual string resources
            showExplanation(getString(R.string.location_disabled_title), getString(R.string.location_disabled_message))
        }
        return locationEnabled
    }
    //endregion

    //region UI Helpers
    private fun showExplanation(title: String, message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }
    }

    private fun showPermissionDeniedExplanation(permission: String) {
        // IMPORTANT: Replace with your actual string resources
        val message = when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> getString(R.string.permission_denied_fine_location)
            Manifest.permission.BLUETOOTH_SCAN -> getString(R.string.permission_denied_bluetooth_scan)
            Manifest.permission.BLUETOOTH_CONNECT -> getString(R.string.permission_denied_bluetooth_connect)
            else -> getString(R.string.permission_denied_generic)
        }
        showExplanation(getString(R.string.permission_denied_title), message) // IMPORTANT: Replace
    }
    //endregion

    //region Broadcast Receivers
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
                val previousBluetoothState = bluetoothEnabled
                bluetoothEnabled = (state == BluetoothAdapter.STATE_ON)

                if (previousBluetoothState != bluetoothEnabled) {
                    if (!bluetoothEnabled && mainViewModel.scanningState.value == true) {
                        stopBeaconScanningInternal()
                        // IMPORTANT: Replace with your actual string resources
                        showExplanation(getString(R.string.bluetooth_disabled_title), getString(R.string.bluetooth_turned_off_scanning_stopped))
                    }
                }
            }
        }
    }

    inner class LocationStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val previousLocationState = locationEnabled
                val locManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
                locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locManager.isLocationEnabled
                } else {
                    @Suppress("DEPRECATION")
                    locManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }

                if (previousLocationState != locationEnabled) {
                    if (!locationEnabled && mainViewModel.scanningState.value == true) {
                        stopBeaconScanningInternal()
                        // IMPORTANT: Replace with your actual string resources
                        showExplanation(getString(R.string.location_disabled_title), getString(R.string.location_turned_off_scanning_stopped))
                    }
                }
            }
        }
    }
    //endregion

    //region Companion Object
    companion object {
        private const val TAG = "MainActivity"

        private const val INITIAL_LATITUDE = 52.2204685
        private const val INITIAL_LONGITUDE = 21.0101522
        private const val INITIAL_ZOOM_LEVEL = 19.5

        private const val PERMISSION_REQUEST_CODE = 101
        private const val BEACON_REGION_ID = "all-beacons-region"

        private val MARKER_DIMENSIONS_GREY = Pair(12, 12)
        private val MARKER_DIMENSIONS_RED = Pair(18, 18)
        private val MARKER_DIMENSIONS_GREEN = Pair(25, 25)
        private val MARKER_DIMENSIONS_USER = Pair(60, 80)
    }
    //endregion
}