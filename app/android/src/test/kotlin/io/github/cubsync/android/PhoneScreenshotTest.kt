package io.github.cubsync.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.cubsync.core.model.Endpoint
import io.github.cubsync.core.model.FolderPair
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.core.model.SyncDirection
import io.github.cubsync.service.AppLog
import io.github.cubsync.service.EndpointId
import io.github.cubsync.service.Section
import io.github.cubsync.service.ServerController
import io.github.cubsync.service.SettingsStorage
import io.github.cubsync.service.Side
import io.github.cubsync.service.SyncService
import io.github.cubsync.service.ThemeMode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every phone screen, composed off screen and written to a PNG.
 *
 * There is no emulator here, and "it compiles" says nothing about whether a layout looks
 * like the design — which is exactly how a screen ends up shipped with no icons in its
 * bottom bar. Robolectric drives a real composition on the JVM and this draws it into a
 * bitmap, so a screen can be looked at before anybody installs anything.
 *
 * The drawing is done by hand rather than through Compose's `captureToImage`, which waits
 * on a window traversal that never happens off device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun every_screen_renders() {
        val fixture = Fixture()
        try {
            compose.setContent {
                CubAndroidTheme(mode = ThemeMode.DARK) { CubAndroidApp(fixture.state) }
            }

            // 连接 — 运行模式收起，是默认那一屏。
            fixture.state.go(Section.CONNECT)
            shot("01-connect", fixture)

            // 同步 · 配置列表
            fixture.state.go(Section.SYNC)
            shot("02-sync-list", fixture)

            // 同步 · 配置详情
            fixture.state.open(Route.Config("cfg-1"))
            shot("03-sync-config", fixture)

            // 同步 · 差异预览
            fixture.state.open(Route.Preview("cfg-1"))
            shot("04-sync-preview", fixture)

            // 正在同步
            fixture.state.open(Route.Running("job-1"))
            shot("05-running", fixture)

            // 作业（两段里的第一段）
            fixture.state.go(Section.JOBS)
            shot("06-jobs", fixture)

            // 作业 · 日志段
            fixture.state.jobsSegment = JobsSegment.LOGS
            shot("07-logs", fixture)
            fixture.state.jobsSegment = JobsSegment.JOBS

            // 作业条目详情
            fixture.state.open(Route.Job(fixture.jobId))
            shot("08-job-detail", fixture)

            // 设置
            fixture.state.go(Section.SETTINGS)
            shot("09-settings", fixture)

            // 传输（双栏）
            fixture.state.go(Section.TRANSFER)
            shot("10-transfer", fixture)

            // 抽屉
            fixture.state.drawerOpen = true
            shot("11-drawer", fixture)
            fixture.state.drawerOpen = false

            // 传输 · 对比档（两栏按名称对齐，缺项是灰行）
            fixture.state.setDisplayMode(Side.LEFT, io.github.cubsync.service.DisplayMode.COMPARE)
            shot("12-transfer-compare", fixture)

            // 传输 · 卡片档（网格，列数来自设置）
            fixture.state.setDisplayMode(Side.LEFT, io.github.cubsync.service.DisplayMode.CARD)
            fixture.state.setDisplayMode(Side.RIGHT, io.github.cubsync.service.DisplayMode.CARD)
            shot("13-transfer-card", fixture)

            // 副行 ⋮ 的「更多」
            fixture.state.setDisplayMode(Side.LEFT, io.github.cubsync.service.DisplayMode.DETAIL)
            fixture.state.setDisplayMode(Side.RIGHT, io.github.cubsync.service.DisplayMode.DETAIL)
            fixture.state.moreSheet = true
            shot("14-more-sheet", fixture)
        } finally {
            fixture.close()
        }
    }

    /** Draw the whole window into a PNG, without whatever toast happens to be up. */
    private fun shot(name: String, fixture: Fixture) {
        fixture.state.dismissNotice()
        compose.waitForIdle()

        val metrics = compose.activity.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val decor: View = compose.activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))

        val output = File("build/screenshots-phone").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** A state with real folders in it, so a listing and a run have something to show. */
    private class Fixture {
        val directory: Path = Files.createTempDirectory("cubsync-phone-shot-")
        val log = AppLog()
        val service = SyncService(
            stateDirectory = directory,
            log = log,
            settingsStorage = SettingsStorage.memory(),
        )
        val server = ServerController(log)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val state = AndroidAppState(service, server, scope, directory.toFile())
        val jobId: String

        init {
            val camera = folder("DCIM/Camera")
            seed(camera, "IMG_20261016_0731.jpg", 2_400)
            seed(camera, "IMG_20261016_0730.jpg", 1_950)
            seed(folder("Download"), "manual.pdf", 8_400)
            seed(folder("Pictures"), "screenshot.png", 900)

            val config = SyncConfig(
                configId = "cfg-1",
                name = "照片库",
                direction = SyncDirection.TWO_WAY,
                pairs = listOf(
                    FolderPair(
                        pairId = "pair-1",
                        order = 0,
                        name = "相机 → 备份",
                        left = Endpoint(EndpointId.LOCAL, camera.toAbsolutePath().toString().replace('\\', '/')),
                        right = Endpoint(EndpointId.LOCAL, folder("Mirror").toAbsolutePath().toString().replace('\\', '/')),
                    ),
                ),
            )
            service.configs.save(config)
            service.settings.serverNodeName = "Redmi-Note-Pro"
            service.settings.sharedRoots = listOf(folder("Download").toString())

            runBlocking {
                state.openPane(Side.LEFT, camera.toString())
                state.openPane(Side.RIGHT, folder("Mirror").toString())
            }
            state.reloadConfigs()
            state.selectedConfigId = config.configId

            // A finished run, so 作业 and 作业详情 have a real record behind them.
            state.runConfig(config, dryRun = false)
            runBlocking {
                withTimeout(60_000) { while (state.jobs.isEmpty()) delay(20) }
                state.go(Section.SYNC)
            }
            jobId = state.jobs.first().jobId

            // And a real comparison, so 差异预览 counts something rather than apologising.
            seed(camera, "IMG_20261016_0740.jpg", 3_100)
            state.previewConfig(config)
            runBlocking {
                withTimeout(60_000) { while (state.preview == null) delay(20) }
                state.go(Section.SYNC)
            }

            state.running = RunningJob(
                jobId = jobId,
                configName = "照片库",
                dryRun = false,
                pairs = listOf("相机 → 备份", "相册 ↔ DCIM/相册", "视频 ↔ Movies", "文档 ↔ Documents"),
                startedAtMs = System.currentTimeMillis() - 42_000,
                currentPair = "视频 ↔ Movies",
                finishedPairs = 2,
                bytes = 2_900_000_000L,
            )
        }

        private fun folder(name: String): Path {
            val path = directory.resolve(name)
            Files.createDirectories(path)
            return path
        }

        private fun seed(directory: Path, name: String, length: Int) {
            Files.write(directory.resolve(name), ByteArray(length))
        }

        fun close() {
            runCatching { state.close() }
            scope.cancel()
        }
    }
}
