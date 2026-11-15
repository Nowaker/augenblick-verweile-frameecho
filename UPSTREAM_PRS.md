# Upstream PR drafts

These are draft descriptions, not submitted PRs. The combined upstreamable implementation is on `feature/integration`; focused contribution branches still need to be extracted and checked before submission. Fork branding, application ID/build header, fork CI and these documents are outside all four proposed PRs.

Suggested dependency order: Motion Photo import first; gallery/recents/sharing next; frame stepping/previews/precision after their source and settings integration; export fidelity after the source and exact-frame foundations. Shared model/settings changes belong in the earliest contribution that needs them, not duplicate commits in several PRs.

Verification status: the combined implementation passed 348 unit tests, lint and all three debug/instrumentation APK builds without private QA tooling. Fourteen native decoder/export tests passed on each of two physical phones; the decoder/export code is unchanged by the later UI and rotary-count repairs. Two native Settings UI/storage regressions passed on the emulator, with the committed-value case also passing in verified landscape at font scale 1.3. Emulator checks passed portrait/landscape upward-first scrubbing, cancellation/background recovery, moving-picture holds with clean settled export, location-notice/error ordering, pinch with the dial off and on, double-tap reset and the Reset chip on a probe-free APK. These are integrated-candidate results, not separate CI results for four extracted PR branches. The N20 disconnect still blocks the post-fix physical OS vibration count, real-hardware gesture acceptance and removal of its private test APK. No final gate approval is claimed here.

## 1. Open embedded videos from Motion Photos

### PR title

Open JPEG, HEIC and AVIF Motion Photos as video sources

### Summary

People who take Motion Photos can open the embedded video directly in FrameEcho instead of extracting it with a separate tool. The player retains the original file identity and available still metadata while using an owned temporary video for playback and frame extraction.

### Changes

- Resolve ordinary videos and supported JPEG, HEIC/HEIF and AVIF Motion Photo containers through one source-loading path.
- Parse XMP/container ranges with bounded reads and validate the embedded video before exposing it to the player.
- Retain the source name, still range, capture time and known cover presentation time for later metadata and original-still export.
- Release extracted temporary videos when the source is replaced or closed, and show readable errors for inaccessible files, malformed Motion Photos and ordinary stills without video.

### Verification

Parser and source-access tests cover malformed ranges, short reads, permission failures, metadata and temporary-file lifetime. Native tests reopen an exported Motion Photo and identify its original encoded still. Integrated UI checks open Motion Photos from the picker/gallery and through incoming intents, and show a persistent rejection message for a plain JPEG.

### Scope and limits

A standalone Apple Live Photo still is not a self-contained video source; its separate video file remains necessary. A missing cover timestamp stays unknown rather than being replaced with a guessed midpoint. Files remain subject to Android URI grants and device decoder support. The gallery and additional export choices are described in the dependent PRs below.

This PR is AI generated, but under direct supervision and on request of @Nowaker.

---

## 2. Browse, reopen and share media into FrameEcho

### PR title

Add an in-app gallery, recent files and Android open/share handling

### Summary

Users can find a video or Motion Photo inside FrameEcho, return to a source they worked on earlier, or open media sent by another app. A saved startup preference lets frequent users open the gallery directly while retaining the system picker as an alternative.

### Changes

- List accessible videos and detected Motion Photos in a shared thumbnail grid, excluding ordinary still images from the Motion Photo results.
- Handle denied and selected-only media access with an explanation, retry/select-more actions and a system-picker alternative.
- Remember successfully opened sources in most-recent order, with original names, saved-frame counts and a persistent saved-from-only filter.
- Handle `ACTION_VIEW`, `ACTION_SEND`, `ACTION_SEND_MULTIPLE` and new intents delivered to an existing activity. Multiple shared sources remain selectable in an incoming-media list.
- Preserve only the access Android actually grants; expired or deleted sources become visibly unavailable instead of being treated as permanent readable paths.

### Dependencies and verification

This contribution uses the Motion Photo resolver from PR 1. Unit tests cover gallery filtering, intent parsing/queueing, history ordering and preference persistence. Integrated runtime checks cover gallery permission denial/retry, Motion Photo filtering, recent-file order and filtering across restart, cold incoming intents overriding the startup preference, and real multi-URI sharing followed by selection.

### Scope and limits

The gallery does not bypass scoped storage or another app's URI-grant lifetime. Android 14 selected-photo access is a platform-specific path; Android 12 physical-phone results do not establish that path. Playback/export behavior stays in the existing player rather than introducing another media engine.

This PR is AI generated, but under direct supervision and on request of @Nowaker.

---

## 3. Step precisely with responsive previews and a rotary dial

### PR title

Add configurable frame stepping and a persistent precision dial

### Summary

Users can move through a clip one frame at a time, hold a button to accelerate, or turn an endless rotary dial without repeatedly dragging a short seek bar. The displayed selection and captured frame use the same actual-frame state, including at the beginning and end of the video.

### Changes

- Add an editable hold delay and acceleration schedule, with add/remove/reset actions, validation and unsaved-draft handling.
- Share frame-selection and settlement between buttons and the dial, floor fractional millisecond seek boundaries, and retain preview feedback until the selected frame reaches the player surface.
- Decode exact frames with complete GOP references and actual presentation timestamps. Use persistent, bounded SDR preview decoding so holding a step button continues to show changing pictures instead of freezing behind the moving target.
- Keep persistent fine mode beside the slider while retaining the upward fine-scrub gesture. The dial clamps to real first/last frames, supports repeated clockwise/counterclockwise turns, and resets its mode on a new source.
- Keep the thumb visible while the guide fades during rotation and returns dimmer after release. Expose frames per turn, guide opacities, return delay and theme/custom color in Settings, with explicit Save and protection for unsaved edits.
- Use public context-click feedback for frame steps and full dial revolutions. Give landscape video its own height while keeping the controls reachable by scrolling.
- Count completed turns from absolute integer angle phases so closed paths at native touch-coordinate precision do not lose the last full-turn event. Keep frame movement independent from the feedback count.

### Dependencies and verification

This contribution follows the source lifetime from PR 1 and settings/navigation support from PR 2. Pure tests cover schedules, bounds, fractional seeks, rotary accumulation with closed Float-coordinate paths, guide state, preference drafts, actual-frame settlement and event-time double-tap recognition. Native tests compare decoded open-GOP frames and rotated previews with independent image references, and exercise reader reuse and distinct output profiles. Native Settings tests drive the real screen against an isolated DataStore, covering a paused valid prefix followed by invalid input and a valid Save surviving store reopen. Integrated device checks cover long holds, end boundaries, export continuity, mode reset and switching the toggle off with another finger still on the dial. Landscape upward-first scrubbing, pinch/dial ownership, double-tap and explicit Reset have captured failing or discriminating controls followed by passing probe-free runs.

### Acceptance still open

Portable gesture and Settings acceptance passed, including landscape/font 1.3 keyboard use and Save. The old physical run recorded three ticks for four attempted turns; an independent closed-path numerical regression reproduced that software count defect and passes its integer-phase repair. Post-fix physical OS vibration and hardware gesture checks still require the reconnected N20. Unit tests and emulator output do not establish physical haptic acceptance, and no felt vibration is claimed.

### Compatibility

The Media3 dependency is 1.10.0, the lowest stable release containing the required frame-extraction shader/error fixes; Android API 26 and Kotlin 2.1.10 remain supported. HDR and unsupported buffer-preview formats retain the exact graphics path rather than an approximate SDR conversion.

This PR is AI generated, but under direct supervision and on request of @Nowaker.

---

## 4. Preserve export format, metadata and naming fidelity

### PR title

Add HEIC and original-still export with source-backed metadata and filenames

### Summary

Users can choose a suitable still-image format, keep an eligible Motion Photo's original encoded still without recompression, and retain available source capture information. The export sheet shows the filename that the next export will actually use.

### Changes

- Offer JPEG and device-supported HEIC encoding, with compatible quality, resolution, Motion Photo and original-still controls.
- Copy an eligible JPEG/HEIC primary still byte-for-byte at a known cover frame, preserving its encoded pixels and existing metadata. Do not append the video to that still-only copy.
- Carry available source capture dates, fractional seconds, timezone offsets and supported descriptive EXIF through import and export. Ask for access to unredacted location metadata in context and explain a refusal.
- Use real source name, export timestamp, presentation-time position and derived frame index in filename presets/templates. Freeze the naming timestamp so preview and first saved name agree.
- Write through granted MediaStore/Storage Access Framework destinations, publish capture dates where supported, and attempt historical modification-time restoration through the granted descriptor.

### Dependencies and verification

This contribution uses the source metadata/still ranges from PR 1 and the exact-frame capture foundation from PR 3. Tests cover naming/sanitization, precise dates, original-still boundaries and provider errors. Native tests perform real HEIC encoding/decoding, EXIF and `DATE_TAKEN` round trips, Motion Photo reopening/byte-exact still comparisons, and nanosecond timestamps on owned descriptors. Integrated UI exports at Frames 59 and 239 produced clean JPEGs whose first saved names exactly matched their full previews, including the timestamp.

### Limits

HEIC encoding depends on the device. AVIF original-still export is not offered. A byte-exact original copy keeps the original XMP even when that XMP refers to motion video that is not appended. Missing capture dates and unknown cover anchors stay unknown. EXIF capture time and MediaStore `DATE_TAKEN` are not filesystem timestamps: Android providers can reject historical modification time with `EPERM`, and filesystem creation/change time cannot be restored. The implementation reports those capability limits rather than bypassing access or inventing timestamps.

This PR is AI generated, but under direct supervision and on request of @Nowaker.
