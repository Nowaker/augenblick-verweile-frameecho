package com.shangjin.frameecho.app.incoming

import android.net.Uri
import com.shangjin.frameecho.app.recents.RecentAccess
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingMediaTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun uri(value: String): Uri = mockk { every { this@mockk.toString() } returns value }

    private fun item(name: String, supported: Boolean = true) =
        IncomingItem(uri(name), name, null, RecentAccess.TEMPORARY_GRANT, supported)

    @Test
    fun `videos, pictures and untyped files are supported, other types are not`() {
        assertTrue(IncomingMediaQueue.isSupported("video/mp4", null))
        assertTrue(IncomingMediaQueue.isSupported("image/jpeg", "*/*"))
        assertTrue(IncomingMediaQueue.isSupported(null, "image/*"))
        assertTrue(IncomingMediaQueue.isSupported("application/octet-stream", "video/*"))
        assertTrue(IncomingMediaQueue.isSupported(null, null))
        assertFalse(IncomingMediaQueue.isSupported("application/pdf", "*/*"))
        assertFalse(IncomingMediaQueue.isSupported(null, "text/plain"))
    }

    @Test
    fun `a single openable file opens at once without a chooser`() {
        val single = item("a.mp4")
        val (queue, outcome) = IncomingMediaQueue().receive(listOf(single))

        assertEquals(IncomingOutcome.Open(single), outcome)
        assertFalse(queue.chooserVisible)
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun `several files are listed, picked one at a time and offered again after closing the player`() {
        val a = item("a.mp4")
        val b = item("b.jpg")
        val doc = item("c.pdf", supported = false)
        var (queue, outcome) = IncomingMediaQueue().receive(listOf(a, b, doc))
        assertEquals(IncomingOutcome.ShowChooser, outcome)
        assertTrue(queue.chooserVisible)

        assertNull(queue.pick(doc.uri).second)
        val picked = queue.pick(a.uri)
        queue = picked.first
        assertEquals(IncomingOutcome.Open(a), picked.second)
        assertFalse(queue.chooserVisible)
        assertEquals(listOf(b), queue.remaining)

        queue = queue.onMediaClosed()
        assertTrue(queue.chooserVisible)
        assertTrue(queue.items.first { it.uri == a.uri }.opened)

        queue = queue.pick(b.uri).first.onMediaClosed()
        assertEquals(IncomingMediaQueue(), queue)
    }

    @Test
    fun `nothing openable explains itself, naming a lone file`() {
        assertEquals(
            IncomingOutcome.NothingSupported("c.pdf"),
            IncomingMediaQueue().receive(listOf(item("c.pdf", false))).second
        )
        assertEquals(
            IncomingOutcome.NothingSupported(null),
            IncomingMediaQueue().receive(listOf(item("c.pdf", false), item("d.txt", false))).second
        )
    }

    @Test
    fun `view model describes files, keeps persistable grants and requests opens`() = runTest {
        val types = mapOf("content://v" to "video/mp4", "content://p" to "image/jpeg", "content://d" to "application/pdf")
        val persisted = mutableListOf<String>()
        val vm = IncomingMediaViewModel(
            describe = { IncomingDescription(it.toString().substringAfterLast('/'), types[it.toString()]) },
            takePersistableRead = { persisted += it.toString(); it.toString() != "content://p" }
        )

        vm.receive(listOf(uri("content://v")), intentType = "video/*", persistable = true)
        val opened = vm.openRequests.first()
        assertEquals(RecentAccess.PERSISTED_GRANT, opened.access)
        assertEquals("video/mp4", opened.mimeType)

        vm.receive(listOf(uri("content://v"), uri("content://p"), uri("content://d")), "*/*", persistable = false)
        assertTrue(vm.queue.value.chooserVisible)
        assertEquals(listOf(true, true, false), vm.queue.value.items.map { it.supported })
        assertTrue(vm.queue.value.items.all { it.access == RecentAccess.TEMPORARY_GRANT })
        assertEquals(listOf("content://v"), persisted)

        vm.pick(vm.queue.value.items[1])
        assertEquals("content://p", vm.openRequests.first().uri.toString())
        assertFalse(vm.pending.value)

        vm.receive(listOf(uri("content://d")), null, persistable = false)
        assertEquals(IncomingOutcome.NothingSupported("d"), vm.notice.value)
    }
}
