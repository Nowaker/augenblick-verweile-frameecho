package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class FrameTimestampIndex(private val context: Context) {
    @Volatile
    private var cachedFrameTimestamps: Pair<String, LongArray>? = null
    @Volatile
    private var cachedSyncTimestamps: Pair<String, LongArray>? = null

    suspend fun syncAtOrBefore(videoUri: Uri, timestampUs: Long): Long {
        cachedSyncTimestamps?.takeIf { it.first == videoUri.toString() }?.second?.let { syncs ->
            if (syncs.isEmpty()) return 0L
            val match = syncs.binarySearch(timestampUs)
            val index = if (match >= 0) match else -match - 2
            return syncs.getOrElse(index) { 0L }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, videoUri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("The selected file has no video track")
            extractor.selectTrack(track)
            val coroutine = currentCoroutineContext()
            var syncUs = 0L
            while (extractor.sampleTime >= 0L) {
                coroutine.ensureActive()
                val candidate = extractor.sampleTime
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 && candidate <= timestampUs && candidate > syncUs) syncUs = candidate
                if (!extractor.advance()) break
            }
            return syncUs
        } finally {
            extractor.release()
        }
    }

    suspend fun nearest(videoUri: Uri, timestampUs: Long): Long {
        val requestedUs = timestampUs.coerceAtLeast(0L)
        val key = videoUri.toString()
        cachedFrameTimestamps?.takeIf { it.first == key }?.second?.let { timestamps ->
            val match = timestamps.binarySearch(requestedUs)
            if (match >= 0) return timestamps[match]
            val next = -match - 1
            if (next == 0) return timestamps.first()
            if (next == timestamps.size) return timestamps.last()
            return if (requestedUs - timestamps[next - 1] <= timestamps[next] - requestedUs) {
                timestamps[next - 1]
            } else timestamps[next]
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, videoUri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("The selected file has no video track")
            extractor.selectTrack(track)
            val coroutineContext = currentCoroutineContext()
            var timestamps = LongArray(1024)
            var count = 0
            val syncs = ArrayList<Long>()
            var retainSyncs = true
            var retainIndex = true
            var nearestUs = -1L
            var distanceUs = Long.MAX_VALUE
            while (extractor.sampleTime >= 0L) {
                coroutineContext.ensureActive()
                val candidateUs = extractor.sampleTime
                if (retainSyncs && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    if (syncs.size == MAX_SYNC_TIMESTAMP_COUNT) {
                        retainSyncs = false
                        syncs.clear()
                    } else syncs += candidateUs
                }
                val candidateDistanceUs = kotlin.math.abs(candidateUs - requestedUs)
                if (nearestUs < 0L || candidateDistanceUs < distanceUs ||
                    (candidateDistanceUs == distanceUs && candidateUs < nearestUs)
                ) {
                    nearestUs = candidateUs
                    distanceUs = candidateDistanceUs
                }
                if (retainIndex) {
                    if (count == MAX_FRAME_TIMESTAMP_COUNT) {
                        retainIndex = false
                        timestamps = LongArray(0)
                    } else {
                        if (count == timestamps.size) timestamps = timestamps.copyOf(count * 2)
                        timestamps[count++] = candidateUs
                    }
                }
                if (!extractor.advance()) break
            }
            check(nearestUs >= 0L) { "The video contains no decodable frame samples" }
            // A sync sample is not a presentation-order boundary in open GOPs.
            // Cache only complete indexes (at most 8 MiB); longer tracks retain
            // correctness with a complete O(1)-memory scan on each capture.
            if (retainIndex) cachedFrameTimestamps = key to timestamps.copyOf(count).apply { sort() }
            if (retainSyncs) cachedSyncTimestamps = key to syncs.toLongArray().apply { sort() }
            return nearestUs
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val MAX_FRAME_TIMESTAMP_COUNT = 1_048_576
        const val MAX_SYNC_TIMESTAMP_COUNT = 32_768
    }
}
