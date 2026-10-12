package org.combustiblelemon.lemonlib.geometry

enum class SmoothingMethod {
    SIMPLE,   // Uses edge smoothing by repositioning points
    SPLINE,   // Uses Laplacian smoothing for natural shape smoothing (shrinks convex outlines)
    TAUBIN,   // Laplacian shrink pass + inflate pass per iteration: smooths without shrinking
    NONE      // Uses minimal smoothing for very subtle adjustments
}

/**
 * CPU-side polygon smoothing for the shield outline. [smooth], [taubin] and
 * [offsetAlongNormals] reposition existing points only; [catmullRomAdaptive] adds points to
 * round corners (bounded by a caller-supplied budget). Points are a flat `[x0, y0, x1, y1, ...]`
 * list describing a closed polygon (last point connects back to the first).
 */
object PolygonSmoothing {

    /** Taubin defaults (Taubin 1995): shrink by [TAUBIN_LAMBDA], then inflate by [TAUBIN_MU]. */
    const val TAUBIN_LAMBDA = 0.5f
    const val TAUBIN_MU = -0.53f

    fun smooth(points: List<Float>, method: SmoothingMethod, subdivisions: Int): List<Float> {
        return when (method) {
            SmoothingMethod.SPLINE -> subdividePointsWithSplines(points, subdivisions)
            SmoothingMethod.TAUBIN -> taubin(points, subdivisions, TAUBIN_LAMBDA, TAUBIN_MU)
            SmoothingMethod.SIMPLE -> smoothEdgesSimple(points, subdivisions)
            SmoothingMethod.NONE -> smoothEdgesMinimal(points, 2) // Minimal curve smoothing
        }
    }

    /**
     * Taubin smoothing: each iteration runs one Laplacian step with factor [lambda] (shrinks)
     * followed by one with factor [mu] (negative, inflates). With `mu < -lambda` the low
     * frequencies (overall size) are preserved while the high frequencies (corner jaggies) are
     * damped, so the outline rounds off without pulling inside the original bounds.
     */
    fun taubin(points: List<Float>, iterations: Int, lambda: Float = TAUBIN_LAMBDA, mu: Float = TAUBIN_MU): List<Float> {
        if (points.size < 6) return points.toList()
        var current = points.chunked(2) { Pair(it[0], it[1]) }
        for (i in 0 until iterations) {
            current = laplacianStep(current, lambda)
            current = laplacianStep(current, mu)
        }
        return current.flatMap { listOf(it.first, it.second) }
    }

    /**
     * Pushes every vertex of a closed polygon outward along its vertex normal by [distance]
     * (negative pulls inward). The vertex normal is the normalised sum of the two adjacent edge
     * normals, scaled so that both adjacent edges move by exactly [distance] (a mitre), with the
     * mitre length capped at `2 * distance` so very sharp corners do not spike. "Outward" is
     * derived from the polygon's signed area, so winding order does not matter.
     */
    fun offsetAlongNormals(points: List<Float>, distance: Float): List<Float> {
        if (points.size < 6 || distance == 0f) return points.toList()
        val pts = points.chunked(2) { Pair(it[0], it[1]) }
        val n = pts.size
        // Shoelace: positive = counter-clockwise, for which the outward edge normal is (dy, -dx).
        var area2 = 0f
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % n]
            area2 += a.first * b.second - b.first * a.second
        }
        val sign = if (area2 >= 0f) 1f else -1f
        val maxLength = 2f * kotlin.math.abs(distance)
        val result = ArrayList<Float>(points.size)
        for (i in 0 until n) {
            val prev = pts[(i - 1 + n) % n]; val cur = pts[i]; val next = pts[(i + 1) % n]
            val (n1x, n1y) = edgeNormal(prev, cur, sign)
            val (n2x, n2y) = edgeNormal(cur, next, sign)
            var mx = n1x + n2x
            var my = n1y + n2y
            val len = kotlin.math.sqrt(mx * mx + my * my)
            if (len < 1e-5f) {
                // Edges fold back on themselves; fall back to one edge normal.
                mx = n1x; my = n1y
            } else {
                mx /= len; my /= len
            }
            // Mitre: scale so each adjacent edge is displaced by `distance`.
            val cosHalf = mx * n1x + my * n1y
            var scale = if (cosHalf > 1e-3f) distance / cosHalf else distance
            if (kotlin.math.abs(scale) > maxLength) scale = maxLength * kotlin.math.sign(distance)
            result.add(cur.first + mx * scale)
            result.add(cur.second + my * scale)
        }
        return result
    }

    /**
     * Rounds the corners of a closed polygon with a centripetal Catmull-Rom spline that passes
     * through every input point. Points are *added*: each edge is split into 1..[maxPerEdge]
     * segments depending on the sharper turn angle at its two ends (one extra segment per
     * [degreesPerSegment] of turn), so straight runs stay cheap and sharp corners get the samples.
     * If the total would exceed [maxPoints] every edge's segment count is scaled down
     * proportionally (never below 1), so the result has at most [maxPoints] points and never
     * fewer than the input. Centripetal parameterisation (alpha 0.5) avoids cusps and overshoot.
     */
    fun catmullRomAdaptive(
        points: List<Float>,
        maxPoints: Int,
        degreesPerSegment: Float = 15f,
        maxPerEdge: Int = 6
    ): List<Float> {
        val pts = points.chunked(2) { Pair(it[0], it[1]) }
        val n = pts.size
        if (n < 3 || maxPoints <= n || maxPerEdge <= 1 || degreesPerSegment <= 0f) return points.toList()

        // Turn angle at each vertex, degrees in [0, 180].
        val turn = FloatArray(n) { i ->
            val a = pts[(i - 1 + n) % n]; val b = pts[i]; val c = pts[(i + 1) % n]
            val ux = b.first - a.first; val uy = b.second - a.second
            val vx = c.first - b.first; val vy = c.second - b.second
            val lu = kotlin.math.sqrt(ux * ux + uy * uy); val lv = kotlin.math.sqrt(vx * vx + vy * vy)
            if (lu < 1e-6f || lv < 1e-6f) 0f
            else {
                val cos = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1f, 1f)
                Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
            }
        }
        // Segments per edge i (from vertex i to i+1).
        val segs = IntArray(n) { i ->
            val t = maxOf(turn[i], turn[(i + 1) % n])
            kotlin.math.ceil(t / degreesPerSegment).toInt().coerceIn(1, maxPerEdge)
        }
        var total = segs.sum()
        if (total > maxPoints) {
            // Scale the extra segments down proportionally; floor keeps us under budget.
            val extra = total - n
            val budget = maxPoints - n
            for (i in 0 until n) {
                segs[i] = 1 + ((segs[i] - 1).toFloat() * budget / extra).toInt()
            }
            total = segs.sum()
        }

        val result = ArrayList<Float>(total * 2)
        for (i in 0 until n) {
            val p0 = pts[(i - 1 + n) % n]; val p1 = pts[i]; val p2 = pts[(i + 1) % n]; val p3 = pts[(i + 2) % n]
            val count = segs[i]
            result.add(p1.first); result.add(p1.second)
            if (count > 1) {
                // Centripetal knots.
                val t0 = 0f
                val t1 = t0 + knot(p0, p1)
                val t2 = t1 + knot(p1, p2)
                val t3 = t2 + knot(p2, p3)
                if (t2 - t1 < 1e-6f) continue // degenerate edge: no interior samples
                for (k in 1 until count) {
                    val t = t1 + (t2 - t1) * k / count
                    val a1 = lerp(p0, p1, t0, t1, t); val a2 = lerp(p1, p2, t1, t2, t); val a3 = lerp(p2, p3, t2, t3, t)
                    val b1 = lerp(a1, a2, t0, t2, t); val b2 = lerp(a2, a3, t1, t3, t)
                    val c = lerp(b1, b2, t1, t2, t)
                    result.add(c.first); result.add(c.second)
                }
            }
        }
        return result
    }

    private fun knot(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = b.first - a.first; val dy = b.second - a.second
        // alpha = 0.5 -> sqrt of the distance
        return kotlin.math.sqrt(kotlin.math.sqrt(dx * dx + dy * dy)).coerceAtLeast(1e-4f)
    }

    private fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, ta: Float, tb: Float, t: Float): Pair<Float, Float> {
        val d = tb - ta
        if (kotlin.math.abs(d) < 1e-7f) return a
        val w = (t - ta) / d
        return Pair(a.first + (b.first - a.first) * w, a.second + (b.second - a.second) * w)
    }

    private fun edgeNormal(a: Pair<Float, Float>, b: Pair<Float, Float>, sign: Float): Pair<Float, Float> {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return Pair(0f, 0f)
        return Pair(sign * dy / len, -sign * dx / len)
    }

    private fun laplacianStep(points: List<Pair<Float, Float>>, factor: Float): List<Pair<Float, Float>> {
        val n = points.size
        return List(n) { i ->
            val prev = points[(i - 1 + n) % n]
            val cur = points[i]
            val next = points[(i + 1) % n]
            Pair(
                cur.first + factor * ((prev.first + next.first) / 2f - cur.first),
                cur.second + factor * ((prev.second + next.second) / 2f - cur.second)
            )
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
