package io.github.cubsync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.cubsync.desktop.AppState
import io.github.cubsync.desktop.CubTheme
import io.github.cubsync.desktop.CubType
import io.github.cubsync.desktop.NoticeKind
import io.github.cubsync.desktop.parseHexColour
import io.github.cubsync.desktop.pickDirectory
import io.github.cubsync.service.AppSettings
import io.github.cubsync.service.Buffering
import io.github.cubsync.service.BuildInfo
import io.github.cubsync.service.Columns
import io.github.cubsync.service.Density
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.Header
import io.github.cubsync.service.ItemScale
import io.github.cubsync.service.PaneDirection
import io.github.cubsync.service.Scope
import io.github.cubsync.service.ThemeMode
import io.github.cubsync.service.Words

/**
 * 设置: the browsing and transfer preferences, and the appearance.
 *
 * Nine fields, in the order the design lists them, plus the theme and the accent. The
 * synchronisation policy is deliberately *not* here — 同步方向, 比较方式, 冲突 and 删除
 * belong to the configuration they govern, and having them in two places is how the two
 * places start disagreeing.
 */
@Composable
fun SettingsScreen(state: AppState) {
    val colors = CubTheme.colors
    val settings = state.liveSettings

    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Panel(
                Modifier.fillMaxWidth(),
                title = "浏览与传输",
                subtitle = "新开的窗口、新的配置从这里取值",
            ) {
                SettingRow("默认显示方式", "新打开的一栏用什么样子") {
                    Segmented(
                        options = DisplayMode.entries.toList(),
                        selected = settings.defaultDisplayMode,
                        label = { it.label },
                        onSelect = { mode -> state.changeSettings { it.defaultDisplayMode = mode } },
                    )
                }

                SettingRow("条目缩放", "${ItemScale.MIN}–${ItemScale.MAX}%，100% ＝出厂") {
                    StepperField(
                        value = settings.itemScale,
                        onChange = { value -> state.changeSettings { it.itemScale = value } },
                        canDecrease = settings.itemScale > ItemScale.MIN,
                        canIncrease = settings.itemScale < ItemScale.MAX,
                        step = ItemScale.STEP,
                        suffix = "%",
                    )
                }

                SettingRow("显示元数据", "在详细档显示大小与修改时间") {
                    Toggle(settings.showMetadata) { value -> state.changeSettings { it.showMetadata = value } }
                }

                SettingRow(
                    "栏头地址行数",
                    if (settings.headerAdaptive) {
                        "自适应：单栏 / 宽栏恒 1 行，双栏窄栏用 ${settings.headerLines} 行"
                    } else {
                        "恒定 ${settings.headerLines} 行，不看栏宽"
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Toggle(settings.headerAdaptive) { value -> state.changeSettings { it.headerAdaptive = value } }
                        Spacer(Modifier.width(8.dp))
                        StepperField(
                            value = settings.headerLines,
                            onChange = { value -> state.changeSettings { it.headerLines = value } },
                            canDecrease = settings.headerLines > Header.MIN_LINES,
                            canIncrease = settings.headerLines < Header.MAX_LINES,
                            fieldWidth = 26,
                        )
                    }
                }

                SettingRow("分栏方向", "自动＝容器宽 ≥ 高 × 1.15 时左右") {
                    Segmented(
                        options = PaneDirection.entries.toList(),
                        selected = settings.paneDirection,
                        label = { it.label },
                        onSelect = { value -> state.changeSettings { it.paneDirection = value } },
                    )
                }

                SettingRow("缓冲块数", "${Buffering.MIN}–${Buffering.MAX}，同时留多少块在传输中") {
                    StepperField(
                        value = settings.bufferBlocks,
                        onChange = { value -> state.changeSettings { it.bufferBlocks = value } },
                        canDecrease = settings.bufferBlocks > Buffering.MIN,
                        canIncrease = settings.bufferBlocks < Buffering.MAX,
                        fieldWidth = 26,
                    )
                }

                SettingRow("默认接收目录", "对面还没打开文件夹时，传送落到这里") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field(
                            value = settings.defaultReceiveDir,
                            onValueChange = { value -> state.changeSettings { it.defaultReceiveDir = value } },
                            modifier = Modifier.width(180.dp),
                            placeholder = "留空＝用对面的当前目录",
                            style = CubType.meta,
                        )
                        Spacer(Modifier.width(6.dp))
                        QuietButton("选择…") {
                            val picked = pickDirectory("选择默认接收目录")
                            if (picked != null) state.changeSettings { it.defaultReceiveDir = picked }
                        }
                    }
                }

                SettingRow("断点续传", "中断后从已确认的块继续，不重传整份") {
                    Toggle(settings.resumeEnabled) { value -> state.changeSettings { it.resumeEnabled = value } }
                }

                SettingRow("同步删除走回收站", "关掉就是直接删除，删掉的东西回不来") {
                    Toggle(settings.deleteToTrash) { value -> state.changeSettings { it.deleteToTrash = value } }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "行密度（${Density.MIN}–${Density.MAX}%）是全局的，两栏同时生效；" +
                        "排序 / 文件夹优先 / 作用范围在传输页每栏的 ⋯ 里。",
                    style = CubType.meta,
                    color = colors.textFaint,
                )
            }

            Spacer(Modifier.height(12.dp))
            AppearancePanel(state)
        }

        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            AboutPanel(state)
        }
    }
}

/** 外观: the theme and the accent. */
@Composable
private fun AppearancePanel(state: AppState) {
    val colors = CubTheme.colors
    val settings = state.liveSettings

    Panel(Modifier.fillMaxWidth(), title = "外观", subtitle = "深色是默认；两端共用同一套令牌") {
        SettingRow("主题", settings.theme.label) {
            Segmented(
                options = ThemeMode.entries.toList(),
                selected = settings.theme,
                label = { it.label },
                onSelect = { value -> state.changeSettings { it.theme = value } },
            )
        }

        SettingRow("取色", "强调色用在选中档、聚焦栏与进度上") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (swatch in ACCENTS) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(parseHexColour(swatch))
                            .border(
                                2.dp,
                                if (settings.accent.equals(swatch, ignoreCase = true)) colors.text else Color.Transparent,
                                CircleShape,
                            )
                            .clickable { state.changeSettings { it.accent = swatch } },
                    )
                }
                Spacer(Modifier.width(4.dp))
                QuietButton("回到品牌靛蓝") { state.changeSettings { it.accent = AppSettings.ACCENT_DEFAULT } }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "品牌主色 ${AppSettings.ACCENT_DEFAULT}，辅色 #1FA8A0。换成别的颜色是允许的，但标志和界面会开始不像一套。",
            style = CubType.meta,
            color = colors.textFaint,
        )
    }
}

/** 关于: where the state lives, and which build this is. */
@Composable
private fun AboutPanel(state: AppState) {
    val colors = CubTheme.colors
    Panel(Modifier.fillMaxWidth(), title = "关于") {
        KeyValueRow("版本", BuildInfo.VERSION)
        KeyValueRow("协议", "v${BuildInfo.PROTOCOL_VERSION}")
        KeyValueRow("状态目录", state.stateDirectory)
        KeyValueRow("配置数量", "${state.configs.size} 个")
        KeyValueRow("作业记录", "${state.jobs.size} 条")

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietButton("复制状态目录") {
                io.github.cubsync.desktop.copyToClipboard(state.stateDirectory)
                state.notify("已复制状态目录", NoticeKind.SUCCESS)
            }
            QuietButton("打开状态目录") {
                val opened = io.github.cubsync.desktop.openDirectory(state.stateDirectory)
                if (!opened) state.notify("打不开这个目录", NoticeKind.WARNING)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "协议目前未加密、未鉴权：共享只适用于可信网络，别把端口开到公网上。",
            style = CubType.meta,
            color = colors.warning,
        )
        Spacer(Modifier.height(6.dp))
        Text("作用范围越具体越优先：${Scope.entries.joinToString(" > ") { it.label }} > 出厂默认。", style = CubType.meta, color = colors.textFaint)
        Text("两个目录的命名口径：${Words.LOCAL} / ${Words.REMOTE}，${Words.TRASH}不叫垃圾箱。", style = CubType.meta, color = colors.textFaint)
    }
}

/** The accents offered. The first is the brand's; the rest are there so it can be changed. */
private val ACCENTS = listOf("#3A5CCC", "#1FA8A0", "#7A5AF8", "#D9480F", "#2E9E5B")
