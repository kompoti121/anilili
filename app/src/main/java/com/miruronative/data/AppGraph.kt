package com.miruronative.data

import android.content.Context
import com.miruronative.data.cache.AppCache
import com.miruronative.data.remote.AniListClient
import com.miruronative.data.remote.AniSkipClient
import com.miruronative.data.remote.AnivexaClient
import com.miruronative.data.remote.JikanClient
import com.miruronative.data.remote.MalClient
import com.miruronative.data.remote.PipeClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Cache
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tiny manual DI container. Built once from [MiruroApp.onCreate] and read by ViewModels.
 * Avoids pulling in Hilt for a project this size.
 */
object AppGraph {
    lateinit var repository: MiruroRepository
        private set
    lateinit var httpClient: OkHttpClient
        private set

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (::repository.isInitialized) return

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        httpClient = OkHttpClient.Builder()
            .cache(Cache(File(context.applicationContext.cacheDir, "http"), 50L * 1024 * 1024))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val aniList = AniListClient(httpClient, json)
        repository = MiruroRepository(
            aniList = aniList,
            pipe = PipeClient(json),
            anivexa = AnivexaClient(httpClient, json, aniList),
            jikan = JikanClient(httpClient, json),
            aniSkip = AniSkipClient(httpClient, json),
            mal = MalClient(httpClient, json),
            cache = AppCache(context, json),
        )
    }
}
