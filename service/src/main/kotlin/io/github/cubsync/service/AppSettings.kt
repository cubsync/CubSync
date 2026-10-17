package io.github.cubsync.service

/**
 * Where settings are kept. Each platform supplies one; nothing else differs.
 *
 * A plain string map rather than a typed interface, because the point is that both
 * platforms store *the same keys*. A desktop `.properties` file and a phone's
 * `SharedPreferences` are both string maps underneath, so expressing that here is
 * honest about what is going on and keeps the two ends from drifting into two schemas.
 */
interface SettingsStorage {
    fun read(): Map<String, String>
    fun write(values: Map<String, String>)

    /** For tests, and for a run that has nowhere to write. */
    class Memory(private var initial: Map<String, String> = emptyMap()) : SettingsStorage {
        private var current: Map<String, String> = initial
        override fun read(): Map<String, String> = current
        override fun write(values: Map<String, String>) {
            current = values.toMap()
        }
    }

    companion object {
        fun memory(initial: Map<String, String> = emptyMap()): SettingsStorage = Memory(initial)
    }
}

/** 主题. Dark is the default; the design's palettes are the same tokens either way. */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** The browsing preferences that a scope can move around. */
data class BrowsePrefs(
    val displayMode: DisplayMode = DisplayMode.DETAIL,
    val sortKey: SortKey = SortKey.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
) {
    fun encode(): String =
        listOf(displayMode.name, sortKey.name, ascending.toString(), foldersFirst.toString())
            .joinToString("|")

    companion object {
        fun decode(raw: String): BrowsePrefs? {
            val parts = raw.split('|')
            if (parts.size != 4) return null
            val mode = DisplayMode.entries.firstOrNull { it.name == parts[0] } ?: return null
            val key = SortKey.entries.firstOrNull { it.name == parts[1] } ?: return null
            return BrowsePrefs(mode, key, parts[2].toBooleanStrictOrNull() ?: true, parts[3].toBooleanStrictOrNull() ?: true)
        }
    }
}

/**
 * The settings screen's contents, and the rules for how far a change reaches.
 *
 * Two things are deliberately in one class. The first is that these are the only
 * settings a user can change — a preference that exists but cannot be reached from the
 * settings screen is the bug this class is here to prevent recurring. The second is
 * 作用范围: whether a change to the sort order applies to this folder, to this pane, or
 * to both. That rule is stated in the design as a precedence
 *
 *     current folder  >  this pane  >  both panes  >  factory
 *
 * and it is implemented once, here, because "I changed it and nothing happened" is what
 * happens when each screen implements its own version of it.
 */
class AppSettings(private val storage: SettingsStorage = SettingsStorage.memory()) {

    private val values: MutableMap<String, String> = storage.read().toMutableMap()

    private fun persist() = storage.write(values.toMap())

    private fun int(key: String, fallback: Int): Int = values[key]?.toIntOrNull() ?: fallback

    private inline fun <reified T : Enum<T>> enum(key: String, fallback: T): T =
        values[key]?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: fallback

    private fun flag(key: String, fallback: Boolean): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: fallback

    // ------------------------------------------------------------ browsing

    /** 默认显示方式 — what a pane opens as before anybody changes it. */
    var defaultDisplayMode: DisplayMode
        get() = enum(KEY_DISPLAY_MODE, DisplayMode.DETAIL)
        set(value) {
            values[KEY_DISPLAY_MODE] = value.name
            persist()
        }

    /** 条目缩放, a percentage. */
    var itemScale: Int
        get() = ItemScale.clamp(int(KEY_ITEM_SCALE, ItemScale.DEFAULT))
        set(value) {
            values[KEY_ITEM_SCALE] = ItemScale.clamp(value).toString()
            persist()
        }

    /** 显示元数据 — the size and time column in the list modes. */
    var showMetadata: Boolean
        get() = flag(KEY_SHOW_METADATA, true)
        set(value) {
            values[KEY_SHOW_METADATA] = value.toString()
            persist()
        }

    /** 栏头地址行数 · 自适应开关. */
    var headerAdaptive: Boolean
        get() = flag(KEY_HEADER_ADAPTIVE, true)
        set(value) {
            values[KEY_HEADER_ADAPTIVE] = value.toString()
            persist()
        }

    /** 栏头地址行数 · 行数. */
    var headerLines: Int
        get() = Header.clampLines(int(KEY_HEADER_LINES, Header.DEFAULT_LINES))
        set(value) {
            values[KEY_HEADER_LINES] = Header.clampLines(value).toString()
            persist()
        }

    /** 分栏方向. */
    var paneDirection: PaneDirection
        get() = enum(KEY_PANE_DIRECTION, PaneDirection.AUTO)
        set(value) {
            values[KEY_PANE_DIRECTION] = value.name
            persist()
        }

    /** 列数, for the grid modes. */
    var columns: Int
        get() = Columns.clamp(int(KEY_COLUMNS, Columns.DEFAULT))
        set(value) {
            values[KEY_COLUMNS] = Columns.clamp(value).toString()
            persist()
        }

    /** 行密度. Global, and deliberately not scope-aware: two panes at different row
     * heights is a bug report, not a feature. */
    var rowDensity: Int
        get() = Density.clamp(int(KEY_DENSITY, Density.DEFAULT))
        set(value) {
            values[KEY_DENSITY] = Density.clamp(value).toString()
            persist()
        }

    fun bumpDensity(delta: Int) {
        rowDensity = rowDensity + delta
    }

    // ------------------------------------------------------------ transfer

    /** 缓冲块数. */
    var bufferBlocks: Int
        get() = Buffering.clamp(int(KEY_BUFFER_BLOCKS, Buffering.DEFAULT))
        set(value) {
            values[KEY_BUFFER_BLOCKS] = Buffering.clamp(value).toString()
            persist()
        }

    /** 默认接收目录 — where a transfer lands when the pane has not been pointed anywhere. */
    var defaultReceiveDir: String
        get() = values[KEY_RECEIVE_DIR].orEmpty()
        set(value) {
            values[KEY_RECEIVE_DIR] = value
            persist()
        }

    /** 断点续传. */
    var resumeEnabled: Boolean
        get() = flag(KEY_RESUME, true)
        set(value) {
            values[KEY_RESUME] = value.toString()
            persist()
        }

    /** 同步删除走回收站. */
    var deleteToTrash: Boolean
        get() = flag(KEY_DELETE_TO_TRASH, true)
        set(value) {
            values[KEY_DELETE_TO_TRASH] = value.toString()
            persist()
        }

    // ------------------------------------------------------------ appearance

    /** 主题. */
    var theme: ThemeMode
        get() = enum(KEY_THEME, ThemeMode.SYSTEM)
        set(value) {
            values[KEY_THEME] = value.name
            persist()
        }

    /**
     * 取色 — the accent an interface draws selection and focus with.
     *
     * Empty means "not chosen": each end then uses its own token from the prototype,
     * `--win-accent` on the desktop and `--m3-primary` on the phone. The prototype keeps
     * those two apart on purpose, and a single hard default here would be the one line
     * that made both ends the same colour.
     */
    var accent: String
        get() = values[KEY_ACCENT].orEmpty()
        set(value) {
            values[KEY_ACCENT] = value
            persist()
        }

    /** 动态取色（Material You）— take the accent from the wallpaper instead. */
    var dynamicColour: Boolean
        get() = flag(KEY_DYNAMIC_COLOUR, true)
        set(value) {
            values[KEY_DYNAMIC_COLOUR] = value.toString()
            persist()
        }

    /** The hue chip that is chosen; see [Accent.SWATCHES]. */
    var accentHue: Int
        get() = int(KEY_ACCENT_HUE, Accent.DEFAULT.hue)
        set(value) {
            values[KEY_ACCENT_HUE] = value.toString()
            persist()
        }

    /** The accent this phone should draw with, once 动态取色 and the hue chip are applied. */
    fun phoneAccent(): String {
        if (accent.isNotEmpty()) return accent
        return Accent.SWATCHES.firstOrNull { it.hue == accentHue }?.hex ?: Accent.DEFAULT.hex
    }

    // ------------------------------------------------------------ session state

    /** 运行模式, the档 shown collapsed on the connect screen. */
    var runMode: RunMode
        get() = enum(KEY_RUN_MODE, RunMode.NORMAL)
        set(value) {
            values[KEY_RUN_MODE] = value.name
            persist()
        }

    /** The peer address box's last content, so it does not have to be retyped. */
    var lastPeer: String
        get() = values[KEY_LAST_PEER].orEmpty()
        set(value) {
            values[KEY_LAST_PEER] = value
            persist()
        }

    /** 共享的文件夹 — what this machine offers to the network. */
    var sharedRoots: List<String>
        get() = values[KEY_SHARED_ROOTS].orEmpty().lines().filter { it.isNotBlank() }
        set(list) {
            values[KEY_SHARED_ROOTS] = list.distinct().joinToString("\n")
            persist()
        }

    /** The port the server last listened on; 0 means "let the system choose". */
    var sharePort: Int
        get() = int(KEY_SHARE_PORT, 0)
        set(value) {
            values[KEY_SHARE_PORT] = value.toString()
            persist()
        }

    /**
     * 传输通道 — the network cards this machine offers the peer.
     *
     * Empty means every card, which is what a first run should do: somebody who has never
     * touched this has no opinion about which interface to use, and the answer "all of
     * them" is the one that works.
     */
    var channelNics: List<String>
        get() = values[KEY_CHANNEL_NICS].orEmpty().lines().filter { it.isNotBlank() }
        set(list) {
            values[KEY_CHANNEL_NICS] = list.distinct().joinToString("\n")
            persist()
        }

    /** The name this machine announces during a handshake. */
    var serverNodeName: String
        get() = values[KEY_NODE_NAME].orEmpty()
        set(value) {
            values[KEY_NODE_NAME] = value
            persist()
        }

    /** Folders a pane should offer to open. */
    var recentFolders: List<String>
        get() = values[KEY_RECENT_FOLDERS].orEmpty().lines().filter { it.isNotBlank() }
        set(list) {
            values[KEY_RECENT_FOLDERS] = list.take(RECENT_LIMIT).distinct().joinToString("\n")
            persist()
        }

    fun rememberFolder(path: String) {
        if (path.isBlank()) return
        recentFolders = listOf(path) + recentFolders.filterNot { it == path }
    }

    /** Whether the display bar is open. Remembered for the session, not forever. */
    var displayBarOpen: Boolean = false

    /** The pane that the display bar acts on: the one that was touched last. */
    var activeSide: Side = Side.LEFT

    // ------------------------------------------------------------ lists

    /**
     * 收藏夹, as one line per entry.
     *
     * A tab-separated list rather than a serialisation format: it is four short string
     * fields, it has to survive a hand edit in a properties file, and a path may contain
     * almost anything except a tab.
     */
    var favourites: List<Favorite>
        get() = values[KEY_FAVOURITES].orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) null else Favorite(
                device = parts[0],
                backendId = parts[1],
                path = parts[2],
                label = parts.getOrElse(3) { "" },
                protocol = parts.getOrElse(4) { if (parts[1] == EndpointId.LOCAL) "本端" else "CubSync" },
            )
        }
        set(list) {
            values[KEY_FAVOURITES] = list.joinToString("\n") {
                "${it.device}\t${it.backendId}\t${it.path}\t${it.label}\t${it.protocol}"
            }
            persist()
        }

    /** The peers this machine has actually reached, most recent first. */
    var knownPeers: List<KnownPeer>
        get() = values[KEY_KNOWN_PEERS].orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) null else KnownPeer(
                name = parts[0],
                host = parts[1],
                port = parts[2].toIntOrNull() ?: return@mapNotNull null,
                seenAtMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
            )
        }
        set(list) {
            values[KEY_KNOWN_PEERS] = list.take(KNOWN_PEER_LIMIT)
                .joinToString("\n") { "${it.name}\t${it.host}\t${it.port}\t${it.seenAtMs}" }
            persist()
        }

    /** Records a peer, newest first, without letting the list grow without bound. */
    fun rememberPeer(peer: KnownPeer, at: Long = System.currentTimeMillis()) {
        val rest = knownPeers.filterNot { it.address == peer.address }
        knownPeers = (listOf(peer.copy(seenAtMs = at)) + rest).take(KNOWN_PEER_LIMIT)
    }

    fun toggleFavourite(favorite: Favorite) {
        val current = favourites
        val existing = current.firstOrNull { it.backendId == favorite.backendId && it.path == favorite.path }
        favourites = if (existing != null) current - existing else current + favorite
    }

    /**
     * Whether a folder is starred.
     *
     * Keyed on the backend and the path rather than on the display name: the name of a
     * peer can change, and 本端 is a label rather than an address — the same folder must
     * keep its star either way.
     */
    fun isFavourite(backendId: String, path: String): Boolean =
        favourites.any { it.backendId == backendId && it.path == path }

    fun removeFavourite(favorite: Favorite) {
        favourites = favourites.filterNot { it.key == favorite.key }
    }

    /** 收藏夹's own setting: draw each row's path under its name. */
    var favouritesShowPath: Boolean
        get() = flag(KEY_FAV_SHOW_PATH, false)
        set(value) {
            values[KEY_FAV_SHOW_PATH] = value.toString()
            persist()
        }

    /** The groups somebody named, in the order they arranged them. */
    var favouriteGroups: List<String>
        get() = values[KEY_FAV_GROUPS].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(list) {
            values[KEY_FAV_GROUPS] = list.distinct().joinToString("\n")
            persist()
        }

    fun addFavouriteGroup(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) return ""
        if (clean !in favouriteGroups) favouriteGroups = favouriteGroups + clean
        return clean
    }

    fun renameFavouriteGroup(from: String, to: String) {
        val clean = to.trim()
        if (clean.isEmpty() || from == clean) return
        favouriteGroups = favouriteGroups.map { if (it == from) clean else it }
        favourites = favourites.map { if (it.groupName == from) it.copy(groupName = clean) else it }
    }

    /** Deleting a group keeps its rows: they fall back to the heading of their own end. */
    fun deleteFavouriteGroup(name: String) {
        favouriteGroups = favouriteGroups - name
        favourites = favourites.map { if (it.groupName == name) it.copy(groupName = "") else it }
    }

    fun updateFavourite(favorite: Favorite, label: String = favorite.label, group: String = favorite.groupName) {
        favourites = favourites.map {
            if (it.key == favorite.key) it.copy(label = label.trim(), groupName = group) else it
        }
        if (group.isNotEmpty() && group !in favouriteGroups) favouriteGroups = favouriteGroups + group
    }

    /** Replaces the order — what a drag ends with. */
    fun reorderFavourites(ordered: List<Favorite>) {
        favourites = ordered
    }

    fun reorderFavouriteGroups(ordered: List<String>) {
        favouriteGroups = ordered
    }

    /**
     * Every heading, in the order the drawer should draw them.
     *
     * Named groups first, in the order they were arranged, then the automatic ones that
     * have rows but no name — 本端 first among those, because it is the one that is always
     * there.
     */
    fun favouriteHeadings(): List<String> {
        val named = favouriteGroups
        val automatic = favourites.map { it.group }
            .filterNot { it in named }
            .distinct()
            .sortedWith(compareBy({ if (it == Words.LOCAL) 0 else 1 }, { it }))
        return named + automatic
    }

    // ------------------------------------------------------------ scope layering

    private fun folderKey(side: Side, path: String) = "browse.folder.${side.name}|$path"
    private fun paneKey(side: Side) = "browse.pane.${side.name}"

    /**
     * What a pane should use, by the precedence the design states.
     *
     * Read back in order: this pane's record for this folder, then this pane, then both
     * panes, then the factory default. First hit wins.
     */
    fun prefsFor(side: Side, path: String): BrowsePrefs =
        values[folderKey(side, path)]?.let { BrowsePrefs.decode(it) }
            ?: values[paneKey(side)]?.let { BrowsePrefs.decode(it) }
            ?: values[KEY_BROWSE_SHARED]?.let { BrowsePrefs.decode(it) }
            ?: BrowsePrefs(displayMode = defaultDisplayMode)

    /**
     * Writes a change at the scope the user picked.
     *
     * 本栏全局 clears this pane's per-folder record — otherwise the more specific old
     * value keeps winning and the setting looks like it did nothing. 两栏共用 clears
     * both panes' more specific layers for the same reason, in both directions.
     */
    fun apply(side: Side, path: String, scope: Scope, prefs: BrowsePrefs) {
        when (scope) {
            Scope.CURRENT_FOLDER -> values[folderKey(side, path)] = prefs.encode()
            Scope.THIS_PANE -> {
                values[paneKey(side)] = prefs.encode()
                values.keys.removeAll { it.startsWith("browse.folder.${side.name}|") }
            }

            Scope.BOTH_PANES -> {
                values[KEY_BROWSE_SHARED] = prefs.encode()
                values.keys.removeAll { it.startsWith("browse.folder.") || it.startsWith("browse.pane.") }
            }
        }
        persist()
    }

    /** Every key this class owns, for the settings screen's diagnostics. */
    fun snapshot(): Map<String, String> = values.toMap()

    companion object {
        /**
         * The accent both ends start on: the brand's indigo.
         *
         * This used to be the prototype's `--win-accent` (`#1E88E5`), while the first chip
         * in 取色 was `#3A5CCC` and the phone's was `#4C6EF5`. Pressing 回到品牌靛蓝 then
         * produced a colour that appeared nowhere else on the screen. One value now, and
         * it is the mark's.
         */
        const val ACCENT_DEFAULT: String = Brand.PRIMARY

        /** How many peers are remembered. Beyond this the list stops being a shortcut. */
        const val KNOWN_PEER_LIMIT: Int = 12

        /** How many folders a pane offers to open. */
        const val RECENT_LIMIT: Int = 10

        const val KEY_DISPLAY_MODE = "display.mode"
        const val KEY_ITEM_SCALE = "display.itemScale"
        const val KEY_SHOW_METADATA = "display.showMetadata"
        const val KEY_HEADER_ADAPTIVE = "display.headerAdaptive"
        const val KEY_HEADER_LINES = "display.headerLines"
        const val KEY_PANE_DIRECTION = "display.paneDirection"
        const val KEY_COLUMNS = "display.columns"
        const val KEY_DENSITY = "display.rowDensity"
        const val KEY_BUFFER_BLOCKS = "transfer.bufferBlocks"
        const val KEY_RECEIVE_DIR = "transfer.receiveDir"
        const val KEY_RESUME = "transfer.resume"
        const val KEY_DELETE_TO_TRASH = "transfer.deleteToTrash"
        const val KEY_THEME = "appearance.theme"
        const val KEY_ACCENT = "appearance.accent"
        const val KEY_DYNAMIC_COLOUR = "appearance.dynamicColour"
        const val KEY_ACCENT_HUE = "appearance.accentHue"
        const val KEY_RUN_MODE = "device.runMode"
        const val KEY_LAST_PEER = "peer.last"
        const val KEY_KNOWN_PEERS = "peer.known"
        const val KEY_SHARED_ROOTS = "server.roots"
        const val KEY_SHARE_PORT = "server.port"
        const val KEY_CHANNEL_NICS = "server.nics"
        const val KEY_NODE_NAME = "server.nodeName"
        const val KEY_RECENT_FOLDERS = "browse.recent"
        const val KEY_FAVOURITES = "browse.favourites"
        const val KEY_FAV_GROUPS = "browse.favouriteGroups"
        const val KEY_FAV_SHOW_PATH = "browse.favouriteShowPath"
        const val KEY_BROWSE_SHARED = "browse.shared"
    }
}
