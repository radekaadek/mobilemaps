package pl.pw.graterenowa

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Region
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.collections.forEach
import kotlin.math.cos
import kotlin.math.sin

class MainViewModel(app: Application, mapView: MapView) : AndroidViewModel(app) {

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

    private val beaconManager = BeaconManager.getInstanceForApplication(app)

    // Reference beacon locations (gray markers)
    private val _referenceBeacons = MutableLiveData<Map<String, GeoPoint>>()
    val referenceBeacons: LiveData<Map<String, GeoPoint>> = _referenceBeacons

    // Raw ranging updates
    private val _scannedBeacons = MutableLiveData<List<Beacon>>()
    val scannedBeacons: LiveData<List<Beacon>> = _scannedBeacons

    // Estimated current position
    private val _currentPosition = MutableLiveData<GeoPoint?>()
    val currentPosition: LiveData<GeoPoint?> = _currentPosition

    // Lines from estimated position to each detected beacon
    private val _polylines = MutableLiveData<List<Polyline>>()
    val polylines: LiveData<List<Polyline>> = _polylines

    // Circles around each detected beacon (as filled polygons)
    private val _circles = MutableLiveData<List<Polyline>>()
    val circles: LiveData<List<Polyline>> = _circles

    // Which beacon IDs should be drawn “green” vs “red”
    private val _greenBeaconIds = MutableLiveData<Set<String>>()
    val greenBeaconIds: LiveData<Set<String>> = _greenBeaconIds
    private val _redBeaconIds = MutableLiveData<Set<String>>()
    val redBeaconIds: LiveData<Set<String>> = _redBeaconIds

    // Scanning flag
    private val _scanning = MutableLiveData(false)
    val scanning: LiveData<Boolean> = _scanning

    init {
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(BeaconParser.ALTBEACON_LAYOUT))
    }

    fun loadReferenceBeacons(beacons: Map<String, GeoPoint>) {
        _referenceBeacons.value = beacons
    }

    fun onBeaconServiceConnect() {
        val region = Region("all-beacons", null, null, null)
        beaconManager.addRangeNotifier { beacons, _ ->
            _scannedBeacons.postValue(beacons.toList())
            updateVisualization(beacons)
        }
        beaconManager.startRangingBeacons(region)
        _scanning.postValue(true)
    }

    fun stopScanning() {
        val region = Region("all-beacons", null, null, null)
        beaconManager.stopRangingBeacons(region)
        _scanning.postValue(false)
    }

    private fun updateVisualization(beacons: Collection<Beacon>) {
        val refs = _referenceBeacons.value ?: return
        val greenIds = mutableSetOf<String>()
        val newPolylines = mutableListOf<Polyline>()
        val newCircles = mutableListOf<Polyline>()

        // Mark which beacons are in range
        beacons.forEach { b ->
            val id = b.id1.toString()
            val pt = refs[id] ?: return@forEach
            greenIds += id

            // Build a simple circle polygon around each beacon
            val circle = getCircle(pt, 100.0)
            newCircles.add(circle)
        }

        // Red ones are those defined but not seen
        val redIds = refs.keys - greenIds
    }

    private fun estimatePosition(
        beacons: Collection<Beacon>,
        refs: Map<String, GeoPoint>
    ): GeoPoint? {
        val weighted = beacons.mapNotNull { b ->
            refs[b.id1.toString()]?.let { p ->
                val w = 1.0 / (b.distance * b.distance + 0.0001)
                Triple(p.latitude * w, p.longitude * w, w)
            }
        }
        if (weighted.isEmpty()) return null
        val sumW = weighted.sumOf { it.third }
        val lat = weighted.sumOf { it.first } / sumW
        val lon = weighted.sumOf { it.second } / sumW
        return GeoPoint(lat, lon)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
