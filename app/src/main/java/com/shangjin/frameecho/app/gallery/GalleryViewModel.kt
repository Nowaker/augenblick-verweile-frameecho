package com.shangjin.frameecho.app.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class GalleryPhase { NEEDS_PERMISSION, LOADING, LOADED, ERROR }

data class GalleryUiState(
    val access: GalleryAccess = GalleryAccess(videos = false, images = false, selectedOnly = false),
    val phase: GalleryPhase = GalleryPhase.NEEDS_PERMISSION,
    /** Newest first. Motion photos join as they are detected. */
    val items: List<GalleryItem> = emptyList(),
    val scanningMotionPhotos: Boolean = false,
    val stillsScanned: Int = 0,
    val stillsToScan: Int = 0,
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = phase == GalleryPhase.LOADED && items.isEmpty() && !scanningMotionPhotos
}

/**
 * Lists videos at once and motion photos as a bounded background scan finds them: only
 * the newest [MAX_STILLS_SCANNED] stills are inspected, [DETECTION_PARALLELISM] at a
 * time, and results are cached per file version so a refresh rescans only what changed.
 */
class GalleryViewModel(private val source: GallerySource) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private val detectionCache = HashMap<String, Boolean>()

    fun onAccessChanged(access: GalleryAccess) {
        if (access == _uiState.value.access && _uiState.value.phase != GalleryPhase.NEEDS_PERMISSION) return
        _uiState.update { it.copy(access = access) }
        if (access.canList) refresh() else {
            loadJob?.cancel()
            _uiState.update { GalleryUiState(access = access) }
        }
    }

    fun refresh() {
        val access = _uiState.value.access
        if (!access.canList) return
        loadJob?.cancel()
        _uiState.update { it.copy(phase = GalleryPhase.LOADING, errorMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val videos = if (access.videos || access.selectedOnly) source.videos() else emptyList()
                val candidates = if (access.images || access.selectedOnly) {
                    source.stillCandidates(MAX_STILLS_SCANNED)
                } else emptyList()
                val known = candidates.filter { detectionCache[cacheKey(it)] == true }
                val unknown = candidates.filter { detectionCache[cacheKey(it)] == null }
                _uiState.update {
                    it.copy(
                        phase = GalleryPhase.LOADED,
                        items = newestFirst(videos + known),
                        scanningMotionPhotos = unknown.isNotEmpty(),
                        stillsScanned = candidates.size - unknown.size,
                        stillsToScan = candidates.size
                    )
                }
                if (unknown.isNotEmpty()) detect(unknown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(phase = GalleryPhase.ERROR, scanningMotionPhotos = false, errorMessage = e.message)
                }
            }
        }
    }

    private suspend fun detect(stills: List<GalleryItem>) = coroutineScope {
        val permits = Semaphore(DETECTION_PARALLELISM)
        stills.map { still ->
            async {
                permits.withPermit {
                    // One unreadable file must not abort the whole scan.
                    val isMotion = try {
                        source.isMotionPhoto(still)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    detectionCache[cacheKey(still)] = isMotion
                    _uiState.update { state ->
                        state.copy(
                            items = if (isMotion) newestFirst(state.items + still) else state.items,
                            stillsScanned = state.stillsScanned + 1
                        )
                    }
                }
            }
        }.awaitAll()
        _uiState.update { it.copy(scanningMotionPhotos = false) }
    }

    private fun cacheKey(item: GalleryItem) = "${item.key}|${item.modifiedSeconds}|${item.sizeBytes}"

    private fun newestFirst(items: List<GalleryItem>) =
        items.distinctBy { it.key }.sortedWith(compareByDescending<GalleryItem> { it.dateMs }.thenBy { it.key })

    companion object {
        const val MAX_STILLS_SCANNED = 500
        const val DETECTION_PARALLELISM = 4
    }
}
