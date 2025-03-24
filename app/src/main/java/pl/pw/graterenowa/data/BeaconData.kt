package pl.pw.graterenowa.data

data class BeaconData(
    val id: Int,
    val longitude: Double,
    val latitude: Double,
    val numberOnFloor: Int,
    val beaconUid: String,
    val floorId: Int,
    val buildingShortName: String?,
    val roomPlaced: Boolean,
    val nearFloorChange: Boolean,
    val txPowerToSet: Int
)
