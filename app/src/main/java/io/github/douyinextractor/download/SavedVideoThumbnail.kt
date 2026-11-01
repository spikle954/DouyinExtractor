package io.github.douyinextractor.download

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.douyinextractor.ui.CoverFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Bounded in-memory previews of owned files only. No storage scan, new permissions or remote cover requests. */
private object ThumbnailCache {
    private val images = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val misses = LinkedHashSet<String>()
    @Synchronized fun get(key: String) = images.get(key)
    @Synchronized fun missed(key: String) = key in misses
    @Synchronized fun put(key: String, bitmap: Bitmap?) {
        if (bitmap != null) images.put(key, bitmap)
        else { misses.add(key); if (misses.size > 50) misses.remove(misses.first()) }
    }
}

// Bound native decoder work while scrolling through history. Thumbnails never read remote URLs.
private val previewSlots = Semaphore(2)

@Composable
fun SavedVideoThumbnail(item: SavedDownload, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val resolver = context.contentResolver
    val cacheKey = "${item.uri}:${item.bytes}:${item.savedAt}"
    val bitmap by produceState<ImageBitmap?>(null, cacheKey) {
        value = withContext(Dispatchers.IO) {
            if (!item.uri.startsWith("content://")) return@withContext null
            ThumbnailCache.get(cacheKey)?.asImageBitmap() ?: if (ThumbnailCache.missed(cacheKey)) null else {
                val image = previewSlots.withPermit {
                    coroutineContext.ensureActive()
                    val providerPreview = if (Build.VERSION.SDK_INT >= 29) {
                        try { resolver.loadThumbnail(item.uri.toUri(), Size(96, 128), null) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    } else null
                    coroutineContext.ensureActive()
                    providerPreview ?: if (Build.VERSION.SDK_INT >= 27) {
                        // SAF document providers may allow playback without implementing loadThumbnail.
                        var retriever: MediaMetadataRetriever? = null
                        try {
                            val reader = MediaMetadataRetriever().also { retriever = it }
                            reader.setDataSource(context, item.uri.toUri())
                            coroutineContext.ensureActive()
                            reader.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 128)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        finally { try { retriever?.release() } catch (_: Exception) { /* Best-effort cleanup. */ } }
                    } else null
                }
                coroutineContext.ensureActive()
                ThumbnailCache.put(cacheKey, image)
                image?.asImageBitmap()
            }
        }
    }
    CoverFrame(bitmap, modifier.size(48.dp, 64.dp))
}
