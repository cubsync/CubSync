package io.github.cubsync.service.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.cubsync.service.FavDialog
import io.github.cubsync.service.Favorite

/**
 * 收藏夹（侧边抽屉）的界面状态：正在编辑哪条、菜单开在哪、排序开关。
 */
class FavouriteState {
    var favouriteDraft by mutableStateOf<String?>(null)
    var favouriteMenu by mutableStateOf<Favorite?>(null)
    var favouriteMenuAt by mutableStateOf(Offset.Zero)
    var favouriteDialog by mutableStateOf<FavDialog?>(null)
    var favouriteHeaderMenu by mutableStateOf(false)
    var favouriteSorting by mutableStateOf(false)
}
