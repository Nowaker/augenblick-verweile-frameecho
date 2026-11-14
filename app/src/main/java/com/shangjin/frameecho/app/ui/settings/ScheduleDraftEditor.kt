package com.shangjin.frameecho.app.ui.settings

import androidx.compose.runtime.saveable.listSaver
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleDraft

internal val ScheduleDraftSaver = listSaver<ScheduleDraft, String>(
    save = { draft ->
        listOf(draft.initialDelaySeconds) + draft.stages.flatMap { listOf(it.startAfterSeconds, it.framesPerSecond) }
    },
    restore = { saved ->
        ScheduleDraft(saved.first(), saved.drop(1).chunked(2).map { ScheduleDraft.StageDraft(it[0], it[1]) })
    }
)

internal fun ScheduleDraft.withoutStage(index: Int): ScheduleDraft {
    if (stages.size <= 1) return this
    val remaining = stages.filterIndexed { stageIndex, _ -> stageIndex != index }
    return copy(stages = if (index == 0) {
        remaining.mapIndexed { stageIndex, stage ->
            if (stageIndex == 0) stage.copy(startAfterSeconds = "0") else stage
        }
    } else remaining)
}

internal fun ScheduleDraft.reconcileSavedDraft(previous: ScheduleDraft, current: ScheduleDraft): ScheduleDraft =
    if (this == previous) current else this
