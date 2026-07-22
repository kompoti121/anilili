package com.miruronative.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateApkSelectionTest {
    private val assets = listOf(
        "anilili-armeabi-v7a.apk",
        "anilili.apk",
        "anilili-arm64-v8a.apk",
    )

    @Test
    fun arm64DeviceGetsArm64Split() {
        assertEquals(
            "anilili-arm64-v8a.apk",
            preferredReleaseApkName(assets, listOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun armV7DeviceGetsArmV7Split() {
        assertEquals(
            "anilili-armeabi-v7a.apk",
            preferredReleaseApkName(assets, listOf("armeabi-v7a")),
        )
    }

    @Test
    fun unknownAbiFallsBackToUniversal() {
        assertEquals("anilili.apk", preferredReleaseApkName(assets, listOf("x86_64")))
    }
}
