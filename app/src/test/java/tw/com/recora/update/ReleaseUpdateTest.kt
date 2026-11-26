package tw.com.recora.update

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ReleaseUpdateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun semverParsingAndComparison() {
        assertTrue(ReleasePicker.isNewer("v1.0.1", "1.0"))
        assertTrue(ReleasePicker.isNewer("v0.2.0", "0.1.0"))
        assertTrue(ReleasePicker.isNewer("V0.10.0", "0.2.0"))
        assertTrue(ReleasePicker.isNewer("1.0.0", "0.9.9"))
        assertTrue(ReleasePicker.isNewer("v1.2.0", "1.2.0-rc.1"))
        assertTrue(ReleasePicker.isNewer("1.2.0-rc.2", "1.2.0-rc.1"))
        assertTrue(ReleasePicker.isNewer("1.2.0-beta.10", "1.2.0-beta.2"))

        assertFalse(ReleasePicker.isNewer("v1.0.0", "1.0"))
        assertFalse(ReleasePicker.isNewer("v1.0.0+123", "1.0.0"))
        assertFalse(ReleasePicker.isNewer("v0.9.9", "1.0"))
        assertFalse(ReleasePicker.isNewer("v1.2.0-rc.1", "1.2.0"))
        assertFalse(ReleasePicker.isNewer("not-a-version", "1.0"))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("v0.-1.0"))
    }

    @Test
    fun evaluateLatestReleasePicksReleaseApkAndDigest() {
        val hash = "a".repeat(64)
        val json = """
            {
              "tag_name": "v1.0.1",
              "name": "Recora v1.0.1",
              "body": null,
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-24T08:00:00Z",
              "html_url": "https://github.com/ShiKi5473/Recora/releases/tag/v1.0.1",
              "assets": [
                {
                  "name": "app-release-unsigned.apk",
                  "size": 1000,
                  "browser_download_url": "https://github.com/ShiKi5473/Recora/releases/download/v1.0.1/app-release-unsigned.apk"
                },
                {
                  "name": "Recora-v1.0.1-debug.apk",
                  "size": 2000,
                  "browser_download_url": "https://github.com/ShiKi5473/Recora/releases/download/v1.0.1/Recora-v1.0.1-debug.apk"
                },
                {
                  "name": "Recora-v1.0.1.apk",
                  "size": 21500000,
                  "browser_download_url": "https://github.com/ShiKi5473/Recora/releases/download/v1.0.1/Recora-v1.0.1.apk",
                  "digest": "sha256:${hash.uppercase()}"
                }
              ]
            }
        """.trimIndent()

        val res = ReleasePicker.evaluateLatestRelease(json, "1.0", listOf("arm64-v8a", "armeabi-v7a"))
        assertTrue(res is ReleaseParseResult.UpdateAvailable)
        val update = (res as ReleaseParseResult.UpdateAvailable).update
        assertEquals("1.0.1", update.version)
        assertEquals("Recora-v1.0.1.apk", update.apkName)
        assertEquals(hash, update.expectedSha256)
        assertEquals("", update.notes)
        assertNull(update.checksumAssetUrl)
    }

    @Test
    fun evaluateLatestReleaseFallsBackToSidecarSha256AndMatchesAbi() {
        val json = """
            {
              "tag_name": "v1.1.0",
              "name": null,
              "body": "Bug fixes",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "Recora-v1.1.0-x86_64.apk",
                  "size": 100,
                  "browser_download_url": "https://github.com/ShiKi5473/Recora/releases/download/v1.1.0/Recora-v1.1.0-x86_64.apk"
                },
                {
                  "name": "Recora-v1.1.0-arm64-v8a.apk",
                  "size": 100,
                  "browser_download_url": "https://github.com/ShiKi5473/Recora/releases/download/v1.1.0/Recora-v1.1.0-arm64-v8a.apk"
                },
                {
                  "name": "Recora-v1.1.0-arm64-v8a.apk.sha256",
                  "size": 120,
                  "browser_download_url": "https://github.com/ShiKi5473/Recora/releases/download/v1.1.0/Recora-v1.1.0-arm64-v8a.apk.sha256"
                }
              ]
            }
        """.trimIndent()

        val res = ReleasePicker.evaluateLatestRelease(json, "1.0", listOf("arm64-v8a"))
        val update = (res as ReleaseParseResult.UpdateAvailable).update
        assertEquals("Recora-v1.1.0-arm64-v8a.apk", update.apkName)
        assertNull(update.expectedSha256)
        assertEquals(
            "https://github.com/ShiKi5473/Recora/releases/download/v1.1.0/Recora-v1.1.0-arm64-v8a.apk.sha256",
            update.checksumAssetUrl,
        )
        assertEquals("Bug fixes", update.notes)
    }

    @Test
    fun ignoreDraftPrereleaseUpToDateAndHttpUrls() {
        val upToDateJson = """{"tag_name": "v1.0.0", "assets": []}"""
        assertEquals(ReleaseParseResult.UpToDate, ReleasePicker.evaluateLatestRelease(upToDateJson, "1.0"))

        val prereleaseJson = """{"tag_name": "v1.0.1", "prerelease": true, "assets": []}"""
        assertEquals(ReleaseParseResult.UpToDate, ReleasePicker.evaluateLatestRelease(prereleaseJson, "1.0"))

        val httpOnlyJson = """
            {
              "tag_name": "v1.0.1",
              "assets": [
                {"name": "Recora-v1.0.1.apk", "browser_download_url": "http://example.com/app.apk"}
              ]
            }
        """.trimIndent()
        assertEquals(
            ReleaseParseResult.NoApkAsset("1.0.1"),
            ReleasePicker.evaluateLatestRelease(httpOnlyJson, "1.0"),
        )
    }

    @Test
    fun parseChecksumTextSupportsGnuBsdAndBare() {
        val h1 = "0123456789abcdef".repeat(4)
        val h2 = "fedcba9876543210".repeat(4)
        val gnuMulti = """
            $h1  Recora-v1.0.1-x86.apk
            $h2 *./Recora-v1.0.1.apk
        """.trimIndent()
        assertEquals(h2, ReleasePicker.parseChecksumText(gnuMulti, "Recora-v1.0.1.apk"))

        val bsd = "SHA256 (Recora-v1.0.1.apk) = ${h1.uppercase()}"
        assertEquals(h1, ReleasePicker.parseChecksumText(bsd, "Recora-v1.0.1.apk"))

        assertEquals(h1, ReleasePicker.parseChecksumText("$h1\n", "Recora-v1.0.1.apk"))
        assertNull(ReleasePicker.parseChecksumText("$h1  other.apk", "Recora-v1.0.1.apk"))
    }

    @Test
    fun cancelDownloadCancelsBackgroundAutoUpdateAndCleansTempFile() = runBlocking {
        val server = MockWebServer()
        val payload = ByteArray(128 * 1024) { 0x5A }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/latest") == true -> MockResponse().setBody(
                    """
                    {
                      "tag_name": "v99.0.0",
                      "name": "Recora v99.0.0",
                      "assets": [
                        {
                          "name": "Recora-v99.0.0.apk",
                          "size": ${payload.size},
                          "browser_download_url": "${server.url("/Recora-v99.0.0.apk")}"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                else -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", payload.size.toString())
                    .setBody(Buffer().write(payload))
                    .throttleBody(1024, 200, TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        val origUrl = AppUpdater.releaseApiUrl
        val origCleartext = AppUpdater.allowCleartextForTest
        val testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = tmp.root
            override fun getPackageName(): String = "tw.com.recora"
            override fun getSystemService(name: String): Any? = null
        }
        try {
            AppUpdater.releaseApiUrl = server.url("/latest").toString()
            AppUpdater.allowCleartextForTest = true

            val job = async(Dispatchers.IO) {
                AppUpdater.runAutoCheckAndUpdate(testContext, skippedVersion = "")
            }

            withTimeout(5_000) {
                while (AppUpdater.state.value !is AppUpdateState.Downloading) delay(20)
            }

            AppUpdater.cancelDownload(testContext)
            withTimeout(5_000) {
                runCatching { job.await() }
            }

            assertTrue(job.isCancelled)
            val st = AppUpdater.state.value
            assertTrue("預期狀態為 Available，實際為 $st", st is AppUpdateState.Available)
            assertFalse(File(File(tmp.root, "updates"), "download.apk.tmp").exists())
        } finally {
            AppUpdater.releaseApiUrl = origUrl
            AppUpdater.allowCleartextForTest = origCleartext
            AppUpdater.cancelDownload(testContext)
            server.shutdown()
        }
    }
}
