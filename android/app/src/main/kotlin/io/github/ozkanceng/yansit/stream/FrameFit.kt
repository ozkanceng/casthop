package io.github.ozkanceng.yansit.stream

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the screen sits inside the encoder frame, in pixels from the top-left corner. */
internal data class FrameRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The screen's place in the frame. [rotated]: the picture is drawn turned 90° clockwise, so [rect] is in the
 * turned size (FrameFlags.ROTATED, PROTOCOL.md §3).
 */
internal data class FrameLayout(val rect: FrameRect, val rotated: Boolean) {
    /** The size the VirtualDisplay needs: the screen in its own orientation at [rect]'s scale. */
    val sourceWidth: Int get() = if (rotated) rect.height else rect.width
    val sourceHeight: Int get() = if (rotated) rect.width else rect.height
}

/** Layout of the screen inside the fixed-size encoder frame ([ScreenSource]). */
internal object FrameFit {
    /**
     * The source scaled to fill as much of the frame as its aspect allows, centred; the rest stays black
     * (a portrait tablet on a landscape board). Sizes are even, as encoders and the VirtualDisplay expect.
     */
    fun fit(frameWidth: Int, frameHeight: Int, sourceWidth: Int, sourceHeight: Int): FrameRect {
        require(frameWidth > 0 && frameHeight > 0 && sourceWidth > 0 && sourceHeight > 0)
        val scale = min(frameWidth.toDouble() / sourceWidth, frameHeight.toDouble() / sourceHeight)
        val w = even(min(frameWidth, (sourceWidth * scale).roundToInt()))
        val h = even(min(frameHeight, (sourceHeight * scale).roundToInt()))
        return FrameRect((frameWidth - w) / 2, (frameHeight - h) / 2, w, h)
    }

    /**
     * [fit], or with [canRotate] (the receiver turns ROTATED frames back) and a screen turned the other way than the
     * frame: the picture turned 90° clockwise to fill the frame, so a portrait tablet uses the whole landscape
     * board instead of a strip in the middle.
     */
    fun layout(frameWidth: Int, frameHeight: Int, sourceWidth: Int, sourceHeight: Int, canRotate: Boolean): FrameLayout {
        val crosswise = sourceWidth != sourceHeight && frameWidth != frameHeight &&
            (sourceWidth > sourceHeight) != (frameWidth > frameHeight)
        return if (canRotate && crosswise) {
            FrameLayout(fit(frameWidth, frameHeight, sourceHeight, sourceWidth), rotated = true)
        } else {
            FrameLayout(fit(frameWidth, frameHeight, sourceWidth, sourceHeight), rotated = false)
        }
    }

    /**
     * The source size the encoder frame is planned for: the screen turned to the receiver's orientation. Boards are
     * landscape, so a session started in portrait still gets a landscape frame, and turning the tablet only moves
     * the picture inside it. Unknown receiver size counts as landscape.
     */
    fun planningSource(sourceWidth: Int, sourceHeight: Int, receiverWidth: Int, receiverHeight: Int): Pair<Int, Int> {
        val long = max(sourceWidth, sourceHeight)
        val short = min(sourceWidth, sourceHeight)
        val portraitReceiver = receiverWidth > 0 && receiverHeight > receiverWidth
        return if (portraitReceiver) short to long else long to short
    }

    private fun even(v: Int): Int = max(2, v and 1.inv())
}
