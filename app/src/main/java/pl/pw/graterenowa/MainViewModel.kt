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
import pl.pw.graterenowa.utils.MapUtils
import kotlin.math.pow

private const val TAG_VM = "MainViewModel"
private const val INITIAL_LATITUDE_VM = 52.2204685
private const val INITIAL_LONGITUDE_VM = 21.0101522
private const val DEFAULT_TX_POWER_VM = -59.0
private const val DEFAULT_PATH_LOSS_EXPONENT_VM = 3.5
private const val MIN_BEACON_DISTANCE_FOR_POSITIONING_VM = 0.1
private const val MAX_BEACON_DISTANCE_RELEVANCE_VM = 50.0

private val BEACON_JSON_FILES_VM = listOf(
    "beacons_gg0.txt", "beacons_gg1.txt", "beacons_gg2b1.txt",
    "beacons_gg3b2.txt", "beacons_gg3b3.txt", "beacons_gg4.txt", "beacons_gg_out.txt"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _currentPosition = MutableLiveData(GeoPoint(INITIAL_LATITUDE_VM, INITIAL_LONGITUDE_VM))
    val currentPosition: LiveData<GeoPoint> = _currentPosition

    private val _scanningState = MutableLiveData(false)
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

    private val _lastDetectedBeacons = MutableLiveData<List<Beacon>>(emptyList())
    val lastDetectedBeacons: LiveData<List<Beacon>> = _lastDetectedBeacons

    private var internalBeaconMap: Map<String, BeaconData> = emptyMap()

    var txPower: Double = DEFAULT_TX_POWER_VM
    var pathLossExponent: Double = DEFAULT_PATH_LOSS_EXPONENT_VM

    init {
        loadReferenceBeacons()
    }

    private fun loadReferenceBeacons() {
        val loadedBeacons = mutableMapOf<String, BeaconData>()
        BEACON_JSON_FILES_VM.forEach { fileName ->
            try {
                getApplication<Application>().assets.open(fileName).bufferedReader().use { reader ->
                    Gson().fromJson(reader.readText(), BeaconResponse::class.java).items
                        .forEach { beaconData -> loadedBeacons[beaconData.beaconUid] = beaconData }
                }
            } catch (e: Exception) {
                Log.e(TAG_VM, "Error loading reference beacons from $fileName", e)
                _toastMessage.postValue("Error loading beacon data: $fileName")
            }
        }
        internalBeaconMap = loadedBeacons.toMap()
        _beaconMapData.postValue(internalBeaconMap)
        Log.d(TAG_VM, "Loaded ${internalBeaconMap.size} reference beacons.")
    }

    fun updateScanningState(isScanning: Boolean) {
        _scanningState.value = isScanning
        if (!isScanning) {
            _greenBeaconIds.value = emptySet()
            _currentFloor.value?.let { floor ->
                updateRedBeaconIdsInternal(floor, emptySet())
            } ?: run { _redBeaconIds.value = emptySet() }
            _lastDetectedBeacons.value = emptyList()
        }
    }

    fun processBeaconRangingUpdate(beacons: Collection<Beacon>) {
        if (_scanningState.value != true) {
            _lastDetectedBeacons.value = emptyList()
            return
        }
        _lastDetectedBeacons.value = beacons.toList()

        val (detectedBeaconIdsInProximity, detectedFloorCandidate) = analyzeDetectedBeacons(beacons)
        _greenBeaconIds.value = detectedBeaconIdsInProximity

        val previousFloor = _currentFloor.value
        var floorToSet = detectedFloorCandidate

        if (detectedFloorCandidate == null && previousFloor != null) {
            floorToSet = previousFloor
        }

        if (floorToSet != previousFloor && floorToSet != null) {
            _currentFloor.value = floorToSet
        }

        if (floorToSet != null) {
            updateRedBeaconIdsInternal(floorToSet, detectedBeaconIdsInProximity)
        } else {
            _redBeaconIds.value = emptySet()
        }

        val relevantBeaconsForPositioning = beacons.filter {
            detectedBeaconIdsInProximity.contains(it.bluetoothAddress)
        }
        updatePosition(relevantBeaconsForPositioning)
    }

    private fun analyzeDetectedBeacons(beacons: Collection<Beacon>): Pair<Set<String>, Int?> {
        val detectedBeaconDetails = mutableListOf<Pair<BeaconData, Double>>()

        beacons.forEach { beacon ->
            internalBeaconMap[beacon.bluetoothAddress]?.let { beaconData ->
                val distance = MapUtils.rssiToDistance(beacon.rssi, txPower, pathLossExponent)
                if (distance < MAX_BEACON_DISTANCE_RELEVANCE_VM) {
                    detectedBeaconDetails.add(Pair(beaconData, distance))
                }
            }
        }

        if (detectedBeaconDetails.isEmpty()) {
            return Pair(emptySet(), null)
        }

        val closestBeaconData = detectedBeaconDetails.minByOrNull { it.second }?.first
        val currentFloorCandidate = closestBeaconData?.floorId

        val greenIds = detectedBeaconDetails
            .filter { (data, _) -> data.floorId == currentFloorCandidate || currentFloorCandidate == null }
            .map { it.first.beaconUid }
            .toSet()

        return Pair(greenIds, currentFloorCandidate)
    }

    private fun updateRedBeaconIdsInternal(currentFloorVal: Int, currentGreenBeaconIds: Set<String>) {
        val newRedBeaconIds = internalBeaconMap.filter { (id, data) ->
            data.floorId == currentFloorVal && id !in currentGreenBeaconIds
        }.keys
        _redBeaconIds.value = newRedBeaconIds
    }

    private fun updatePosition(beaconsForPositioning: Collection<Beacon>) {
        val validBeaconMeasurements = beaconsForPositioning.mapNotNull { beacon ->
            internalBeaconMap[beacon.bluetoothAddress]?.let { beaconData ->
                val distance = MapUtils.rssiToDistance(beacon.rssi, txPower, pathLossExponent)
                    .coerceAtLeast(MIN_BEACON_DISTANCE_FOR_POSITIONING_VM)
                Triple(beaconData.latitude, beaconData.longitude, distance)
            }
        }

        if (validBeaconMeasurements.isEmpty()) {
            Log.w(TAG_VM, "UpdatePosition called with no valid beacons for positioning.")
            return
        }

        var totalWeight = 0.0
        var weightedLatSum = 0.0
        var weightedLonSum = 0.0

        validBeaconMeasurements.forEach { (lat, lon, distance) ->
            val weight = 1.0 / (distance.pow(2).coerceAtLeast(1e-9))
            weightedLatSum += lat * weight
            weightedLonSum += lon * weight
            totalWeight += weight
        }

        if (totalWeight > 1e-9) {
            val newLat = weightedLatSum / totalWeight
            val newLon = weightedLonSum / totalWeight
            _currentPosition.value = GeoPoint(newLat, newLon)
            Log.d(TAG_VM, "ViewModel Position updated to: Lat=$newLat, Lon=$newLon using ${validBeaconMeasurements.size} beacons.")
        } else {
            Log.w(TAG_VM, "Total weight is too small or zero, cannot calculate position. Measurements: ${validBeaconMeasurements.size}")
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }
}