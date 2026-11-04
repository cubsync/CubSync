package io.github.cubsync.service.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.cubsync.core.model.SyncConfig
import io.github.cubsync.service.JobRecord
import io.github.cubsync.service.JobsSegment
import io.github.cubsync.service.RunningJob
import io.github.cubsync.service.SyncLayer
import io.github.cubsync.service.SyncPreview

/**
 * 同步页 + 作业页的界面状态：配置列表、正在跑的作业、试运行预览。
 * 之前摊在 AppState，抽出来让"同步/作业"这一域有自己的名字，UI 读 `state.sync.xxx`。
 */
class SyncState {
    var configs by mutableStateOf<List<SyncConfig>>(emptyList())
    var selectedConfigId by mutableStateOf<String?>(null)
    var syncLayer by mutableStateOf(SyncLayer.LIST)
    var syncSearch by mutableStateOf("")
    var preview by mutableStateOf<SyncPreview?>(null)
    var previewing by mutableStateOf(false)
    var jobs by mutableStateOf<List<JobRecord>>(emptyList())
    var selectedJobId by mutableStateOf<String?>(null)
    var jobsSegment by mutableStateOf(JobsSegment.JOBS)
    var running by mutableStateOf<RunningJob?>(null)
    var runPaused by mutableStateOf(false)
}
