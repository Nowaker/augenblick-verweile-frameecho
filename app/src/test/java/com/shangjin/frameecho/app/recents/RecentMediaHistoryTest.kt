package com.shangjin.frameecho.app.recents

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RecentMediaHistoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun opened(entries: List<RecentMedia>, uri: String, at: Long, access: RecentAccess = RecentAccess.PERSISTED_GRANT) =
        RecentMediaHistory.recordOpened(entries, uri, "$uri.mp4", "video/mp4", access, at).entries

    @Test
    fun `visible list is sorted by last access, counting saves`() {
        var entries = opened(emptyList(), "a", 100)
        entries = opened(entries, "b", 200)
        entries = opened(entries, "c", 300)
        entries = RecentMediaHistory.recordSaved(entries, "a", "a.mp4", "video/mp4", RecentAccess.PERSISTED_GRANT, 400).entries

        assertEquals(listOf("a", "c", "b"), RecentMediaHistory.visible(entries, false).map { it.uri })
    }

    @Test
    fun `saved-frames filter shows only files a frame was saved from`() {
        var entries = opened(emptyList(), "a", 100)
        entries = opened(entries, "b", 200)
        entries = RecentMediaHistory.recordSaved(entries, "a", "a.mp4", null, RecentAccess.PERSISTED_GRANT, 300).entries
        entries = RecentMediaHistory.recordSaved(entries, "a", "a.mp4", null, RecentAccess.PERSISTED_GRANT, 350).entries

        val saved = RecentMediaHistory.visible(entries, onlyWithSavedFrames = true)
        assertEquals(listOf("a"), saved.map { it.uri })
        assertEquals(2, saved.single().savedFrameCount)
        assertEquals(350L, saved.single().lastSavedMs)
        assertEquals("video/mp4", saved.single().mimeType)
    }

    @Test
    fun `reopening keeps a single entry and never downgrades a durable grant`() {
        var entries = opened(emptyList(), "a", 100, RecentAccess.PERSISTED_GRANT)
        entries = RecentMediaHistory.recordSaved(entries, "a", "a.mp4", null, RecentAccess.PERSISTED_GRANT, 150).entries
        entries = opened(entries, "a", 200, RecentAccess.TEMPORARY_GRANT)

        val entry = entries.single()
        assertEquals(RecentAccess.PERSISTED_GRANT, entry.access)
        assertEquals(200L, entry.lastOpenedMs)
        assertEquals(1, entry.savedFrameCount)
    }

    @Test
    fun `list is bounded and reports the evicted oldest entries`() {
        var entries = emptyList<RecentMedia>()
        repeat(RecentMediaHistory.MAX_ENTRIES) { entries = opened(entries, "u$it", it.toLong()) }
        val update = RecentMediaHistory.recordOpened(entries, "new", "new.mp4", null, RecentAccess.MEDIA_STORE, 10_000)

        assertEquals(RecentMediaHistory.MAX_ENTRIES, update.entries.size)
        assertEquals(listOf("u0"), update.evicted.map { it.uri })
        assertEquals("new", update.entries.first().uri)
    }

    @Test
    fun `codec round-trips awkward names and skips malformed lines`() {
        val entries = listOf(
            RecentMedia("content://x/1?a=b&c", "tab\there\nnewline 名前.mp4", null, RecentAccess.TEMPORARY_GRANT, 5, null, 0),
            RecentMedia("content://x/2", "b.jpg", "image/jpeg", RecentAccess.MEDIA_STORE, 6, 9, 3)
        )
        val encoded = RecentMediaCodec.encode(entries)

        assertEquals(entries, RecentMediaCodec.decode(encoded))
        assertEquals(entries.take(1), RecentMediaCodec.decode(encoded.lines().take(2).joinToString("\n") + "\nbroken\tline"))
        assertTrue(RecentMediaCodec.decode("v0\nanything").isEmpty())
        assertTrue(RecentMediaCodec.decode(null).isEmpty())
    }

    @Test
    fun `store persists outcomes and hands evicted entries back`() {
        val testScope = TestScope(UnconfinedTestDispatcher() + Job())
        testScope.runTest {
            var now = 0L
            val evicted = mutableListOf<RecentMedia>()
            val store = RecentMediaStore(
                PreferenceDataStoreFactory.create(scope = testScope, produceFile = { tmpFolder.newFile("recents.preferences_pb") }),
                clock = { now }
            ) { evicted += it }
            fun uri(value: String): Uri = mockk { every { this@mockk.toString() } returns value }

            repeat(RecentMediaHistory.MAX_ENTRIES + 1) { index ->
                now = index.toLong()
                store.recordOpened(uri("content://m/$index"), "$index.mp4", "video/mp4", RecentAccess.PERSISTED_GRANT)
            }
            now = 1_000
            store.recordSaved(uri("content://m/5"), "5.mp4", "video/mp4", RecentAccess.PERSISTED_GRANT)

            val entries = store.entries.first()
            assertEquals(listOf("content://m/0"), evicted.map { it.uri })
            assertEquals("content://m/5", RecentMediaHistory.visible(entries, true).single().uri)

            store.remove("content://m/5")
            assertTrue(store.entries.first().none { it.uri == "content://m/5" })
        }
    }
}
