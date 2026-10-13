package tw.com.mufudashcam

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.com.mufudashcam.data.api.AstroSession
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AstroSessionTest {
    private class Fake(var recording: Boolean) : AstroSession.Commands {
        val sent = mutableListOf<String>()
        override suspend fun isRecording() = recording
        override suspend fun stopRecording() {
            sent += "stop"; recording = false
        }
        override suspend fun startRecording() {
            sent += "start"; recording = true
        }
        override suspend fun enterPlayback() {
            sent += "playback"
        }
        override suspend fun enterMovie() {
            sent += "movie"
        }
    }

    private fun TestScope.session(fake: Fake) = AstroSession(this, fake, idleMs = 10_000)

    @Test
    fun stopsRecordingThenEntersPlaybackAndRestoresAfterTenSeconds() = runTest {
        val fake = Fake(recording = true)
        val s = session(fake)
        s.withFiles { assertEquals(listOf("stop", "playback"), fake.sent) }
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(listOf("stop", "playback"), fake.sent) // 還沒到 10 秒
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf("stop", "playback", "movie", "start"), fake.sent)
    }

    @Test
    fun reacquiringWithinIdleWindowCancelsRestore() = runTest {
        val fake = Fake(recording = true)
        val s = session(fake)
        s.withFiles { }
        advanceTimeBy(5_000)
        s.withFiles { } // 計時中重新取得：不用再停錄、切模式
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(listOf("stop", "playback"), fake.sent)
        advanceUntilIdle()
        assertEquals(listOf("stop", "playback", "movie", "start"), fake.sent)
    }

    @Test
    fun heldLeaseKeepsFilesModeUntilClosed() = runTest {
        val fake = Fake(recording = true)
        val s = session(fake)
        val lease = s.hold("test")
        runCurrent()
        assertEquals(listOf("stop", "playback"), fake.sent)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("stop", "playback"), fake.sent) // 租約還在，不恢復
        lease.close()
        lease.close() // 重複 close 沒有副作用
        advanceUntilIdle()
        assertEquals(listOf("stop", "playback", "movie", "start"), fake.sent)
    }

    @Test
    fun disconnectRestoresImmediately() = runTest {
        val fake = Fake(recording = true)
        val s = session(fake)
        s.hold("test")
        runCurrent()
        s.restoreNow(force = true)
        assertEquals(listOf("stop", "playback", "movie", "start"), fake.sent)
    }

    @Test
    fun doesNotStartRecordingWeDidNotStop() = runTest {
        val fake = Fake(recording = false)
        val s = session(fake)
        s.withFiles { }
        advanceUntilIdle()
        assertEquals(listOf("playback", "movie"), fake.sent)
        assertTrue("start" !in fake.sent)
    }

    @Test
    fun safetyNetStartsRecordingWhenIdleAndNotRecording() = runTest {
        val fake = Fake(recording = false)
        val s = session(fake)
        s.onConnected()
        assertEquals(listOf("movie", "start"), fake.sent)
    }

    @Test
    fun safetyNetDoesNothingWhenAlreadyRecording() = runTest {
        val fake = Fake(recording = true)
        val s = session(fake)
        s.onConnected()
        assertEquals(emptyList<String>(), fake.sent)
    }

    @Test
    fun changingSettingsStopsInMovieModeButRefusesWhileFilesInUse() = runTest {
        val fake = Fake(recording = true)
        val s = session(fake)
        s.withRecordingStopped { }
        assertEquals(listOf("movie", "stop"), fake.sent)

        val busy = s.hold("download")
        runCurrent()
        try {
            s.withRecordingStopped { }
            fail("下載中不能改設定")
        } catch (e: IOException) {
            // 預期
        }
        busy.close()
    }
}
