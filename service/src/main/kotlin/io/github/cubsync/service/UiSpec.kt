package io.github.cubsync.service

import kotlin.math.roundToInt
import io.github.cubsync.core.protocol.Capabilities

/**
 * Everything the two interfaces must say the same way.
 *
 * The design document is the specification, and the property it spends the most words on
 * is that a word means the same thing on a phone and on a desktop. Written twice, that
 * property decays — not because anybody decides to diverge, but because the second copy
 * is written from memory. So the words, the lists of steps and the defaults live here,
 * once, and a front end may choose how to draw them and not what they say.
 *
 * Nothing in this file knows about Compose, or about either platform.
 */

// --------------------------------------------------------------------- navigation

/**
 * The top-level entries, in the order both platforms show them.
 *
 * The phone leaves `LOGS` out of its bottom bar and folds the log into the job it came
 * from, because the bar has room for five and the log is a detail of a run. The desktop
 * has room for six and keeps it separate. That is the only difference: it is one list
 * with one extra entry, not two concepts.
 */
enum class Section(val label: String, val heading: String, val hint: String) {
    CONNECT("连接", "连接设备", "USB 与多张网卡并行组通道，握手时协商 v4 能力位"),
    TRANSFER("传输", "传输", "双排浏览 · 地址栏直达 · 5 档显示（与手机端同一套）· 断点续传 · 右键理管"),
    SYNC("同步", "同步", "一个配置管 N 个文件夹对：方向 / 比较 / 过滤全局生效，过滤可每对覆盖"),
    JOBS("作业", "作业", "按文件夹对分组 · 可暂停、续传、失败对单独重试"),
    LOGS("日志", "日志", "结构化记录 · 每条对应一个协议帧或一个文件动作（含 pairId）"),
    SETTINGS("设置", "设置", "主题、缓冲块数、adb 路径、回收站策略"),
    ;

    /** What the phone's bottom bar shows. */
    val onPhoneBar: Boolean get() = this != LOGS
}

/** The heading and the one-line hint above a screen, both taken from the prototype. */
fun sectionHeading(section: Section): String = section.heading
fun sectionHint(section: Section): String = section.hint

// --------------------------------------------------------------------- names

/**
 * The words for the two machines, and for the few things that have more than one name
 * in common speech.
 *
 * `本机` and `远端`, never `本地` or `服务端` for a side of a copy. It looks like
 * fussiness; it is not. A person reads the pane title to decide which side of a copy
 * they are looking at, and two names for one side is how somebody deletes the wrong
 * file.
 */
object Words {
    /**
     * 本端 / 远端 rather than 本机 / 远端.
     *
     * The two panes are two ends of a link, and neither end has to be this computer: a
     * phone talking to a phone, a disk copied to another disk, a WebDAV or SFTP mount on
     * the far side. Calling the near end 本机 stopped being true the moment the far end
     * could be anything, and it reads as a lie on a phone.
     */
    const val LOCAL = "本端"
    const val REMOTE = "远端"
    const val SERVER = "服务端"
    const val CLIENT = "客户端"
    const val LOCAL_PANE = "本端栏"
    const val REMOTE_PANE = "远端栏"
    const val TRASH = "回收站"
    const val FILTER = "过滤"
    const val NEW_FOLDER = "新建文件夹"
    const val COPY_PATH = "复制本栏地址"
    const val UNCONNECTED = "未连接"
    const val EMPTY_FOLDER = "这个文件夹是空的"
    const val FAVOURITES = "收藏夹"
    const val ACCOUNTS = "账户"
    const val ROOTS = "根"
}

// --------------------------------------------------------------------- display

/**
 * 显示方式, five steps.
 *
 * The label is the whole of it. An earlier version carried a glyph beside each name, and
 * a glyph the font has no opinion about renders as a box — a worse failure than a plain
 * word, because the word always looks like itself.
 */
enum class DisplayMode(val label: String) {
    COMPACT("紧凑"),
    DETAIL("详细"),
    CARD("卡片"),
    THUMBNAIL("缩略"),
    COMPARE("对比"),
    ;

    /**
     * Whether this step has a column count.
     *
     * A grid is measured in columns; a list is measured in row height. Showing both
     * controls in both cases is how a settings panel gets a control that does nothing.
     */
    val hasColumns: Boolean get() = this == CARD || this == THUMBNAIL

    /** Can be shown in a grid at all. */
    val isGrid: Boolean get() = hasColumns
}

/** 分栏方向, three steps. */
enum class PaneDirection(val label: String) {
    AUTO("自动"),
    SIDE_BY_SIDE("左右"),
    STACKED("上下"),
}

/** 作用范围, three steps. More specific wins, so the declaration order is the precedence. */
enum class Scope(val label: String) {
    CURRENT_FOLDER("当前目录"),
    THIS_PANE("本栏全局"),
    BOTH_PANES("两栏共用"),
}

enum class SortKey(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    MODIFIED("修改时间"),
}

/**
 * 运行模式, three steps, Android only.
 *
 * 普通 goes through MediaStore / SAF, Root（su）runs the server in a `:root` process via
 * libsu, and Shizuku / ADB talks to the same shell a rooted phone would — the prototype
 * draws all three as a chip row and this is the one place the labels may not be shortened.
 * The desktop has no such choice: there is no root on Windows, and offering one would be
 * asking about a permission that does not exist.
 */
enum class RunMode(val label: String) {
    NORMAL("普通"),
    ROOT("Root（su）"),
    SHIZUKU("Shizuku / ADB"),
    ;

    /**
     * Which of the three the platform can actually honour.
     *
     * Root and Shizuku are Android's; the desktop answers 普通 for every value, so the
     * setting can be shared without the desktop pretending to have a root process.
     */
    companion object {
        /** The note under the chip row, verbatim from the prototype. */
        const val NOTE: String = "Root 模式可读写全部目录，需在 Magisk / KernelSU 中授权。"

        /** The fold's heading suffix while collapsed, e.g. `Root（su）· 默认收起`. */
        const val COLLAPSED_SUFFIX: String = " · 默认收起"

        val onDesktop: List<RunMode> = listOf(NORMAL)
    }
}

/** 连接方式, the two-step switch at the top of 连接设备. */
enum class LinkMode(val label: String) {
    ADB("USB-ADB（推荐）"),
    WIFI("WiFi 直连"),
}

/**
 * 传输通道 uses the machine's real interfaces, not a fixed list.
 *
 * The prototype's three rows are `USB_ADB / wlan1 / wlan0`: two of them are the machine's
 * own cards and the first is the loopback adb forward opens. So a channel row is a card
 * plus one address, and the only card this product does not create is the adb one.
 */
object Channels {
    /** The row the prototype leads with, which exists once `adb forward` has run. */
    const val USB_ADB_NAME = "USB_ADB"
    const val USB_ADB_ADDRESS = "127.0.0.1:5740"

    /**
     * What the phone's adb row says when tapped.
     *
     * The row is drawn — the prototype leads with it — but nothing on the phone turns it
     * on: the cable becomes a channel when the desktop runs `adb forward`, and this end is
     * already serving on the same port it serves the network on. A switch that did nothing
     * was worse than a sentence that explains why there is nothing to switch.
     */
    const val ADB_FROM_HOST = "USB-ADB 由电脑端经 adb 转发建立，手机上无需开启"

    /** The same, for 多链路. */
    const val MULTI_LINK_RESERVED = "多链路已留位：协议支持多链路，传输目前仍走其中一条"
}

/**
 * 协议协商 shows what the handshake actually agreed on.
 *
 * The names are the prototype's, and each one is tied to the bit it stands for. Three of
 * them — `BIDIR_CONTROL`, `CONFIG_PAIR`, `JOB_EVENTS` — have no bit yet: the prototype
 * draws a twelve-bit v4 capability set and this build negotiates the sixteen bits in
 * `Capabilities`. Those three are therefore shown as 已留位 rather than as agreed, which is
 * the same treatment the prototype's unimplemented channels get.
 */
data class CapabilityRow(val name: String, val bit: Long?)

object CapabilityNames {
    val ROWS: List<CapabilityRow> = listOf(
        CapabilityRow("RECURSIVE_LIST", Capabilities.LIST),
        CapabilityRow("BATCH_STAT", Capabilities.STAT),
        CapabilityRow("HASH_XXH64", Capabilities.HASH),
        CapabilityRow("RESUME", Capabilities.RESUME),
        CapabilityRow("ATOMIC_RENAME", Capabilities.RENAME),
        CapabilityRow("SET_MTIME", Capabilities.SET_MTIME),
        CapabilityRow("BIDIR_CONTROL", null),
        CapabilityRow("CONFIG_PAIR", null),
        CapabilityRow("JOB_EVENTS", null),
        CapabilityRow("POSIX_MODE", Capabilities.PERMISSIONS),
        CapabilityRow("HARDLINK", Capabilities.SYMLINK),
    )

    /** The bits this build can honour; what the handshake intersects against. */
    val ALL_BITS: Long = Capabilities.ALL

    /**
     * Whether the agreed set covers this row.
     *
     * A row with no bit is never "agreed", and one with a bit is agreed when the negotiated
     * set contains it — which is what `10 / 12` counts. Rows without a bit count towards
     * neither number, so the fraction is about the bits rather than about the drawing.
     */
    fun agreed(row: CapabilityRow, negotiated: Long): Boolean =
        row.bit != null && Capabilities.has(negotiated, row.bit)

    fun reserved(row: CapabilityRow): Boolean = row.bit == null

    /** `agreed / offered`, the pair the prototype prints as `10 / 12`. */
    fun agreedCount(negotiated: Long): Int = ROWS.count { agreed(it, negotiated) }
}

/**
 * 取色: the five hue chips on the phone's 设置 screen.
 *
 * The prototype fixes one of them — 靛蓝 is drawn in `--m3-primary`, `#4C6EF5` — and gives
 * the other four only as a hue number (`data-h`). They are resolved as HSL at the
 * saturation and lightness `#4C6EF5` implies (89% / 63%), so the set is one ramp the
 * prototype already chose rather than four colours picked here.
 */
object Accent {
    data class Swatch(val label: String, val hue: Int, val hex: String)

    private fun hsl(hue: Int, saturation: Double = 0.89, lightness: Double = 0.63): String {
        val c = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60.0) % 2 - 1))
        val m = lightness - c / 2
        val (r, g, b) = when (hue / 60) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return "#%02X%02X%02X".format(
            ((r + m) * 255).toInt().coerceIn(0, 255),
            ((g + m) * 255).toInt().coerceIn(0, 255),
            ((b + m) * 255).toInt().coerceIn(0, 255),
        )
    }

    /**
     * The first chip is the brand's own indigo and is called 默认 on both ends.
     *
     * It used to be a guess at `--m3-primary` (`#4C6EF5`) while the desktop's list began
     * with `#3A5CCC` and its reset button set a third colour (`#1E88E5`) — three answers
     * to one question, and the screen offered two of them side by side. One value now,
     * taken from the mark rather than from a stylesheet.
     */
    val BRAND: String = Brand.PRIMARY

    val SWATCHES: List<Swatch> = listOf(
        Swatch("默认", 232, BRAND),
        Swatch("紫", 275, hsl(275)),
        Swatch("青", 200, hsl(200)),
        Swatch("绿", 150, hsl(150)),
        Swatch("橙", 25, hsl(25)),
    )

    val DEFAULT: Swatch = SWATCHES.first()
}

/** Why a file was skipped. Shown one per line, so they have to be short. */
enum class SkipReason(val label: String) {
    SAME_TIME_AND_SIZE("时间与大小一致"),
    FILTERED("被过滤器排除"),
    IGNORED("已忽略"),
    SOURCE_MISSING("源不存在"),
    DRY_RUN("试运行，没有写入"),
    NEEDS_DECISION("两侧都改过，需要决定"),
}

// --------------------------------------------------------------------- numbers

/** 行密度 is a line-height percentage, not a named size. */
object Density {
    const val DEFAULT = 100
    const val MIN = 50
    const val MAX = 250
    const val STEP = 5

    /**
     * Brings anything — a typed number, a pinch, a restored preference — into range.
     *
     * Every entry point funnels through here, because the alternative is four places
     * that each remember the bounds and one of them that forgets.
     */
    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    fun canDecrease(value: Int): Boolean = value > MIN
    fun canIncrease(value: Int): Boolean = value < MAX
}

/** 条目缩放 is a percentage of the factory size. */
object ItemScale {
    const val DEFAULT = 100
    const val MIN = 70
    const val MAX = 200

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    /** How much a panel action moves it. */
    const val STEP = 10

    /**
     * What a pinch does to the current value.
     *
     * [factor] is the gesture's own scale delta: 1.0 means unchanged, 1.05 means five per
     * cent bigger. Multiplicative rather than additive, because that is what a gesture
     * feels like — the same finger movement changes the size by the same proportion
     * whether the rows are already large or small.
     *
     * The arithmetic lives here, away from the gesture handler, because it is the part a
     * test can reach. The handler around it is a thin shell.
     */
    fun zoomBy(current: Int, factor: Float): Int {
        if (!factor.isFinite() || factor <= 0f) return clamp(current)
        return clamp((current * factor).roundToInt())
    }
}

/** 列数: the shortcuts on the expand bar, and the range a typed value may take. */
object Columns {
    const val DEFAULT = 4
    const val MIN = 1
    const val MAX = 12
    val SHORTCUTS = listOf(2, 3, 4, 5, 6)

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

/** 栏头地址行数: an adaptive switch plus a line count. */
object Header {
    const val DEFAULT_LINES = 2
    const val MIN_LINES = 1
    const val MAX_LINES = 4

    /** Below this pane width, a two-pane layout gets the多行 header. */
    const val NARROW_PANE_DP = 210

    fun clampLines(value: Int): Int = value.coerceIn(MIN_LINES, MAX_LINES)

    /**
     * How many address lines a pane shows.
     *
     * Adaptive means "wrap only where there is a reason to": a single pane or a wide
     * window has room for one line, and a wrapped header there just eats list space.
     */
    fun linesFor(adaptive: Boolean, requested: Int, paneWidthDp: Float, paneCount: Int): Int {
        val wanted = clampLines(requested)
        if (!adaptive) return wanted
        if (paneCount < 2 || paneWidthDp >= NARROW_PANE_DP) return 1
        return wanted
    }

    /** The header grows to fit its lines instead of squeezing the list. */
    fun heightDp(lines: Int): Int = 16 + clampLines(lines) * 13
}

/**
 * 缓冲块数: the number of 1MB chunks held in flight.
 *
 * The prototype prints `1MB × 256`, so that is the factory value and the range has to
 * contain it; an earlier version capped the field at 16, which made the prototype's own
 * default unenterable.
 */
object Buffering {
    const val DEFAULT = 256
    const val MIN = 1
    const val MAX = 1024

    /**
     * What changing the field says today.
     *
     * The number is stored and shown, but the copy pipeline reads one 1MB block at a
     * time: there is no queue of blocks in flight to size yet. Saying so on the change is
     * the same treatment the prototype's unwired channels get — the control stays, the
     * sentence tells the truth.
     */
    const val RESERVED = "缓冲块数已记下：读写目前按 1MB 块顺序进行，并行块队列还没接上"

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

// --------------------------------------------------------------------- jobs

/** 作业条目详情: the key/value block, in this order, identical on both ends. */
val JOB_DETAIL_ROWS: List<String> = listOf(
    "配置",
    "方向",
    "文件夹对",
    "开始",
    "结束",
    "数据量",
    "条目",
    "耗时",
    "速率",
    "新增 · 更新",
    "删除 · 冲突",
    "跳过 · 失败",
    "续传 / 隔离",
)

object JobDetail {
    /** File-level lists are capped; the rest is counted. */
    const val FILE_LIMIT = 20
}

// --------------------------------------------------------------------- resolution

/**
 * Resolves 自动 for a given container.
 *
 * `自动` is not a guess: the rule is stated so that a phone in portrait and the same
 * phone on its side disagree on purpose, which is the whole point of the setting.
 */
fun resolveDirection(setting: PaneDirection, widthDp: Float, heightDp: Float): PaneDirection =
    when (setting) {
        PaneDirection.AUTO ->
            if (widthDp >= heightDp * 1.15f) PaneDirection.SIDE_BY_SIDE else PaneDirection.STACKED
        else -> setting
    }
