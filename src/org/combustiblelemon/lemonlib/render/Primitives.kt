package org.combustiblelemon.lemonlib.render

import org.lwjgl.opengl.GL11
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a circle of [segments] vertices around ([centerX], [centerY]) with the current GL color,
 * line width and blend state: an outline (`GL_LINE_LOOP`) or, with [filled], a disc
 * (`GL_TRIANGLE_FAN`). Fewer than 3 segments draws nothing.
 *
 * Same vertex walk as LazyLib's `DrawUtils.drawCircle` (credit: LazyWizard), but in immediate mode
 * rather than client vertex arrays, so it is safe to call while a buffer object is still bound.
 */
fun drawCircle(centerX: Float, centerY: Float, radius: Float, segments: Int, filled: Boolean) {
    if (segments < 3) return

    // Rotate one point around the center by a fixed step instead of a cos/sin per vertex
    val theta = (2.0 * PI / segments).toFloat()
    val cos = cos(theta)
    val sin = sin(theta)
    var x = radius
    var y = 0f

    GL11.glBegin(if (filled) GL11.GL_TRIANGLE_FAN else GL11.GL_LINE_LOOP)
    repeat(segments) {
        GL11.glVertex2f(x + centerX, y + centerY)
        val prevX = x
        x = cos * x - sin * y
        y = sin * prevX + cos * y
    }
    GL11.glEnd()
}
