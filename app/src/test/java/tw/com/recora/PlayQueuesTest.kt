package tw.com.recora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.com.recora.data.PlayItem
import tw.com.recora.ui.player.PlayQueues

class PlayQueuesTest {
    @Test
    fun takeOnceAndDropStaleEntries() {
        val queues = PlayQueues()
        val list = listOf(PlayItem("content://1", "a"), PlayItem("content://2", "b"))
        val id = queues.put(list)
        assertEquals(list, queues.take(id))
        // 取走一次就沒了；0 一定沒有
        assertNull(queues.take(id))
        assertNull(queues.take(0))
        // 最多留 4 個沒取走的：最舊的丟掉
        val ids = (1..5).map { queues.put(listOf(PlayItem("content://$it", "$it"))) }
        assertNull(queues.take(ids.first()))
        assertEquals("5", queues.take(ids.last())!!.single().title)
    }
}
