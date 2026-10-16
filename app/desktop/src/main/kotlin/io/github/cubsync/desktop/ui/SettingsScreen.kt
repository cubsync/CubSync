package io.github.cubsync.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cubsync.core.model.CompareMode
import io.github.cubsync.core.model.ConflictPolicy
import io.github.cubsync.core.model.DeletePolicy
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.DesktopSettings
import io.github.cubsync.desktop.CubType
import io.github.cubsync.service.Format
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.Section
import io.github.cubsync.desktop.copyToClipboard
import io.github.cubsync.desktop.openDirectory
import io.github.cubsync.service.BuildInfo

private val PARALLELISM_OPTIONS = listOf(1, 2, 4, 8)

/**
 * The settings that are about the application rather than about one configuration.
 *
 * The defaults here are what a new configuration starts from, which is the only
 * place where "I always want it this way" can be said once instead of per config.
 * The state directory is shown with its path, because it is the thing somebody has
 * to find when they want to move their setup to another machine.
 */
@Composable
fun SettingsScreen(state: AppState) {
    val colors = CubTheme.colors

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(Section.SETTINGS.label, Section.SETTINGS.hint)

        Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(
                modifier = Modifier.fillMaxWidth(),
                title = "状态目录",
                subtitle = "配置与每对文件夹的历史都放在这里",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.stateDirectory,
                        style = CubType.mono,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    QuietButton("复制", onClick = { copyToClipboard(state.stateDirectory) })
                    QuietButton("打开", onClick = { openDirectory(state.stateDirectory) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "两台设备各自记自己的历史；把这个目录一起带走，删除与新增的判断就不会丢。",
                    style = CubType.meta,
                    color = colors.textFaint,
                )
            }

            Panel(modifier = Modifier.fillMaxWidth(), title = "外观") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("深色主题", style = CubType.body, color = colors.text)
                        Text(
                            "与设计稿保持同一套配色，深色为默认。",
                            style = CubType.meta,
                            color = colors.textFaint,
                        )
                    }
                    Switch(
                        checked = state.settings.darkTheme,
                        onCheckedChange = {
                            state.settings.darkTheme = it
                            state.settings.save()
                        },
                    )
                }
            }

            Panel(
                modifier = Modifier.fillMaxWidth(),
                title = "新配置的默认策略",
                subtitle = "只影响之后新建的配置，不改动已经保存的",
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("同步方向")
                        Spacer(Modifier.height(5.dp))
                        Dropdown(
                            options = SyncDirection.entries.filter { it.supported }
                                .map { Choice(it, Format.direction(it)) },
                            selected = state.settings.defaultDirection,
                            onSelect = {
                                state.settings.defaultDirection = it
                                state.settings.save()
                            },
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("比较方式")
                        Spacer(Modifier.height(5.dp))
                        Dropdown(
                            options = CompareMode.entries.map { Choice(it, Format.compare(it)) },
                            selected = state.settings.defaultCompare,
                            onSelect = {
                                state.settings.defaultCompare = it
                                state.settings.save()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("冲突时")
                        Spacer(Modifier.height(5.dp))
                        Dropdown(
                            options = ConflictPolicy.entries.map { Choice(it, Format.conflict(it)) },
                            selected = state.settings.defaultConflict,
                            onSelect = {
                                state.settings.defaultConflict = it
                                state.settings.save()
                            },
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("删除时")
                        Spacer(Modifier.height(5.dp))
                        Dropdown(
                            options = DeletePolicy.entries.map { Choice(it, Format.delete(it)) },
                            selected = state.settings.defaultDelete,
                            onSelect = {
                                state.settings.defaultDelete = it
                                state.settings.save()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel("并行度", Modifier.width(90.dp))
                    SegmentedChoice(
                        options = PARALLELISM_OPTIONS.map { Choice(it, it.toString()) },
                        selected = state.settings.defaultParallelism.coerceIn(1, DesktopSettings.MAX_PARALLELISM),
                        onSelect = {
                            state.settings.defaultParallelism = it
                            state.settings.save()
                        },
                    )
                }
            }

            Panel(modifier = Modifier.fillMaxWidth(), title = "关于") {
                KeyValue("版本", BuildInfo.VERSION, wide = true)
                KeyValue("协议版本", BuildInfo.PROTOCOL_VERSION.toString(), wide = true)
                KeyValue("平台", BuildInfo.PLATFORM, wide = true)
                KeyValue("配置数量", Format.count(state.configs.size, "个"), wide = true)
                KeyValue("本次会话运行", Format.count(state.jobs.size, "次"), wide = true)
                Spacer(Modifier.height(10.dp))
                Text(
                    "许可证 Apache-2.0。协议没有加密与鉴权，只应在可信网络里共享文件夹。",
                    style = CubType.meta,
                    color = colors.warning,
                )
                Spacer(Modifier.height(8.dp))
                QuietButton(
                    "在日志页看全部",
                    onClick = { state.goTo(Section.LOGS) },
                )
                Spacer(Modifier.height(6.dp))
                QuietButton(
                    "刷新配置列表",
                    onClick = {
                        state.reloadConfigs()
                        state.notify("配置列表已刷新", NoticeKind.INFO)
                    },
                )
            }
        }
    }
}
