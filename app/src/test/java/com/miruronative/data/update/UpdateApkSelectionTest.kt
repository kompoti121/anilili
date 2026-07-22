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

    /** Asset names since the underscore rename that keeps Anilili.apk first alphabetically. */
    private val underscoreAssets = listOf(
        "Anilili.apk",
        "Anilili_arm64-v8a.apk",
        "Anilili_armeabi-v7a.apk",
    )

    @Test
    fun underscoreNamedSplitsResolvePerAbi() {
        assertEquals(
            "Anilili_arm64-v8a.apk",
            preferredReleaseApkName(underscoreAssets, listOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            "Anilili_armeabi-v7a.apk",
            preferredReleaseApkName(underscoreAssets, listOf("armeabi-v7a")),
        )
        assertEquals("Anilili.apk", preferredReleaseApkName(underscoreAssets, listOf("x86_64")))
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
