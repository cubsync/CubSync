package io.github.cubsync.service

/**
 * The brand constants, as the brand build script defines them.
 *
 * Kept apart from [CubColors] on purpose. `#3A5CCC` is the mark's big circle and
 * `#1FA8A0` its small one, and `brand.py` says in capitals that these two do not change
 * in any colour state. An earlier version drew the mark with the *interface* tokens —
 * `--win-accent` and `--info` — so updating the palette silently changed the logo, which
 * is how the small circle stopped being teal.
 */
object Brand {
    const val PRIMARY: String = "#3A5CCC"
    const val ACCENT: String = "#1FA8A0"

    const val PRIMARY_ARGB: Long = 0xFF3A5CCC
    const val ACCENT_ARGB: Long = 0xFF1FA8A0

    // The 120-unit design grid the build script draws on.
    const val BIG_CX = 60.0f
    const val BIG_CY = 60.0f
    const val BIG_R = 34.0f
    const val SMALL_CX = 80.4f
    const val SMALL_CY = 76.1f
    const val SMALL_R = 23.0f
    const val HOLE_R = 8.0f

    /** The mark's bounding box, so a caller can scale it to fill a square. */
    const val BOX_LEFT = 26.0f
    const val BOX_TOP = 26.0f
    const val BOX_RIGHT = 103.4f
    const val BOX_BOTTOM = 99.1f

    const val BOX_WIDTH = BOX_RIGHT - BOX_LEFT
    const val BOX_HEIGHT = BOX_BOTTOM - BOX_TOP
    const val BOX_CX = (BOX_LEFT + BOX_RIGHT) / 2f
    const val BOX_CY = (BOX_TOP + BOX_BOTTOM) / 2f
}
