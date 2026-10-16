package io.github.cubsync.service

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
enum class Section(val label: String) {
    CONNECT("连接"),
    TRANSFER("传输"),
    SYNC("同步"),
    JOBS("作业"),
    LOGS("日志"),
    SETTINGS("设置"),
    ;

    /** What the phone's bottom bar shows. */
    val onPhoneBar: Boolean get() = this != LOGS
}

fun sectionHint(section: Section): String = when (section) {
    Section.CONNECT -> "共享本机文件夹，或连上另一台设备"
    Section.TRANSFER -> "两栏对照，把选中的东西送过去"
    Section.SYNC -> "配置文件夹对与策略，先预览再运行"
    Section.JOBS -> "每次运行的结果、明细与日志"
    Section.LOGS -> "连接、比较、写入的实时记录"
    Section.SETTINGS -> "浏览与传输的默认值，以及外观"
}

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
    const val LOCAL = "本机"
    const val REMOTE = "远端"
    const val SERVER = "服务端"
    const val CLIENT = "客户端"
    const val LOCAL_PANE = "本机栏"
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

/** 运行模式. */
enum class RunMode(val label: String, val detail: String) {
    NORMAL("普通", "经 MediaStore / SAF 读写"),
    ROOT("Root（su）", "后台进程直读全部目录"),
}

/** 传输通道. */
enum class Channel(val label: String, val detail: String) {
    LAN("局域网", "有线 / Wi-Fi 直连"),
    USB_ADB("USB-ADB", "数据线，推荐"),
    MULTI_LINK("多链路", "网卡聚合"),
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

/** 缓冲块数: the number of chunks held in flight. */
object Buffering {
    const val DEFAULT = 4
    const val MIN = 1
    const val MAX = 16

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
