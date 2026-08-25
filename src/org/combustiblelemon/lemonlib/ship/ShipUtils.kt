package org.combustiblelemon.lemonlib.ship

import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Given the ***target*** and ***ship*** will calculate if the target shields are facing the ***ship***
 * @param ship the ship's ship
 * @param target the target ship
 * @return boolean
 */
fun isShieldFacingShip(ship: ShipAPI, target: ShipAPI): Boolean {
    var targetShieldFacing = target.shield.facing + 180f
    if (targetShieldFacing > 360) {
        targetShieldFacing -= 360f
    }
    val targetShieldArc = target.shield.arc
    val controllerShipFacing = ship.facing
    val targetLeft = abs(targetShieldFacing - targetShieldArc)
    var targetRight = targetShieldFacing + targetShieldArc
    if (targetRight > 360) {
        targetRight -= 360f
    }
    return controllerShipFacing < targetLeft && controllerShipFacing > targetRight
}

private const val Pi = 3.14f

fun getLocationInFrontOfShip(ship: ShipAPI?, units: Float): Vector2f {
    val calculatedPos = Vector2f()
    val shipLocation = ship!!.location
    val facing = ship.facing
    calculatedPos.x = (shipLocation.x - units * cos(2.0 * Pi * (180.0 + facing) / 360.0)).toFloat()
    calculatedPos.y = (shipLocation.y - units * sin(2.0 * Pi * (180.0 + facing) / 360.0)).toFloat()
    return calculatedPos
}

fun getVelocityVectorInFacing(ship: ShipAPI?, units: Float): Vector2f {
    val velocity = Vector2f()
    val facing = ship!!.facing
    velocity.x = (-units * cos(2.0 * Pi * (180.0 + facing) / 360.0)).toFloat()
    velocity.y = (-units * sin(2.0 * Pi * (180.0 + facing) / 360.0)).toFloat()
    return velocity
}

fun getShipDimensions(ship: ShipAPI): Array<Vector2f> {

    val max = Vector2f(0f, 0f)
    val min = Vector2f(10000f, 10000f)

    ship.exactBounds.origSegments.forEach { segment ->
        // max
        if (segment.p1.x > max.x) {
            max.x = segment.p1.x
        }

        if (segment.p1.y > max.y) {
            max.y = segment.p1.y
        }

        // min
        if (segment.p1.x < min.x) {
            min.x = segment.p1.x
        }

        if (segment.p1.y < min.y) {
            min.y = segment.p1.y
        }
    }

    return arrayOf(max, min)
}

fun getShipPolygonInWorld(ship: ShipAPI): Array<Vector2f> {
    val shipX = ship.location!!.x
    val shipY = ship.location!!.y
    val results = mutableListOf<Vector2f>()

    val facing = Math.toRadians(ship.facing.toDouble()).toFloat()
    val cos = cos(facing)
    val sin = sin(facing)

    ship.exactBounds!!.origSegments.forEach { segment ->
        val x = segment!!.p1.x
        val y = segment.p1.y

        results.add(Vector2f((x * cos - y * sin) + shipX, (x * sin + y * cos) + shipY))
    }

    return results.toTypedArray()
}
