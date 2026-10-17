package io.github.cubsync.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 读共享存储的权限。
 *
 * 这个应用此前不申请任何存储权限，manifest 里那段注释把理由写得很确定：「它不是媒体
 * 浏览器，读的是给它的路径，而现代安卓上那些路径本来就是它能打开的共享存储」。
 * **那句话是错的。** targetSdk 33 起，没有 READ_MEDIA_* 的应用可以看见目录树，却看不到
 * 里面的**文件** —— 于是真机上打开一个文件夹只能看到子文件夹，看不到任何文件。
 *
 * 症状很安静：不报错、不弹窗、界面正常，只是东西少了。这类错误只有在真机上才看得见，
 * 所以它此前一直没被发现。
 *
 * 按系统版本申请对应的一组，已有的不重复问。
 */
@Composable
fun RequestStorageAccess() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 拒绝时不解释：没有权限时界面会自然地少掉东西，而申请会再来一次 */ }

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
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}
