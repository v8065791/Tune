package dev.tune.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dev.tune.player.art.ArtFetcher
import dev.tune.player.art.ArtKeyer
import dev.tune.player.data.MusicRepository

class TuneApplication : Application(), ImageLoaderFactory {

    /** Single repository instance shared by every ViewModel. */
    val repository: MusicRepository by lazy { MusicRepository(this) }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(ArtKeyer())
                add(ArtFetcher.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.20).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
}
