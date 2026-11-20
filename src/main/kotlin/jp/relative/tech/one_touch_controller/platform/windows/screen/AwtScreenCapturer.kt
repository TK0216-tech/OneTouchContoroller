package jp.relative.tech.one_touch_controller.platform.windows.screen

import jp.relative.tech.one_touch_controller.domain.ports.EncodedScreenFrame
import jp.relative.tech.one_touch_controller.domain.ports.ScreenCapturePort
import jp.relative.tech.one_touch_controller.domain.screen.ViewRect
import java.awt.Color
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

// The main screen (the one with the taskbar). Mouse positions from the phone are mapped onto it.
fun primaryScreenBounds(): Rectangle =
    GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds

// Cuts out the wanted part, shrinks it to at most `targetWidth` pixels and draws the mouse pointer on it
// (a screenshot does not contain the pointer). `cursor` = pointer position as fractions of the whole screen.
fun renderScreenPicture(
    shot: BufferedImage,
    view: ViewRect,
    targetWidth: Int,
    cursor: Pair<Double, Double>?
): BufferedImage {
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

/**
 * [ScreenCapturePort] implementation using AWT [Robot] and ImageIO JPEG encoding.
 */
class AwtScreenCapturer(
    private val robotProvider: () -> Robot
) : ScreenCapturePort {
    override fun captureFrame(
        view: ViewRect,
        targetWidth: Int,
        quality: Int,
        lastFingerprint: Int,
        forceSend: Boolean
    ): EncodedScreenFrame? {
        val screen = primaryScreenBounds()
        val shot = robotProvider().createScreenCapture(screen)
        val p = MouseInfo.getPointerInfo()?.location
        val cursor = p?.let {
            Pair((it.x - screen.x).toDouble() / screen.width, (it.y - screen.y).toDouble() / screen.height)
        }
        val picture = renderScreenPicture(shot, view, targetWidth, cursor)
        val fingerprint = pictureFingerprint(picture)
        if (!forceSend && fingerprint == lastFingerprint) return null
        return EncodedScreenFrame(fingerprint, encodeJpeg(picture, quality))
    }
}
