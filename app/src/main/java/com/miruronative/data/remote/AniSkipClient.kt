package com.miruronative.data.remote

import com.miruronative.data.model.SkipTimes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AniSkip community skip-time database (aniskip.com), keyed by MAL id. Fallback for streaming
 * providers that don't carry intro/outro markers of their own (most Anivexa providers).
 */
class AniSkipClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /** Intro/outro windows for one episode; null when AniSkip has none (unknown episodes 404). */
    fun skipTimes(malId: Int, episode: Int): SkipTimes? {
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode".toHttpUrl().newBuilder()
            .addQueryParameter("types[]", "op")
            .addQueryParameter("types[]", "ed")
            .addQueryParameter("episodeLength", "0")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val root = client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) error("AniSkip HTTP ${response.code}")
            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
        if (root["found"]?.jsonPrimitive?.booleanOrNull != true) return null
        var intro: Pair<Double, Double>? = null
        var outro: Pair<Double, Double>? = null
        root["results"]?.jsonArray?.forEach { element ->
            val result = element.jsonObject
            val interval = result["interval"]?.jsonObject ?: return@forEach
            val start = interval["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            val end = interval["endTime"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            when (result["skipType"]?.jsonPrimitive?.contentOrNull) {
                "op" -> intro = start to end
                "ed" -> outro = start to end
            }
        }
        if (intro == null && outro == null) return null
        return SkipTimes(intro?.first, intro?.second, outro?.first, outro?.second)
    }
}
