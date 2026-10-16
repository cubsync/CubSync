package io.github.cubsync.android

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import io.github.cubsync.service.Format

/**
 * The settings that are about the application rather than about one configuration.
 *
 * The defaults here are what a new configuration starts from, which is the only place
 * "I always want it this way" can be said once instead of per configuration. The state
 * directory is shown because it is what somebody has to find when they want to move
 * their setup to another device.
 */
@Composable
fun SettingsScreen(state: AndroidAppState) {
    val colors = CubTheme.colors

    ScreenBody {
        Panel(title = "状态目录", subtitle = "配置与每对文件夹的历史都放在这里") {
            Text(state.stateDirectory, style = CubType.meta, color = colors.text)
            Spacer(Modifier.height(6.dp))
            Text(
                "两台设备各自记自己的历史；这个目录丢了，删除与新增的判断就会退化成第一次同步。",
                style = CubType.meta,
                color = colors.textFaint,
            )
        }

        Panel(title = "外观") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("深色主题", style = CubType.body, color = colors.text, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.preferences.darkTheme,
                    onCheckedChange = { state.preferences.darkTheme = it },
                )
            }
        }

        Panel(title = "新配置的默认策略", subtitle = "只影响之后新建的配置，不改动已经保存的") {
            Dropdown(
                label = "同步方向",
                options = SyncDirection.entries.filter { it.supported }.map { Choice(it, Format.direction(it)) },
                selected = state.preferences.defaultDirection,
                onSelect = { state.preferences.defaultDirection = it },
            )
            Spacer(Modifier.height(10.dp))
            Dropdown(
                label = "比较方式",
                options = CompareMode.entries.map { Choice(it, Format.compare(it)) },
                selected = state.preferences.defaultCompare,
                onSelect = { state.preferences.defaultCompare = it },
            )
            Spacer(Modifier.height(10.dp))
            Dropdown(
                label = "冲突时",
                options = ConflictPolicy.entries.map { Choice(it, Format.conflict(it)) },
                selected = state.preferences.defaultConflict,
                onSelect = { state.preferences.defaultConflict = it },
            )
            Spacer(Modifier.height(10.dp))
            Dropdown(
                label = "删除时",
                options = DeletePolicy.entries.map { Choice(it, Format.delete(it)) },
                selected = state.preferences.defaultDelete,
                onSelect = { state.preferences.defaultDelete = it },
            )
        }

        Panel(title = "关于") {
            KeyValue("配置数量", Format.count(state.configs.size, "个"))
            KeyValue("本次会话运行", Format.count(state.jobs.size, "次"))
            Spacer(Modifier.height(6.dp))
            VersionFooter()
        }

        Spacer(Modifier.width(1.dp))
    }
}
