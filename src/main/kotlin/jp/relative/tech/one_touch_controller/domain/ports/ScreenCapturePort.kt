package jp.relative.tech.one_touch_controller.domain.ports

import jp.relative.tech.one_touch_controller.domain.screen.ViewRect

class EncodedScreenFrame(
    val fingerprint: Int,
    val jpegBytes: ByteArray
)

/** Port for capturing the primary screen, drawing the cursor and encoding as JPEG. */
interface ScreenCapturePort {
    /**
     * Captures the screen region defined by [view], scales to [targetWidth], and encodes JPEG if
     * the frame changed compared to [lastFingerprint] or [forceSend] is true.
     * Returns null when the image is unchanged and [forceSend] is false.
     */
    fun captureFrame(
        view: ViewRect,
        targetWidth: Int,
        quality: Int,
        lastFingerprint: Int,
        forceSend: Boolean
    ): EncodedScreenFrame?
}
