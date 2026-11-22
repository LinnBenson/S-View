package io.todu.sview.wear

import android.content.Context
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import androidx.concurrent.futures.CallbackToFutureAdapter
import io.todu.sview.wear.tile.WatchTileLayout
import io.todu.sview.wear.tile.WatchTileResources
import java.util.concurrent.Executors
import kotlin.math.min

/** 系统按需绑定，只有同步完成后主动请求更新；磁贴没有轮询和保活任务。 */
class WatchProjectTileService : TileService() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> = submit {
        val projects = WatchTileStore.projects(this)
        val device = requestParams.deviceConfiguration
        val size = min(device.screenWidthDp, device.screenHeightDp).toFloat()
        val layout = WatchTileLayout.create(this, projects, WatchTileStore.token(this), size, device.fontScale)
        Tile.Builder().setResourcesVersion(WatchTileResources.version(projects))
            .setTileTimeline(Timeline.Builder().addTimelineEntry(TimelineEntry.Builder()
                .setLayout(Layout.Builder().setRoot(layout).build()).build()).build()).build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<Resources> =
        submit { WatchTileResources.create(this, requestParams.version) }

    private fun <T> submit(block: () -> T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { result ->
        worker.execute { try { result.set(block()) } catch (error: Exception) { result.setException(error) } }
        "S View 磁贴"
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { getUpdater(context).requestUpdate(WatchProjectTileService::class.java) }
                .onFailure { android.util.Log.w("WatchTile", "暂时无法刷新磁贴", it) }
        }
    }
}
