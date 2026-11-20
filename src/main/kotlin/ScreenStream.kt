// Turns a screenshot into the small JPEG picture that is sent to the phone.
// (Taking the screenshot itself and sending the picture is done in Main.kt; this file only does the picture work,
// so it can be tested without a screen.)

import java.awt.Color
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

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

// The main screen (the one with the taskbar). Mouse positions from the phone are mapped onto it.
fun primaryScreenBounds(): Rectangle =
    GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds

// Cuts out the wanted part, shrinks it to at most `targetWidth` pixels and draws the mouse pointer on it
// (a screenshot does not contain the pointer). `cursor` = pointer position as fractions of the whole screen.
fun renderScreenPicture(shot: BufferedImage, view: ViewRect, targetWidth: Int, cursor: Pair<Double, Double>?): BufferedImage {
    val sx = (view.x * shot.width).toInt().coerceIn(0, shot.width - 1)
    val sy = (view.y * shot.height).toInt().coerceIn(0, shot.height - 1)
    val sw = (view.size * shot.width).toInt().coerceIn(1, shot.width - sx)
    val sh = (view.size * shot.height).toInt().coerceIn(1, shot.height - sy)
    val outW = minOf(targetWidth, sw)                      // never blow it up bigger than the real pixels
    val outH = (sh.toDouble() * outW / sw).toInt().coerceAtLeast(1)

    val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(shot, 0, 0, outW, outH, sx, sy, sx + sw, sy + sh, null)
    if (cursor != null) {
        val px = ((cursor.first - view.x) / view.size * outW).toInt()
        val py = ((cursor.second - view.y) / view.size * outH).toInt()
        if (px in 0 until outW && py in 0 until outH) drawPointer(g, px, py, (outW / 70).coerceIn(10, 26))
    }
    g.dispose()
    return out
}

// A small arrow: white with a black outline, so it can be seen on any background
private fun drawPointer(g: Graphics2D, x: Int, y: Int, size: Int) {
    val k = size / 20.0
    val xs = intArrayOf(0, 0, 5, 9, 12, 8, 14).map { x + (it * k).toInt() }.toIntArray()
    val ys = intArrayOf(0, 17, 13, 20, 19, 12, 12).map { y + (it * k).toInt() }.toIntArray()
    g.color = Color.WHITE
    g.fillPolygon(xs, ys, xs.size)
    g.color = Color.BLACK
    g.drawPolygon(xs, ys, xs.size)
}

// A quick fingerprint of the picture: if it is the same as the last one, nothing changed and nothing is sent
fun pictureFingerprint(img: BufferedImage): Int {
    val data = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    return data.contentHashCode()
}

fun encodeJpeg(img: BufferedImage, quality: Int): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val param = writer.defaultWriteParam
    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
    param.compressionQuality = quality.coerceIn(10, 90) / 100f
    val bytes = ByteArrayOutputStream()
    MemoryCacheImageOutputStream(bytes).use { stream ->
        writer.output = stream
        writer.write(null, IIOImage(img, null, null), param)
    }
    writer.dispose()
    return bytes.toByteArray()
}
