package io.github.cubsync.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 共享存储的两种访问权，一个文件管理器两样都需要。
 *
 * **一、按类型读取**（`READ_MEDIA_*`，API 33 起）。
 * 没有它，应用**能列目录、列不出文件**：打开一个文件夹只看到子文件夹，不报错、不弹窗，
 * 屏幕看着正常、只是东西少了。targetSdk 33 起才如此，所以它此前一直没被发现。
 *
 * **二、所有文件访问**（`MANAGE_EXTERNAL_STORAGE`，API 30 起）。
 * 只有它能读写**任意**路径，而不只是媒体库认得的那几种。同步工具要往别处写、要读
 * `.cubtmp` 这类自建文件、要进 `Android/data` 下的目录 —— 这些都超出「按类型读取」的范围。
 * 上一版只申请了第一样，于是能看不能写：**一个传不了文件的传输工具**。
 *
 * 第二样不能被弹窗授予，只能由用户在一个系统设置页里打开，所以这里把用户送过去；
 * 系统没有「打开过就不再问」的机制，所以每次进入界面都检查一次、缺了就再送一次。
 * 这是文件管理器的常规做法，也是这类应用能正常工作的前提。
 */
@Composable
fun RequestStorageAccess() {
    val context = LocalContext.current

    fun needsAllFiles(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 按类型的权限有了之后再要「所有文件」：两个弹窗同时出会互相盖住。
        if (needsAllFiles()) openAllFilesSettings()
    }

    LaunchedEffect(Unit) {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        } else if (needsAllFiles()) {
            openAllFilesSettings()
        }
    }
}
