package io.github.cubsync.service.state

import io.github.cubsync.service.Adb
import io.github.cubsync.service.PeerSession
import io.github.cubsync.service.Section

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 连接页的界面状态：连到谁、连没连上、地址框里填了什么、USB 上插着什么。
 *
 * 之前这些字段都摊在 [AppState] 里，和其它五个域混在同一个 900 行的类。抽出来只做
 * 一件事：让"连接"这一域的状态有名字，UI 读 `state.connect.xxx`，方法动它也只动
 * 这一个对象 —— 改连接不再可能顺带碰坏收藏或传输。
 */
class ConnectState {

    var section by mutableStateOf(Section.CONNECT)

    var peers by mutableStateOf<List<PeerSession>>(emptyList())
    var peerError by mutableStateOf<String?>(null)

    /** The address box's two halves, kept apart so a port is optional and a colon is not. */
    var peerHost by mutableStateOf("")
    var peerPortText by mutableStateOf("")
    var peerDraft by mutableStateOf("")
    var connecting by mutableStateOf(false)

    /** USB: what adb can see, and the last thing it said. */
    var adbDevices by mutableStateOf<List<Adb.Device>>(emptyList())
    var adbNote by mutableStateOf<String?>(null)

    /** Roots the server editor is holding before it is started. */
    var rootDraft by mutableStateOf<List<String>>(emptyList())
}
