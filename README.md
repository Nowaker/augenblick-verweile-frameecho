# Augenblick, verweile! (FrameEcho)

An Android frame-capture fork of [Shangjin-Xiao/FrameEcho](https://github.com/Shangjin-Xiao/FrameEcho). Package `net.nowaker.augenblick` installs alongside the upstream app. The player header shows the installed version, build number and build time.

[Canonical source](https://gitlab.com/Nowaker/augenblick-verweile-frameecho) | [GitHub mirror](https://github.com/Nowaker/augenblick-verweile-frameecho) | [Upstream PR drafts](UPSTREAM_PRS.md)

## Use

1. Open a video or supported Motion Photo with the system picker, in-app gallery, recent files, or another app's Open/Share action.
2. Choose a frame with the timeline, previous/next buttons, hold-to-repeat, or the precision dial beside the seek slider. Swipe upward on the slider for temporary fine scrubbing.
3. Open export settings to choose the format, naming and destination, then use Capture & Save.

Settings include the startup screen, hold delay and editable acceleration stages. Use Save schedule or Save dial settings to apply those edits; Reset changes the draft until saved. The dial defaults to 60 frames per turn; its sensitivity, guide opacities, return delay and theme/custom color are configurable. Clockwise moves forward and counterclockwise moves backward. Its thumb stays visible while the guide fades during rotation.

Recent files retain their original names and can be filtered to sources with saved frames. Opening a different source resets the persistent dial mode.

## Media and export

- Import ordinary videos and supported JPEG, HEIC/HEIF and AVIF Motion Photos with embedded video. A standalone Apple Live Photo still needs its separate video file.
- Export decoded frames as JPEG or HEIC, or export a JPEG Motion Photo. HEIC encoding requires a compatible device encoder.
- Copy an eligible JPEG/HEIC Motion Photo's original still byte-for-byte at its known cover frame. This preserves the still's original metadata, including its XMP, without appending the video. AVIF original-still export is not supported.
- Preserve available source capture times, fractional seconds, timezone offsets and supported descriptive EXIF. Access to unredacted location metadata is requested in context.
- Filename templates support `{filename}`, `{timestamp}`, `{position}` and `{frame}`. Position is presentation time in microseconds; frame is a zero-based index derived from the reported frame rate. The export sheet previews the actual filename.
- Save through Android MediaStore or a folder selected with the Storage Access Framework.

Exact capture uses Media3 with actual decoded presentation timestamps. SDR adjacent previews use a bounded MediaCodec decoder/cache; HDR and unsupported preview formats use the exact graphics path. Decoding and encoding remain subject to device codec support.

Missing source capture dates and unknown Motion Photo cover times are not invented. EXIF capture time, MediaStore `DATE_TAKEN` and filesystem modification time are separate: a provider may refuse a historical modification time, and filesystem creation/change time cannot be restored. Shared files can become inaccessible when the sending app's URI grant expires.

## Build

Requires JDK 17 or newer, Android SDK 36, NDK `27.0.12077973` and CMake `3.22.1`. Minimum Android version is 8.0 (API 26); the project uses Kotlin 2.1.10.

```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew :app:assembleDebugAndroidTest :core:media:assembleDebugAndroidTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Native instrumentation uses the owned fixtures in `test-fixtures/media/`. `PrecisionDialSettingsDeviceTest` opens Settings UI and must run only on a device reserved for foreground QA. Release builds require the signing properties documented by `app/build.gradle.kts`; no release key is included.

## License

[MIT](LICENSE). Original FrameEcho by Shangjin-Xiao; fork changes by Nowaker.
