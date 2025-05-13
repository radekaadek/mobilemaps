package pl.pw.graterenowa.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

object MapUtils {

    const val STROKE_WIDTH_RSSI_SCALE = 0.5f
    const val RSSI_MAX_VALUE = 110
    const val RSSI_RANGE = 110
    const val CIRCLE_STROKE_WIDTH = 3f

    fun rssiToDistance(rssi: Int, txPower: Double, pathLossExponent: Double): Double {
        if (pathLossExponent == 0.0) return Double.POSITIVE_INFINITY
        return 10.0.pow((txPower - rssi) / (10 * pathLossExponent))
    }

    fun getCircle(center: GeoPoint, radiusMeters: Double): Polyline {
        val earthRadius = 6378137.0
        val centerLatRad = Math.toRadians(center.latitude)
        val points = mutableListOf<GeoPoint>()

        for (i in 0..360 step 10) {
            val angleRad = Math.toRadians(i.toDouble())
            val latOffset = radiusMeters * cos(angleRad) / earthRadius
            val lonOffset = radiusMeters * sin(angleRad) / (earthRadius * cos(centerLatRad))
            points.add(
                GeoPoint(
                    center.latitude + Math.toDegrees(latOffset),
                    center.longitude + Math.toDegrees(lonOffset)
                )
            )
        }
        if (points.isNotEmpty()) {
            points.add(points.first())
        }

        return Polyline().apply {
            setPoints(points)
            outlinePaint.strokeWidth = CIRCLE_STROKE_WIDTH
        }
    }

    fun getScaledDrawable(context: Context, drawableId: Int, width: Int, height: Int): Drawable? {
        return try {
            ContextCompat.getDrawable(context, drawableId)?.let { originalDrawable ->
                val bitmap = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
                val canvas = Canvas(bitmap)
                originalDrawable.setBounds(0, 0, canvas.width, canvas.height)
                originalDrawable.draw(canvas)
                bitmap.toDrawable(context.resources)
            }
        } catch (e: Exception) {
            Log.e("MapUtils", "Error scaling drawable $drawableId", e)
            null
        }
    }
}