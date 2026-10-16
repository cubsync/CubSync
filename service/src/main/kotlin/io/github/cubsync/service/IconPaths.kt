package io.github.cubsync.service

/**
 * The icon geometry both interfaces draw.
 *
 * SVG path data rather than characters. An earlier version used glyphs — `\u2630`, `\u22ee`,
 * `\u2606` — and wherever the rendered font had no opinion about one, it drew a box. A box
 * in a navigation bar is worse than no icon: it looks like a loading state that never
 * finishes.
 *
 * The paths are the design's own, taken from the prototype, so the two ends draw the same
 * shapes. The data lives here, with no Compose dependency, because both front ends need
 * the same paths and neither of them should own them.
 *
 * Coordinates are on a 24×24 grid and are stroked, not filled, at the width below — which
 * is how the prototype draws them.
 */
object IconPaths {

    /** The stroke width the prototype uses. */
    const val STROKE: Float = 1.7f

    /** Five entries of the bottom bar, matching [Section] in order. */
    const val NAV_CONNECT =
        "M9 7V4M15 7V4M7 7h10v5a5 5 0 0 1-10 0zM12 17v3"
    const val NAV_TRANSFER =
        "M7 21V4M7 4 4 7M7 4l3 3M17 3v17M17 20l3-3M17 20l-3-3"
    const val NAV_SYNC =
        "M21 12a9 9 0 0 1-9 9 9 9 0 0 1-8-5M3 12a9 9 0 0 1 9-9 9 9 0 0 1 8 5M21 4v4h-4M3 20v-4h4"
    const val NAV_JOBS =
        "M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01"
    const val NAV_LOGS =
        "M6 3.5h8.5L19 8v12.5H6zM14 3.5V8h4.5M9 12.5h6M9 16h6"
    const val NAV_SETTINGS =
        "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"

    /** The three-way switch between both panes and either one alone. */
    const val PANE_BOTH =
        "M3 5.5A1.5 1.5 0 0 1 4.5 4h5A1.5 1.5 0 0 1 11 5.5v13A1.5 1.5 0 0 1 9.5 20h-5A1.5 1.5 0 0 1 3 18.5z" +
            "M13 5.5A1.5 1.5 0 0 1 14.5 4h5A1.5 1.5 0 0 1 21 5.5v13A1.5 1.5 0 0 1 19.5 20h-5a1.5 1.5 0 0 1-1.5-1.5z"
    const val PANE_LOCAL =
        "M6.5 5.1A2.6 2.6 0 0 1 9.1 2.5h5.8a2.6 2.6 0 0 1 2.6 2.6v13.8a2.6 2.6 0 0 1-2.6 2.6H9.1a2.6 2.6 0 0 1-2.6-2.6z" +
            "M10.5 18.6h3"
    const val PANE_REMOTE =
        "M4.5 4h15a2 2 0 0 1 2 2v8.6a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
            "M9 20.4h6M12 16.6v3.8"

    /** The chrome the two ends share. */
    const val MENU = "M4 7h16M4 12h16M4 17h16"

    /** Three dots: short strokes with a round cap, which is what reads as dots. */
    const val MORE = "M12 5.4v.2M12 11.9v.2M12 18.4v.2"
    const val BACK = "M15 4.5 7.5 12 15 19.5"
    const val FORWARD = "M9 4.5 16.5 12 9 19.5"
    const val STAR =
        "M12 3.6l2.62 5.31 5.86.85-4.24 4.13 1 5.84L12 16.98l-5.24 2.75 1-5.84L3.52 9.76l5.86-.85z"
    const val REFRESH = "M20.5 11.5a8.5 8.5 0 1 0-2.4 5.9M20.5 5v6.5H14"
    const val CLOSE = "M6 6l12 12M18 6L6 18"
    const val PLUS = "M12 5v14M5 12h14"
    const val MINUS = "M5 12h14"
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val CHEVRON_DOWN = "M6 9.5l6 6 6-6"
    const val CHEVRON_RIGHT = "M9.5 6l6 6-6 6"
    const val FOLDER = "M4 7.2A2.2 2.2 0 0 1 6.2 5h3l2.2 2.4h6.4A2.2 2.2 0 0 1 20 9.6v7.2a2.2 2.2 0 0 1-2.2 2.2H6.2A2.2 2.2 0 0 1 4 16.8z"
    const val LOCK = "M7.5 10.5V8a4.5 4.5 0 0 1 9 0v2.5M6 10.5h12v9.5H6z"
    const val SEARCH = "M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13zM15.4 15.4 21 21"
    const val TRASH = "M4 7h16M9 7V5h6v2M6 7l1 13h10l1-13M10 11v6M14 11v6"
    const val LINK = "M4 12h16M12 4v16"

    /** 连接页的传输通道、账户、共享与运行状态。 */
    const val WIFI =
        "M2.6 8.7a14.5 14.5 0 0 1 18.8 0M5.7 12.3a10.2 10.2 0 0 1 12.6 0M8.8 15.8a5.6 5.6 0 0 1 6.4 0M12 19.3v.2"
    const val DEVICE = "M4 6.5h16v9H4zM9 19h6M12 15.5V19"
    const val SHARE = "M12 15.5V3.5M8.5 7 12 3.5 15.5 7M5 13v6.5a1.5 1.5 0 0 0 1.5 1.5h11a1.5 1.5 0 0 0 1.5-1.5V13"
    const val CLOCK = "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17zM12 7.4V12l3 2"

    /** 过滤器，与电脑端同一支漏斗。 */
    const val FILTER = "M3 5h18l-7 8v6l-4-2v-4z"

    /** 设置页的外观一行。 */
    const val PALETTE = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 3v18"

    /** A filled dot, for a row that is not a folder. */
    const val DOT = "M12 10.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2z"

    /**
     * The window controls, from the prototype's `.win-title .wctl`.
     *
     * Drawn rather than borrowed from the system, because the prototype draws them: a
     * native title bar is the one piece of the window the design replaces, and it is also
     * the piece that ignores the theme.
     */
    const val WIN_MINIMISE = "M5 12h14"
    const val WIN_MAXIMISE = "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"
    const val WIN_RESTORE = "M8.5 8.5h10a1.5 1.5 0 0 1 1.5 1.5v8.5a1.5 1.5 0 0 1-1.5 1.5h-8.5A1.5 1.5 0 0 1 8.5 18.5zM4 15V5.5A1.5 1.5 0 0 1 5.5 4H15"

    /** `.rail-fold` — the button that collapses the rail to icons. */
    const val FOLD = "M13 5.5 6.5 12l6.5 6.5M19.5 5.5 13 12l6.5 6.5"
}
