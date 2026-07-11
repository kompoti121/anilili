package com.miruronative.data

import android.content.Context
import com.miruronative.data.remote.AniListClient
import com.miruronative.data.remote.AnivexaClient
import com.miruronative.data.remote.PipeClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Tiny manual DI container. Built once from [MiruroApp.onCreate] and read by ViewModels.
 * Avoids pulling in Hilt for a project this size.
 */
object AppGraph {
    lateinit var repository: MiruroRepository
        private set

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (::repository.isInitialized) return

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val aniList = AniListClient(client, json)
        repository = MiruroRepository(
            aniList = aniList,
            pipe = PipeClient(json),
            anivexa = AnivexaClient(client, json, aniList),
        )
    }
}
