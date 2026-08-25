package org.combustiblelemon.lemonlib.geometry

import org.lwjgl.util.vector.Vector2f
import java.awt.geom.AffineTransform
import kotlin.math.abs
import kotlin.math.round

fun rotatePointByDegrees(point: Vector2f, center: Vector2f, degrees: Float): Vector2f {
    val pt = doubleArrayOf(point.x.toDouble(), point.y.toDouble())
    AffineTransform.getRotateInstance(
        Math.toRadians(degrees.toDouble()),
        center.x.toDouble(),
        center.y.toDouble()
    ).transform(pt, 0, pt, 0, 1)

    return Vector2f(
        pt[0].toFloat(),
        pt[1].toFloat(),
    )
}

fun normalize(value: Double, min: Double, max: Double): Float {
    return ((value - min) / (max - min)).toFloat()
}

fun getPercentage(number: Float): Int {
    return abs(round((number - 1f) * 100f)).toInt()
}

/**
 * Checks if a point is inside a polygon using ray casting algorithm
 * @param point The point to check
 * @param polygon Array of Vector2f defining the polygon vertices
 * @return true if point is inside the polygon
 */
fun isPointInPolygon(point: Vector2f, polygon: Array<Vector2f>): Boolean {
    var inside = false
    var j = polygon.size - 1

    for (i in polygon.indices) {
        if ((polygon[i].y > point.y) != (polygon[j].y > point.y) &&
            point.x < (polygon[j].x - polygon[i].x) * (point.y - polygon[i].y) /
            (polygon[j].y - polygon[i].y) + polygon[i].x
        ) {
            inside = !inside
        }
        j = i
    }

    return inside
}

/**
 * Generates random points inside a polygon (works with concave shapes)
 * @param polygon Array of Vector2f points defining the polygon vertices
 * @param pointCount Number of points to generate (default 2)
 * @return Array of Vector2f containing the generated points
 */
fun getRandomPointsInPolygon(polygon: Array<Vector2f>, pointCount: Int = 2): Array<Vector2f> {
    // Find bounding box
    val minX = polygon.minOf { it.x }
    val maxX = polygon.maxOf { it.x }
    val minY = polygon.minOf { it.y }
    val maxY = polygon.maxOf { it.y }

    val results = mutableListOf<Vector2f>()

    while (results.size < pointCount) {
        // Generate random point within bounding box
        val x = minX + (maxX - minX) * Math.random().toFloat()
        val y = minY + (maxY - minY) * Math.random().toFloat()
        val point = Vector2f(x, y)

        // Check if point is inside polygon using ray casting algorithm
        if (isPointInPolygon(point, polygon)) {
            results.add(point)
        }
    }

    return results.toTypedArray()
}

/**
 * Calculates the perpendicular intersection point between a line segment and a point
 * @param segment Array of two Vector2f representing the line segment endpoints
 * @param point The point to find the perpendicular intersection from
 * @return Vector2f representing the intersection point, or null if the perpendicular point lies outside the segment
 */
fun getPerpendicularIntersectionPoint(segment: Array<Vector2f>, point: Vector2f): Vector2f? {
    if (segment.size != 2) return null

    val p1 = segment[0]
    val p2 = segment[1]

    // Calculate the vector from p1 to p2
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y

    // Calculate the squared length of the line segment
    val squaredLength = dx * dx + dy * dy

    // If the segment has zero length, return null
    if (squaredLength == 0f) return null

    // Calculate the projection of the point onto the line
    val t = ((point.x - p1.x) * dx + (point.y - p1.y) * dy) / squaredLength

    // If t is outside [0,1], the perpendicular point lies outside the segment
    if (t < 0f || t > 1f) return null

    // Calculate the intersection point
    val intersectionX = p1.x + t * dx
    val intersectionY = p1.y + t * dy

    return Vector2f(intersectionX, intersectionY)
}

/**
 * Calculates a point along a line segment at a specified percentage from the end point
 * @param segment Array of two Vector2f representing the line segment endpoints
 * @param percentage Float between 0 and 1 representing the percentage from the end point (second point)
 * @return Vector2f representing the point along the segment, or null if the segment is invalid
 */
fun getPointAlongSegmentFromEnd(segment: Array<Vector2f>, percentage: Float): Vector2f? {
    if (segment.size != 2) return null
    if (percentage < 0f || percentage > 1f) return null

    val endPoint = segment[1]
    val startPoint = segment[0]

    // Calculate the vector from end to start
    val dx = startPoint.x - endPoint.x
    val dy = startPoint.y - endPoint.y

    // Calculate the point at the specified percentage
    val x = endPoint.x + dx * percentage
    val y = endPoint.y + dy * percentage

    return Vector2f(x, y)
}

/**
 * Checks if a perpendicular intersection point exists between a line segment and a point
 * @param segment Array of two Vector2f representing the line segment endpoints
 * @param point The point to check for perpendicular intersection
 * @return true if a perpendicular intersection point exists on the segment, false otherwise
 */
fun hasPerpendicularIntersectionPoint(segment: Array<Vector2f>, point: Vector2f): Boolean {
    if (segment.size != 2) return false

    val p1 = segment[0]
    val p2 = segment[1]

    // Calculate the vector from p1 to p2
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y

    // Calculate the squared length of the line segment
    val squaredLength = dx * dx + dy * dy

    // If the segment has zero length, return false
    if (squaredLength == 0f) return false

    // Calculate the projection of the point onto the line
    val t = ((point.x - p1.x) * dx + (point.y - p1.y) * dy) / squaredLength

    // Return true if t is within [0,1], meaning the perpendicular point lies on the segment
    return t in 0f..1f
}
