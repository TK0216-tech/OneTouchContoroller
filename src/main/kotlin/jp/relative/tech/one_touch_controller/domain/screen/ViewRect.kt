package jp.relative.tech.one_touch_controller.domain.screen

// The part of the screen that is shown, as fractions of the whole screen (0.0 - 1.0).
// At zoom 200% it is half the width and half the height, centred on (cx, cy) but never sticking out of the screen.
// The phone / web page uses exactly the same formula to turn a tap into a spot on the real screen.
data class ViewRect(val x: Double, val y: Double, val size: Double)

fun viewRect(zoomPercent: Int, cx: Int, cy: Int): ViewRect {
    val size = 100.0 / zoomPercent.coerceIn(100, 400)
    return ViewRect(
        x = (cx.coerceIn(0, 1000) / 1000.0 - size / 2).coerceIn(0.0, 1.0 - size),
        y = (cy.coerceIn(0, 1000) / 1000.0 - size / 2).coerceIn(0.0, 1.0 - size),
        size = size
    )
}
