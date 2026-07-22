package com.miruronative.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateApkSelectionTest {
    private val assets = listOf(
        "Anilili-armeabi-v7a.apk",
        "Anilili.apk",
        "Anilili-arm64-v8a.apk",
    )

    /** Releases published before v0.1.34 used all-lowercase asset names. */
    private val legacyAssets = listOf(
        "anilili-armeabi-v7a.apk",
        "anilili.apk",
        "anilili-arm64-v8a.apk",
    )

    @Test
    fun arm64DeviceGetsArm64Split() {
        assertEquals(
            "Anilili-arm64-v8a.apk",
            preferredReleaseApkName(assets, listOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun armV7DeviceGetsArmV7Split() {
        assertEquals(
            "Anilili-armeabi-v7a.apk",
            preferredReleaseApkName(assets, listOf("armeabi-v7a")),
        )
    }

    @Test
    fun unknownAbiFallsBackToUniversal() {
        assertEquals("Anilili.apk", preferredReleaseApkName(assets, listOf("x86_64")))
    }

    @Test
    fun legacyLowercaseAssetsStillResolve() {
        assertEquals(
            "anilili-arm64-v8a.apk",
            preferredReleaseApkName(legacyAssets, listOf("arm64-v8a")),
        )
        assertEquals("anilili.apk", preferredReleaseApkName(legacyAssets, listOf("x86_64")))
    }
}
