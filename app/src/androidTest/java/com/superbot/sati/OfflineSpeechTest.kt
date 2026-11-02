package com.superbot.sati

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superbot.sati.speech.OfflineSpeech
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineSpeechTest {
    @Test
    fun networkOnlyAndNotYetInstalledVoicesAreNeverSelected() {
        val network = Voice("network", Locale.US, 500, 100, true, emptySet())
        val notInstalled = Voice(
            "missing", Locale.US, 500, 100, false,
            setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
        )
        val offline = Voice("offline", Locale.US, 200, 200, false, emptySet())
        assertEquals(offline, OfflineSpeech.voice(setOf(network, notInstalled, offline), Locale.US))
        assertNull(OfflineSpeech.voice(setOf(network, notInstalled), Locale.US))
    }

    @Test
    fun missingKoreanVoiceCannotFallBackToNetworkOrEnglish() {
        val english = Voice("en-local", Locale.US, 200, 200, false, emptySet())
        val koreanNetwork = Voice("ko-network", Locale.KOREA, 500, 100, true, emptySet())
        assertNull(OfflineSpeech.voice(setOf(english, koreanNetwork), Locale.KOREA))
    }

    @Test
    fun englishAndKoreanTextUsesMatchingVoiceSegments() {
        val segments = OfflineSpeech.segments("Good! 안녕. Try again!")
        assertEquals(listOf(Locale.US, Locale.KOREA, Locale.US), segments.map { it.first })
        assertEquals("Good! 안녕. Try again!", segments.joinToString("") { it.second })
    }

    @Test
    fun modelDownloadHasInternetPermissionButCleartextIsDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.INTERNET in permissions)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
    }
}
