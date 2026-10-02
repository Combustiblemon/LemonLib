package org.combustiblelemon.lemonlib.geometry

import org.lwjgl.util.vector.Vector2f

/**
 * Cubic bezier easing curve from (0,0) to (1,1) with two control points — the same model as a CSS
 * `cubic-bezier(x1, y1, x2, y2)`. [getTime] evaluates the X component for a parameter in 0..1,
 * [getProgression] the Y component.
 */
class CubicCurve2D {
    private val start = Vector2f(0f, 0f)
    private val end = Vector2f(1f, 1f)
    var control1: Vector2f
    var control2: Vector2f

    constructor(control1X: Double, control1Y: Double, control2X: Double, control2Y: Double) {
        control1 = Vector2f(control1X.toFloat(), control1Y.toFloat())
        control2 = Vector2f(control2X.toFloat(), control2Y.toFloat())
    }

    constructor(control1X: Float, control1Y: Float, control2X: Float, control2Y: Float) {
        control1 = Vector2f(control1X, control1Y)
        control2 = Vector2f(control2X, control2Y)
    }

    /**
     * @param progression Needs to be between 0 and 1
     */
    fun getTime(progression: Float): Float {
        return solveCurveX(progression)
    }

    private fun solveCurveX(t: Float): Float {
        val t1 = 1 - t
        return (Math.pow(t1.toDouble(), 3.0) * start.x + (3 * t * Math.pow(t1.toDouble(), 2.0)
                * control1.x) + 3 * Math.pow(t.toDouble(), 2.0) * t1 * control2.x + Math.pow(t.toDouble(), 3.0) * end.x).toFloat()
    }

    /**
     * @param time Needs to be between 0 and 1
     */
    fun getProgression(time: Float): Float {
        return solveCurveY(time)
    }

    private fun solveCurveY(t: Float): Float {
        val t1 = 1 - t
        return (Math.pow(t1.toDouble(), 3.0) * start.y + (3 * t * Math.pow(t1.toDouble(), 2.0)
                * control1.y) + 3 * Math.pow(t.toDouble(), 2.0) * t1 * control2.y + Math.pow(t.toDouble(), 3.0) * end.y).toFloat()
    }
}
