package io.github.cubsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.cubsync.service.Accent
import io.github.cubsync.service.AppSettings
import io.github.cubsync.service.Buffering
import io.github.cubsync.service.BuildInfo
import io.github.cubsync.service.Columns
import io.github.cubsync.service.DisplayMode
import io.github.cubsync.service.Header
import io.github.cubsync.service.IconPaths
import io.github.cubsync.service.ItemScale
import io.github.cubsync.service.PaneDirection
import io.github.cubsync.service.Scope
import io.github.cubsync.service.ThemeMode
import io.github.cubsync.service.Words

/**
 * 设置: the appearance, then the browsing and transfer preferences.
 *
 * The nine fields the design lists, in its order, plus the theme and the accent. The
 * synchronisation policy is deliberately absent — 同步方向, 比较方式, 冲突 and 删除 belong
 * to the configuration they govern, and a setting in two places is a setting that will
 * eventually say two things.
 */
@Composable
fun SettingsScreen(state: AndroidAppState) {
    val colors = CubTheme.colors
    val settings = state.liveSettings

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionLabel("外观")
        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Segmented(
                options = listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT),
                selected = settings.theme,
                label = { it.label },
                onSelect = { value -> state.changeSettings { it.theme = value } },
                modifier = Modifier.fillMaxWidth(),
            )
            SettingRow("当前生效", if (colors.isDark) "深色" else "浅色") {
                CubIcon(IconPaths.PALETTE, size = 20, tint = colors.textDim)
            }
            CardRow(
                title = "动态取色（Material You）",
                detail = "从壁纸提取主色",
                icon = IconPaths.PALETTE,
                trailing = {
                    Toggle(settings.dynamicColour) { value ->
                        state.changeSettings { it.dynamicColour = value }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                for (swatch in Accent.SWATCHES) {
                    val active = !settings.dynamicColour && settings.accentHue == swatch.hue
                    val shape = RoundedCornerShape(99.dp)
                    Row(
                        Modifier
                            .heightIn(min = 34.dp)
                            .clip(shape)
                            .background(if (active) parseHexColour(swatch.hex) else Color.Transparent)
                            .border(1.dp, if (active) parseHexColour(swatch.hex) else colors.outlineStrong, shape)
                            .clickable {
                                state.changeSettings {
                                    it.dynamicColour = false
                                    it.accentHue = swatch.hue
                                    it.accent = ""
                                }
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            swatch.label,
                            style = CubType.label,
                            color = if (active) Color.White else colors.textDim,
                        )
                    }
                }
            }
        }

        SectionLabel("浏览与传输")
        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            SettingRow("默认显示方式", settings.defaultDisplayMode.label) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (mode in DisplayMode.entries) {
                        val active = settings.defaultDisplayMode == mode
                        Text(
                            mode.label,
                            style = CubType.label,
                            color = if (active) colors.accent else colors.textFaint,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { state.changeSettings { it.defaultDisplayMode = mode } }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            SettingRow("条目缩放", "双指缩放 0.7×–2.0× · 双击复位") {
                StepperField(
                    value = settings.itemScale,
                    onChange = { value -> state.changeSettings { it.itemScale = value } },
                    canDecrease = settings.itemScale > ItemScale.MIN,
                    canIncrease = settings.itemScale < ItemScale.MAX,
                    step = ItemScale.STEP,
                    suffix = "%",
                )
            }

            SettingRow("显示元数据", "列表里显示大小 · 修改时间（关＝更紧凑）") {
                Toggle(settings.showMetadata) { value -> state.changeSettings { it.showMetadata = value } }
            }

            SettingRow(
                "栏头地址行数",
                if (settings.headerAdaptive) "自适应＝按栏宽自动（窄栏分行 · 宽栏单行）" else "恒定 ${settings.headerLines} 行",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Toggle(settings.headerAdaptive) { value -> state.changeSettings { it.headerAdaptive = value } }
                    Spacer(Modifier.width(8.dp))
                    StepperField(
                        value = settings.headerLines,
                        onChange = { value -> state.changeSettings { it.headerLines = value } },
                        canDecrease = settings.headerLines > Header.MIN_LINES,
                        canIncrease = settings.headerLines < Header.MAX_LINES,
                        fieldWidth = 24,
                    )
                }
            }

            SettingRow("分栏方向", "双栏浏览与对比档共用 · 自动＝宽 ≥ 高 时左右，否则上下") {
                ChoiceChips(
                    options = PaneDirection.entries,
                    selected = settings.paneDirection,
                    label = { it.label },
                    onSelect = { value -> state.changeSettings { it.paneDirection = value } },
                )
            }

            SettingRow("缓冲块数", "1MB × ${settings.bufferBlocks}") {
                StepperField(
                    value = settings.bufferBlocks,
                    onChange = { value ->
                        state.changeSettings { it.bufferBlocks = Buffering.clamp(value) }
                        state.notify(Buffering.RESERVED, NoticeKind.INFO)
                    },
                    canDecrease = settings.bufferBlocks > Buffering.MIN,
                    canIncrease = settings.bufferBlocks < Buffering.MAX,
                    fieldWidth = 24,
                )
            }

            SettingRow("默认接收目录", settings.defaultReceiveDir.ifEmpty { "用对面的当前目录" }) {
                Field(
                    value = settings.defaultReceiveDir,
                    onValueChange = { value -> state.changeSettings { it.defaultReceiveDir = value } },
                    modifier = Modifier.width(150.dp),
                    placeholder = "用对面的当前目录",
                    style = CubType.meta,
                )
            }

            SettingRow("断点续传", ".cubtmp + journal（5s 落盘）") {
                Toggle(settings.resumeEnabled) { value -> state.changeSettings { it.resumeEnabled = value } }
            }

            SettingRow("同步删除走回收站", "${Words.TRASH}/<配置>/<对>/<时间>") {
                Toggle(settings.deleteToTrash) { value -> state.changeSettings { it.deleteToTrash = value } }
            }

            CardDesc(
                "行密度与列数跟着显示方式走，是每栏的偏好，在传输页副行的「显示方式▾」里；" +
                    "分栏方向两处同步。",
            )
        }

        SectionLabel("关于")
        Panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            CardRow(
                title = "CubSync ${BuildInfo.VERSION}",
                detail = "协议 v${BuildInfo.PROTOCOL_VERSION} · Apache-2.0",
                icon = IconPaths.NAV_SETTINGS,
            )
            KeyValueRow("状态目录", io.github.cubsync.service.ConfigStore.defaultBase().toString())
            KeyValueRow("配置数量", "${state.configs.size} 个")
            KeyValueRow("作业记录", "${state.jobs.size} 条")
            KeyValueRow("运行模式", settings.runMode.label)
            KeyValueRow("列数范围", "${Columns.MIN}–${Columns.MAX}（卡片 / 缩略档）")
            Text(
                "协议目前未加密、未鉴权：共享只适用于可信网络，别把端口开到公网上。",
                style = CubType.rowDetail,
                color = colors.warning,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

