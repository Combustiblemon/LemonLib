package org.combustiblelemon.lemonlib.geometry

enum class SmoothingMethod {
    SIMPLE,   // Uses edge smoothing by repositioning points
    SPLINE,   // Uses Laplacian smoothing for natural shape smoothing
    NONE      // Uses minimal smoothing for very subtle adjustments
}

/**
 * CPU-side polygon smoothing for the shield outline. All methods reposition
 * existing points only — no new points are added.
 */
object PolygonSmoothing {

    fun smooth(points: List<Float>, method: SmoothingMethod, subdivisions: Int): List<Float> {
        return when (method) {
            SmoothingMethod.SPLINE -> subdividePointsWithSplines(points, subdivisions)
            SmoothingMethod.SIMPLE -> smoothEdgesSimple(points, subdivisions)
            SmoothingMethod.NONE -> smoothEdgesMinimal(points, 2) // Minimal curve smoothing
        }
    }

    private fun subdividePointsWithSplines(points: List<Float>, subdivisions: Int = 8): List<Float> {
        val result = mutableListOf<Float>()
        val numPoints = points.size / 2

        if (numPoints < 3) return points.toList() // Need at least 3 points for a polygon

        // Convert to pairs for easier handling
        val pairs = points.chunked(2) { Pair(it[0], it[1]) }

        // Apply Laplacian smoothing to existing points (no new points added)
        val smoothedPoints = laplacianSmoothing(pairs, subdivisions)

        // Return smoothed points as flat list
        for (point in smoothedPoints) {
            result.add(point.first)
            result.add(point.second)
        }

        return result
    }

    private fun laplacianSmoothing(points: List<Pair<Float, Float>>, iterations: Int): List<Pair<Float, Float>> {
        if (points.size < 3) return points

        var currentPoints = points.toMutableList()

        for (iteration in 0 until iterations) {
            val smoothedPoints = mutableListOf<Pair<Float, Float>>()

            for (i in currentPoints.indices) {
                val prev = currentPoints[(i - 1 + currentPoints.size) % currentPoints.size]
                val current = currentPoints[i]
                val next = currentPoints[(i + 1) % currentPoints.size]

                // Laplacian smoothing: move point towards the average of its neighbors
                val smoothingFactor = 0.3f // Controls how much smoothing is applied
                val smoothedX = current.first + smoothingFactor * ((prev.first + next.first) / 2f - current.first)
                val smoothedY = current.second + smoothingFactor * ((prev.second + next.second) / 2f - current.second)

                smoothedPoints.add(Pair(smoothedX, smoothedY))
            }

            currentPoints = smoothedPoints
        }

        return currentPoints
    }

    // Alternative smoothing method: simple-edge smoothing by repositioning points
    private fun smoothEdgesSimple(points: List<Float>, subdivisions: Int = 2): List<Float> {
        val result = mutableListOf<Float>()
        val numPoints = points.size / 2

        if (numPoints < 3) return points.toList()

        // Convert to pairs for easier handling
        val pairs = points.chunked(2) { Pair(it[0], it[1]) }

        // Apply simple smoothing by moving points towards edge midpoints
        val smoothedPoints = simpleEdgeSmoothing(pairs, subdivisions)

        // Return smoothed points as a flat list
        for (point in smoothedPoints) {
            result.add(point.first)
            result.add(point.second)
        }

        return result
    }

    private fun simpleEdgeSmoothing(points: List<Pair<Float, Float>>, iterations: Int): List<Pair<Float, Float>> {
        if (points.size < 3) return points

        var currentPoints = points.toMutableList()

        for (iteration in 0 until iterations) {
            val smoothedPoints = mutableListOf<Pair<Float, Float>>()

            for (i in currentPoints.indices) {
                val current = currentPoints[i]
                val next = currentPoints[(i + 1) % currentPoints.size]

                // Move point slightly towards the midpoint of the edge
                val smoothingFactor = 0.2f // Gentle smoothing
                val edgeMidX = (current.first + next.first) / 2f
                val edgeMidY = (current.second + next.second) / 2f

                val smoothedX = current.first + smoothingFactor * (edgeMidX - current.first)
                val smoothedY = current.second + smoothingFactor * (edgeMidY - current.second)

                smoothedPoints.add(Pair(smoothedX, smoothedY))
            }

            currentPoints = smoothedPoints
        }

        return currentPoints
    }

    // Minimal smoothing method that creates very subtle shape smoothing
    private fun smoothEdgesMinimal(points: List<Float>, subdivisions: Int = 2): List<Float> {
        val result = mutableListOf<Float>()
        val numPoints = points.size / 2

        if (numPoints < 3) return points.toList()

        // Convert to pairs for easier handling
        val pairs = points.chunked(2) { Pair(it[0], it[1]) }

        // Apply very subtle smoothing by slightly adjusting point positions
        val smoothedPoints = minimalSmoothing(pairs, subdivisions)

        // Return smoothed points as a flat list
        for (point in smoothedPoints) {
            result.add(point.first)
            result.add(point.second)
        }

        return result
    }

    private fun minimalSmoothing(points: List<Pair<Float, Float>>, iterations: Int): List<Pair<Float, Float>> {
        if (points.size < 3) return points

        var currentPoints = points.toMutableList()

        for (iteration in 0 until iterations) {
            val smoothedPoints = mutableListOf<Pair<Float, Float>>()

            for (i in currentPoints.indices) {
                val prev = currentPoints[(i - 1 + currentPoints.size) % currentPoints.size]
                val current = currentPoints[i]
                val next = currentPoints[(i + 1) % currentPoints.size]

                // Very subtle smoothing: move point slightly towards the average of neighbors
                val smoothingFactor = 0.1f // Very gentle smoothing
                val avgX = (prev.first + next.first) / 2f
                val avgY = (prev.second + next.second) / 2f

                val smoothedX = current.first + smoothingFactor * (avgX - current.first)
                val smoothedY = current.second + smoothingFactor * (avgY - current.second)

                smoothedPoints.add(Pair(smoothedX, smoothedY))
            }

            currentPoints = smoothedPoints
        }

        return currentPoints
    }
}
