package org.combustiblelemon.lemonlib.geometry

import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.geom.Line2D

// Line/bounds collision helpers, ported from LazyLib's CollisionUtils (LazyWizard: "All modders have
// my permission to copy and use any code I've written") so dependents need no LazyLib for them.
// Differences from LazyLib: results are always fresh vectors (LazyLib can hand back the caller's
// own start/end or the entity's live location), and getCollisionPoint refreshes the exact bounds
// itself instead of relying on isPointWithinBounds doing it as a side effect.

/** Squared distance under which a point counts as lying on a segment (LazyLib's 1/3 px tolerance). */
private const val ON_SEGMENT_DIST_SQ = 0.11111f

/**
 * Where segment [start1]-[end1] crosses segment [start2]-[end2], or null when they do not cross or
 * are parallel. An endpoint of the first segment lying on the second (within 1/3 px) is returned
 * as the hit, [end1] checked before [start1].
 */
fun getSegmentIntersection(start1: Vector2f, end1: Vector2f, start2: Vector2f, end2: Vector2f): Vector2f? {
    if (Line2D.ptSegDistSq(
            start2.x.toDouble(), start2.y.toDouble(), end2.x.toDouble(), end2.y.toDouble(),
            end1.x.toDouble(), end1.y.toDouble()
        ) <= ON_SEGMENT_DIST_SQ.toDouble()
    ) return Vector2f(end1)
    if (Line2D.ptSegDistSq(
            start2.x.toDouble(), start2.y.toDouble(), end2.x.toDouble(), end2.y.toDouble(),
            start1.x.toDouble(), start1.y.toDouble()
        ) <= ON_SEGMENT_DIST_SQ.toDouble()
    ) return Vector2f(start1)

    val denom = (end1.x - start1.x) * (end2.y - start2.y) - (end1.y - start1.y) * (end2.x - start2.x)
    // Parallel (LazyLib's MathUtils.equals(0f, denom) reduces to an exact zero test)
    if (denom == 0f) return null

    val r = ((start1.y - start2.y) * (end2.x - start2.x) - (start1.x - start2.x) * (end2.y - start2.y)) / denom
    val s = ((start1.y - start2.y) * (end1.x - start1.x) - (start1.x - start2.x) * (end1.y - start1.y)) / denom
    if (r < 0f || r > 1f || s < 0f || s > 1f) return null

    return Vector2f(start1.x + r * (end1.x - start1.x), start1.y + r * (end1.y - start1.y))
}

/**
 * The first point where the segment [lineStart]-[lineEnd] meets [entity]'s exact bounds (nearest to
 * [lineStart]), or null on a miss. A start already inside the bounds is its own hit. Entities
 * without exact bounds fall back to their collision circle and report their center as the hit.
 */
fun getCollisionPoint(lineStart: Vector2f, lineEnd: Vector2f, entity: CombatEntityAPI): Vector2f? {
    val bounds = entity.exactBounds
    if (bounds == null) {
        val center = entity.location
        val radius = entity.collisionRadius
        val hits = Line2D.ptSegDistSq(
            lineStart.x.toDouble(), lineStart.y.toDouble(), lineEnd.x.toDouble(), lineEnd.y.toDouble(),
            center.x.toDouble(), center.y.toDouble()
        ) <= (radius * radius).toDouble()
        return if (hits) Vector2f(center) else null
    }

    bounds.update(entity.location, entity.facing)
    if (isPointWithinBounds(lineStart, entity)) return Vector2f(lineStart)

    var closest: Vector2f? = null
    var closestDistSq = Float.MAX_VALUE
    for (segment in bounds.segments) {
        val hit = getSegmentIntersection(lineStart, lineEnd, segment.p1, segment.p2) ?: continue
        val dx = hit.x - lineStart.x
        val dy = hit.y - lineStart.y
        val distSq = dx * dx + dy * dy
        if (closest == null || distSq < closestDistSq) {
            closest = hit
            closestDistSq = distSq
        }
    }
    return closest
}

/**
 * True when [point] is inside [entity]'s exact bounds or on their outline (within 1/3 px). Entities
 * without exact bounds use their collision circle, edge inclusive.
 */
fun isPointWithinBounds(point: Vector2f, entity: CombatEntityAPI): Boolean {
    val center = entity.location
    val radius = entity.collisionRadius
    val dx = point.x - center.x
    val dy = point.y - center.y
    val inCircle = dx * dx + dy * dy <= radius * radius

    val bounds = entity.exactBounds ?: return inCircle
    // The collision circle encloses the bounds: outside it is outside them
    if (!inCircle) return false

    bounds.update(center, entity.facing)
    val segments = bounds.segments
    if (segments.isEmpty()) return false
    for (segment in segments) {
        if (Line2D.ptSegDistSq(
                segment.p1.x.toDouble(), segment.p1.y.toDouble(), segment.p2.x.toDouble(), segment.p2.y.toDouble(),
                point.x.toDouble(), point.y.toDouble()
            ) <= ON_SEGMENT_DIST_SQ.toDouble()
        ) return true
    }

    // Outline as LazyLib builds it: every segment's start, then the last segment's end
    val polygon = Array(segments.size + 1) { i ->
        if (i < segments.size) segments[i].p1 else segments.last().p2
    }
    return isPointInPolygon(point, polygon)
}
