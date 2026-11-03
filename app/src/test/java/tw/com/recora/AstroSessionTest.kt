package tw.com.recora

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
import tw.com.recora.data.api.AstroSession
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
    fun awaitHoldReturnsAfterModeSwitchAndKeepsItUntilClosed() = runTest {
        val fake = Fake(recording = true)
        val s = session(fake)
        val lease = s.awaitHold("test")
        // 不用 runCurrent：回傳時已經切好了（串流播放要等這個才開始讀檔）
        assertEquals(listOf("stop", "playback"), fake.sent)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("stop", "playback"), fake.sent)
        lease.close()
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
    fun leaseReleasedAfterResetSendsNothing() = runTest {
        // 突然斷線：先 reset（onDisconnected），畫面之後才歸還租約；不能對已經離線的記錄器送恢復指令
        val fake = Fake(recording = true)
        val s = session(fake)
        val lease = s.hold("串流播放")
        runCurrent()
        assertEquals(listOf("stop", "playback"), fake.sent)
        s.reset()
        lease.close()
        advanceUntilIdle()
        assertEquals(listOf("stop", "playback"), fake.sent)
    }

    @Test
    fun appBackgroundRestoresOnlyWhatWeChanged() = runTest {
        val fake = Fake(recording = true)
        val s = session(fake)
        s.restoreIfIdle() // 這次連線沒動過記錄器：不送指令
        assertEquals(emptyList<String>(), fake.sent)

        val lease = s.hold("串流播放")
        runCurrent()
        s.restoreIfIdle() // 有人在用檔案：不動
        assertEquals(listOf("stop", "playback"), fake.sent)

        lease.close()
        s.restoreIfIdle() // 不等 10 秒
        assertEquals(listOf("stop", "playback", "movie", "start"), fake.sent)
        advanceUntilIdle() // 閒置計時到時不會再送一次
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
    fun filesPageStoppingBeforeOnConnectedStillRestartsRecording() = runTest {
        // 連線狀態一發出，「檔案」頁就停錄；對時等指令之後 onConnected 才跑，不能忘記是我們停的
        val fake = Fake(recording = true)
        val s = session(fake)
        val lease = s.hold("檔案頁")
        runCurrent()
        s.onConnected()
        assertEquals(listOf("stop", "playback"), fake.sent)
        lease.close()
        advanceUntilIdle()
        assertEquals(listOf("stop", "playback", "movie", "start"), fake.sent)
    }

    @Test
    fun idleDashcamAtConnectStartsRecordingAfterFilesReleased() = runTest {
        // 上次中途斷線留下沒在錄影的記錄器，連上時「檔案」頁拿著租約：用完要開始錄影
        val fake = Fake(recording = false)
        val s = session(fake)
        val lease = s.hold("檔案頁")
        s.onConnected()
        runCurrent()
        assertTrue("start" !in fake.sent)
        lease.close()
        advanceUntilIdle()
        assertEquals("start", fake.sent.last())
        assertTrue(fake.recording)
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
