package club.xiaojiawei.hsscript.status

import java.awt.Rectangle

/** Conservative provenance check for desktop pixels consumed as game evidence. */
internal object ScreenCaptureOcclusionPolicy {
    // Win32 may enumerate 1x1 message/utility HWNDs in the z-order. They have
    // no meaningful pixel area, but Rectangle.intersects treats them as an
    // occluder when the game capture begins at (0, 0). Keep the exemption
    // purely geometric; do not whitelist owners, classes, or processes.
    private const val MIN_MEANINGFUL_DIMENSION_PX = 4

    data class Layer(
        val handle: Long,
        val visible: Boolean,
        val bounds: Rectangle?,
    )

    data class Decision(
        val accepted: Boolean,
        val reason: String,
        val occludingHandle: Long? = null,
        val ignoredTinyLayerCount: Int = 0,
    )

    fun evaluate(
        targetHandle: Long,
        captureBounds: Rectangle,
        layersAboveTarget: List<Layer>,
        enumerationComplete: Boolean,
    ): Decision {
        if (targetHandle == 0L || captureBounds.isEmpty) return Decision(false, "invalid-target-or-capture")
        if (!enumerationComplete) return Decision(false, "z-order-enumeration-incomplete")
        var ignoredTinyLayers = 0
        val occluder = layersAboveTarget.firstOrNull { layer ->
            if (layer.handle == targetHandle || !layer.visible) return@firstOrNull false
            val bounds = layer.bounds ?: return Decision(false, "visible-window-bounds-unknown", layer.handle)
            if (bounds.width < MIN_MEANINGFUL_DIMENSION_PX &&
                bounds.height < MIN_MEANINGFUL_DIMENSION_PX
            ) {
                ignoredTinyLayers++
                return@firstOrNull false
            }
            bounds.intersects(captureBounds)
        }
        return if (occluder == null) {
            Decision(true, if (ignoredTinyLayers > 0) "only-non-meaningful-tiny-windows" else "no-visible-window-over-capture",
                ignoredTinyLayerCount = ignoredTinyLayers)
        } else {
            Decision(false, "visible-window-intersects-capture", occluder.handle)
        }
    }
}
