package pl.pw.graterenowa

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import org.altbeacon.beacon.Beacon
import org.osmdroid.util.GeoPoint
import pl.pw.graterenowa.data.BeaconData
import pl.pw.graterenowa.data.BeaconResponse
import kotlin.math.pow

// Constants that might be shared or needed by ViewModel
private const val TAG_VM = "MainViewModel"
private const val INITIAL_LATITUDE_VM = 52.2204685
private const val INITIAL_LONGITUDE_VM = 21.0101522
private const val TX_POWER_VM = -59.0
private const val PATH_LOSS_EXPONENT_VM = 4.0
private const val MIN_BEACON_DISTANCE_VM = 0.1
private val BEACON_JSON_FILES_VM = listOf(
    "beacons_gg0.txt",
    "beacons_gg1.txt",
    "beacons_gg2b1.txt",
    "beacons_gg3b2.txt",
    "beacons_gg3b3.txt",
    "beacons_gg4.txt",
    "beacons_gg_out.txt"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- LiveData ---
    private val _currentPosition = MutableLiveData<GeoPoint>(GeoPoint(INITIAL_LATITUDE_VM, INITIAL_LONGITUDE_VM))
    val currentPosition: LiveData<GeoPoint> = _currentPosition

    private val _scanningState = MutableLiveData<Boolean>(false)
    val scanningState: LiveData<Boolean> = _scanningState

    private val _beaconMapData = MutableLiveData<Map<String, BeaconData>>(emptyMap())
    val beaconMapData: LiveData<Map<String, BeaconData>> = _beaconMapData

    private val _greenBeaconIds = MutableLiveData<Set<String>>(emptySet())
    val greenBeaconIds: LiveData<Set<String>> = _greenBeaconIds

    private val _redBeaconIds = MutableLiveData<Set<String>>(emptySet())
    val redBeaconIds: LiveData<Set<String>> = _redBeaconIds

    private val _currentFloor = MutableLiveData<Int?>(null)
    val currentFloor: LiveData<Int?> = _currentFloor

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    // --- Properties ---
    // Keep beaconMap internal to the ViewModel for now, expose as LiveData if needed for direct observation of the map itself
    private var internalBeaconMap: Map<String, BeaconData> = emptyMap()
    private var internalGreenBeaconIds: MutableSet<String> = mutableSetOf()
    private var internalRedBeaconIds: MutableSet<String> = mutableSetOf()


    init {
        loadReferenceBeacons()
    }

    private fun loadReferenceBeacons() {
        internalBeaconMap = BEACON_JSON_FILES_VM.flatMap { fileName ->
            try {
                getApplication<Application>().assets.open(fileName).bufferedReader().use { reader ->
                    Gson().fromJson(reader.readText(), BeaconResponse::class.java).items
                }
            } catch (e: Exception) {
                Log.e(TAG_VM, "Error loading reference beacons from $fileName", e)
                _toastMessage.postValue("Error loading beacon data: $fileName")
                emptyList()
            }
        }.associateBy { it.beaconUid }
        _beaconMapData.postValue(internalBeaconMap)
        Log.d(TAG_VM, "Loaded ${internalBeaconMap.size} reference beacons.")
    }

    fun updateScanningState(isScanning: Boolean) {
        _scanningState.postValue(isScanning)
    }

    fun processBeaconRangingUpdate(beacons: Collection<Beacon>) {
        if (beacons.isEmpty()) {
            // Reset relevant LiveData if no beacons are found
            internalGreenBeaconIds.clear()
            _greenBeaconIds.postValue(internalGreenBeaconIds)
            // Potentially handle red beacons if floor logic dictates they change when no green beacons.
            return
        }

        updatePosition(beacons) // This will update _currentPosition LiveData

        val detectedBeaconIdsThisScan = mutableSetOf<String>()
        var floorDetectedThisScan: Int? = null

        beacons.forEach { beacon ->
            val beaconId = beacon.bluetoothAddress
            internalBeaconMap[beaconId]?.let { beaconData ->
                detectedBeaconIdsThisScan.add(beaconId)
                if (floorDetectedThisScan == null) { // Simple floor detection
                    floorDetectedThisScan = beaconData.floorId
                }
            }
        }

        // Update green beacons
        internalGreenBeaconIds = detectedBeaconIdsThisScan
        _greenBeaconIds.postValue(internalGreenBeaconIds)


        // Handle floor change and update red beacons
        val oldFloor = _currentFloor.value
        if (floorDetectedThisScan != null && floorDetectedThisScan != oldFloor) {
            _currentFloor.postValue(floorDetectedThisScan)
            updateRedBeaconIds(floorDetectedThisScan, detectedBeaconIdsThisScan)
        } else if (floorDetectedThisScan == null && _currentFloor.value != null) {
            // No floor detected this scan, but we had one. Re-evaluate red beacons for the current floor.
            updateRedBeaconIds(_currentFloor.value, detectedBeaconIdsThisScan)
        } else if (floorDetectedThisScan != null && floorDetectedThisScan == oldFloor) {
            // Floor is the same, but green beacons might have changed, so re-evaluate red.
            updateRedBeaconIds(floorDetectedThisScan, detectedBeaconIdsThisScan)
        }


    }
    private fun updateRedBeaconIds(currentFloorVal: Int?, detectedGreenIds: Set<String>) {
        val newRedBeaconIds = mutableSetOf<String>()
        if (currentFloorVal != null) {
            internalBeaconMap.forEach { (id, data) ->
                if (data.floorId == currentFloorVal && id !in detectedGreenIds) {
                    newRedBeaconIds.add(id)
                }
            }
        }
        internalRedBeaconIds = newRedBeaconIds
        _redBeaconIds.postValue(internalRedBeaconIds)
    }


    private fun updatePosition(beacons: Collection<Beacon>) {
        val validBeacons = beacons.mapNotNull { beacon ->
            internalBeaconMap[beacon.bluetoothAddress]?.let { beaconData ->
                val distance = beacon.distance.coerceAtLeast(MIN_BEACON_DISTANCE_VM)
                Triple(beaconData.latitude, beaconData.longitude, distance)
            }
        }

        if (validBeacons.isEmpty()) {
            Log.w(TAG_VM, "UpdatePosition called with no matching beacons in beaconMap.")
            return
        }

        if (validBeacons.size == 1) {
            Log.d(TAG_VM, "Only one beacon detected, position not updated by ViewModel.")
            // currentPosition remains the same, or you could decide to move towards the single beacon:
            // _currentPosition.postValue(GeoPoint(validBeacons[0].first, validBeacons[0].second))
        } else {
            var totalWeight = 0.0
            var weightedLat = 0.0
            var weightedLon = 0.0

            validBeacons.forEach { (lat, lon, distance) ->
                val weight = 1.0 / (distance + 1e-6)
                weightedLat += lat * weight
                weightedLon += lon * weight
                totalWeight += weight
            }

            if (totalWeight > 0) {
                val newLat = weightedLat / totalWeight
                val newLon = weightedLon / totalWeight
                _currentPosition.postValue(GeoPoint(newLat, newLon))
                Log.d(TAG_VM, "ViewModel Position updated to: Lat=$newLat, Lon=$newLon")
            } else {
                Log.w(TAG_VM, "Total weight is zero, cannot calculate position via ViewModel.")
            }
        }
    }

    fun clearToastMessage() {
        _toastMessage.postValue(null)
    }

    // Add other logic from MainActivity that should reside in ViewModel
    // e.g., complex calculations, data transformations, business logic for beacon interactions.
}