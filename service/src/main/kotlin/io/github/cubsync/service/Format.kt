package io.github.cubsync.service

import io.github.cubsync.core.engine.ActionKind
import io.github.cubsync.core.engine.JobStatus
import io.github.cubsync.core.engine.PairStatus
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How numbers and states are written for a person.
 *
 * Every user-visible word for an enum lives here rather than at the call site, so
 * the same policy cannot be called "镜像" on one screen and "单向同步" on another.
 */
object Format {

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dateTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    fun bytes(value: Long): String {
        if (value < 0) return "-"
        if (value < 1024) return "$value B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var size = value.toDouble() / 1024
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return if (size >= 100) "%.0f %s".format(size, units[unit]) else "%.1f %s".format(size, units[unit])
    }

    fun rate(bytes: Long, durationMs: Long): String =
        if (durationMs <= 0 || bytes <= 0) "-" else "${bytes(bytes * 1000 / durationMs)}/s"

    fun duration(ms: Long): String = when {
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> "%.1f s".format(ms / 1000.0)
        ms < 3_600_000 -> "%d m %02d s".format(ms / 60_000, (ms % 60_000) / 1_000)
        else -> "%d h %02d m".format(ms / 3_600_000, (ms % 3_600_000) / 60_000)
    }

    fun clockTime(epochMs: Long): String = time.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun stamp(epochMs: Long): String =
        if (epochMs <= 0) "-" else dateTime.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** A rough "how long ago", which is what a person reads in a list. */
    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return "从未"
        val seconds = (now - epochMs) / 1000
        return when {
            seconds < 45 -> "刚刚"
            seconds < 3_600 -> "${seconds / 60} 分钟前"
            seconds < 86_400 -> "${seconds / 3_600} 小时前"
            seconds < 604_800 -> "${seconds / 86_400} 天前"
            else -> stamp(epochMs)
        }
    }

    fun direction(value: SyncDirection): String = when (value) {
        SyncDirection.TWO_WAY -> "双向同步"
        SyncDirection.MIRROR_LEFT_TO_RIGHT -> "镜像 左侧 → 右侧"
        SyncDirection.MIRROR_RIGHT_TO_LEFT -> "镜像 右侧 → 左侧"
        SyncDirection.UPDATE_LEFT_TO_RIGHT -> "仅更新 左侧 → 右侧"
        SyncDirection.UPDATE_RIGHT_TO_LEFT -> "仅更新 右侧 → 左侧"
        SyncDirection.CUSTOM -> "自定义"
    }

    /** Short form for a list column, where the full phrase would wrap. */
    fun directionShort(value: SyncDirection): String = when (value) {
        SyncDirection.TWO_WAY -> "双向"
        SyncDirection.MIRROR_LEFT_TO_RIGHT -> "镜像 →"
        SyncDirection.MIRROR_RIGHT_TO_LEFT -> "镜像 ←"
        SyncDirection.UPDATE_LEFT_TO_RIGHT -> "更新 →"
        SyncDirection.UPDATE_RIGHT_TO_LEFT -> "更新 ←"
        SyncDirection.CUSTOM -> "自定义"
    }

    fun compare(value: CompareMode): String = when (value) {
        CompareMode.MTIME_AND_SIZE -> "修改时间与大小"
        CompareMode.CONTENT_HASH -> "内容哈希"
        CompareMode.SIZE_ONLY -> "仅大小"
    }

    fun conflict(value: ConflictPolicy): String = when (value) {
        ConflictPolicy.ASK -> "逐个询问"
        ConflictPolicy.PREFER_LEFT -> "左侧优先"
        ConflictPolicy.PREFER_RIGHT -> "右侧优先"
        ConflictPolicy.KEEP_BOTH -> "两份都留"
        ConflictPolicy.SKIP -> "跳过"
    }

    fun delete(value: DeletePolicy): String = when (value) {
        DeletePolicy.RECYCLE -> "移入回收站"
        DeletePolicy.PROPAGATE -> "直接删除"
        DeletePolicy.KEEP -> "保留不删"
    }

    fun jobStatus(value: JobStatus): String = when (value) {
        JobStatus.COMPLETED -> "已完成"
        JobStatus.PARTIAL_FAILED -> "部分失败"
        JobStatus.FAILED -> "失败"
        JobStatus.CANCELLED -> "已取消"
    }

    fun pairStatus(value: PairStatus): String = when (value) {
        PairStatus.COMPLETED -> "已完成"
        PairStatus.FAILED -> "失败"
        PairStatus.BLOCKED -> "已阻止"
        PairStatus.SKIPPED -> "已跳过"
        PairStatus.DRY_RUN -> "试运行"
    }

    fun action(value: ActionKind): String = when (value) {
        ActionKind.NONE -> "无操作"
        ActionKind.COPY_LEFT_TO_RIGHT -> "复制 →"
        ActionKind.COPY_RIGHT_TO_LEFT -> "复制 ←"
        ActionKind.MKDIR -> "建目录"
        ActionKind.DELETE_LEFT -> "删除左"
        ActionKind.DELETE_RIGHT -> "删除右"
        ActionKind.CONFLICT_ASK -> "待定"
        ActionKind.CONFLICT_KEEP_BOTH -> "两份都留"
        ActionKind.CONFLICT_SKIP -> "跳过"
    }

    fun level(value: LogLevel): String = when (value) {
        LogLevel.DEBUG -> "明细"
        LogLevel.INFO -> "信息"
        LogLevel.WARN -> "警告"
        LogLevel.ERROR -> "错误"
    }

    /** "3 项" reads better than "3 items" beside Chinese labels. */
    fun count(value: Int, unit: String = "项"): String = "$value $unit"
}

/**
 * What a configuration will do, in one sentence.
 *
 * Generated from the same fields the engine reads, so a screen cannot describe a
 * policy the run will not follow. It lives here rather than in a screen because both
 * front ends say this, and they have to say the same thing.
 */
fun policySentence(config: SyncConfig): String {
    val direction = config.direction
    val deletes = when {
        !direction.deletesAllowed -> "不会删除任何文件"
        config.delete == DeletePolicy.KEEP -> "删除不会被同步，多余的文件会留着"
        config.delete == DeletePolicy.RECYCLE -> "删除会同步，被删掉的文件进回收站"
        else -> "删除会同步，被删掉的文件直接删除"
    }
    val conflicts = when (config.conflict) {
        ConflictPolicy.ASK -> "冲突时停下来等人决定"
        ConflictPolicy.PREFER_LEFT -> "冲突时用左侧覆盖右侧"
        ConflictPolicy.PREFER_RIGHT -> "冲突时用右侧覆盖左侧"
        ConflictPolicy.KEEP_BOTH -> "冲突时两份都保留"
        ConflictPolicy.SKIP -> "冲突时跳过"
    }
    return "${Format.direction(direction)}；$deletes；$conflicts。"
}
