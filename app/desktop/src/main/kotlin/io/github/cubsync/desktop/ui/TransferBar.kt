package io.github.cubsync.desktop.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cubsync.core.spi.FileMeta
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.service.NoticeKind
import io.github.cubsync.desktop.copyToClipboard
import io.github.cubsync.desktop.openDirectory
import io.github.cubsync.service.Browsing
import io.github.cubsync.service.Columns
import io.github.cubsync.service.CompareMark
import io.github.cubsync.service.Density
import io.github.cubsync.service.Difference
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.Format
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.ItemScale
import io.github.cubsync.service.PaneDirection
import io.github.cubsync.service.PaneState
import io.github.cubsync.service.Scope
import io.github.cubsync.service.Section
import io.github.cubsync.service.Side
import io.github.cubsync.service.SortKey
import io.github.cubsync.service.Words
import io.github.cubsync.service.resolveDirection
import io.github.cubsync.service.fileIcon
import io.github.cubsync.service.viewIcon
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

@Composable
fun TransferBar(state: AppState) {
    val colors = CubTheme.colors
    val running = state.sync.running
    val progress by state.browsing.transferProgress.collectAsState()
    // 速率没有从引擎来：`TransferProgress` 只有"已传/总量"。起点按这块屏幕第一次看到进度时
    // 记，进度每推进一块都会重组，所以这个值跟着走。半秒以内不给数，免得第一块就是天文数字。
    val startedAt = remember(progress != null) { if (progress != null) state.now() else 0L }
    val elapsedMs = if (progress != null && startedAt > 0) state.now() - startedAt else 0L
    val rateText = if (progress != null && elapsedMs >= 500) {
        Format.rate(progress!!.doneBytes, elapsedMs)
    } else {
        null
    }

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(230.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            progress != null -> "传送中"
                            running != null -> "${running.label}「${running.configName}」"
                            else -> "等待任务"
                        },
                        style = CubType.label,
                        color = colors.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        when {
                            progress != null -> if (progress?.paused == true) "已暂停" else "进行中"
                            running != null -> "${running.finishedPairs}/${running.pairCount} 对"
                            else -> DASH
                        },
                        tone = when {
                            progress != null -> colors.accent
                            running != null -> colors.accent
                            else -> null
                        },
                    )
                }
                Text(
                    when {
                        progress != null -> progressLine(progress!!, rateText)
                        running != null -> Format.duration(state.now() - running.startedAtMs)
                        else -> "选中文件后点中间的 → / ← 发起传输"
                    },
                    style = CubType.meta,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.surfaceSunken),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress?.fraction ?: 0f)
                        .clip(RoundedCornerShape(99.dp))
                        .background(colors.accent),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (progress == null) DASH else "${progress!!.percent}%",
                style = CubType.meta,
                color = colors.textDim,
                modifier = Modifier.width(48.dp),
            )
            if (progress != null) {
                QuietButton(if (progress!!.paused) "继续" else "暂停") {
                    if (progress!!.paused) state.browsing.resumeTransfer() else state.browsing.pauseTransfer()
                }
                QuietButton("取消", danger = true) { state.browsing.cancelTransfer() }
            } else if (running != null) {
                QuietButton("暂停 / 取消", danger = true) { state.cancelRun() }
            } else {
                // The prototype draws both buttons whether or not anything is moving; with
                // nothing in flight they are off rather than absent, so the strip does not
                // change width every time a copy starts.
                QuietButton("暂停", enabled = false) {}
                QuietButton("取消", danger = true, enabled = false) {}
            }
        }
    }
}

/** `已传 / 总量 · 速率`, the line the prototype puts under the name. */
private fun progressLine(progress: io.github.cubsync.service.TransferProgress, rate: String?): String =
    buildString {
        append("${Format.bytes(progress.doneBytes)} / ${Format.bytes(progress.totalBytes)} · ${progress.totalFiles} 项")
        if (rate != null) append(" · $rate")
    }
