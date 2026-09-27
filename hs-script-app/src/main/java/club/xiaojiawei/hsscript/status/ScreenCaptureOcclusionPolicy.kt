package club.xiaojiawei.hsscript.status

import java.awt.Rectangle

/** Conservative provenance check for desktop pixels consumed as game evidence. */
internal object ScreenCaptureOcclusionPolicy {
    data class Layer(
        val handle: Long,
        val visible: Boolean,
        val bounds: Rectangle?,
    )

    data class Decision(val accepted: Boolean, val reason: String, val occludingHandle: Long? = null)

    fun evaluate(
        targetHandle: Long,
        captureBounds: Rectangle,
        layersAboveTarget: List<Layer>,
        enumerationComplete: Boolean,
    ): Decision {
        if (targetHandle == 0L || captureBounds.isEmpty) return Decision(false, "invalid-target-or-capture")
        if (!enumerationComplete) return Decision(false, "z-order-enumeration-incomplete")
        val occluder = layersAboveTarget.firstOrNull { layer ->
            if (layer.handle == targetHandle || !layer.visible) return@firstOrNull false
            val bounds = layer.bounds ?: return Decision(false, "visible-window-bounds-unknown", layer.handle)
            bounds.intersects(captureBounds)
        }
        return if (occluder == null) {
            Decision(true, "no-visible-window-over-capture")
        } else {
            Decision(false, "visible-window-intersects-capture", occluder.handle)
        }
    }
}
