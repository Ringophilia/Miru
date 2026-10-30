# P6 candidate-engine capability probe

This change supplies independent Android device **probe source**, not P6 implementation
or acceptance. Production code, UI, version, cache policy, and release dependencies
are unchanged. No device result is claimed by the source or a successful build.

Base: `codex/seek-preview`, `20bd901b09f52edeab968eb28eb68ad033abbe05`.
Worktree: `C:\Users\tocml\.codex\worktrees\seek-preview\Miru`.

## Requirement and candidate

MIRU-SPEC §8.4 retains its full original plan: direct/local extraction via a second
lightweight MediaMetadataRetriever; HLS corresponding-segment decoding via one pooled
ExoPlayer and ImageReader; ten-second buckets, at most sixty memory-LRU bitmaps, and
time-only fallback without an empty thumbnail frame. This probe neither implements
that plan nor silently narrows it to local media.

The alternative under examination is `androidx.media3:media3-inspector-frame:1.11.1`,
added **only** to `androidTestImplementation`, with the matching test OkHttp module;
HLS/DASH are provided by the tested application's existing runtime dependencies.
Fixed official sources (not the locally cached older inspector 1.9.2) establish API
signatures, but do not establish the behaviors that require a real decoder/network:

- [FrameExtractor, tag 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/inspector_frame/src/main/java/androidx/media3/inspector/frame/FrameExtractor.java)
- [FrameExtractorInternal, tag 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/inspector_frame/src/main/java/androidx/media3/inspector/frame/FrameExtractorInternal.java)
- [Matching Guava ExecutionSequencer cancellation contract](https://github.com/google/guava/blob/v33.3.1/guava/src/com/google/common/util/concurrent/ExecutionSequencer.java#L45)

The API uses a shared video-only ExoPlayer and GL readback. Calls are made on the main
application thread; blocking future waits run on the instrumentation thread. Frames
are borrowed and may be reused internally, so the test never recycles them.

Crucially, an in-progress sequenced extraction does not acquire cancellation semantics
merely because its public future is cancelled. `close()` queues reference release
behind extraction. Coroutine timeout, cancelled future, and `close()` returning are
all insufficient evidence of a stopped decoder or released socket. The two lifecycle
tests enforce real peer closure before timeout/fixture cleanup and may expose a
candidate-engine failure. A red capability gate must be reported as such, not weakened
or labelled P6 acceptance. Any transport-abort workaround needs a separate production
design and its own tests.

## Eight independent probes

`app.miru.browser.capability.SeekPreviewFrameExtractorCapabilityTest` contains:

- HTTP MP4 and content URI decode at 9s, 1s, 5s, checking returned timestamp and actual RGB.
- HLS TS, fMP4, AES-128, and DASH checks with the same temporal image oracle, plus real
  manifest/init/segment/key HTTP observations and synthetic-header pass/fail checks.
- A real response sends headers plus 32 bytes, then stalls indefinitely. After public
  future cancellation, require peer EOF/RST within six seconds and a successful next
  request on the same extractor.
- With that read still pending, cancel and close; require EOF/RST within five seconds
  and successful decoding from a fresh extractor.

The client transfer listener first proves that the partial media bytes were actually
consumed. The client read timeout is thirty seconds. The server stays open while observing EOF/
RST and does not treat its own cleanup as success. Only after the observation are the
probe's OkHttp calls explicitly cancelled for cleanup. This is separate from evidence
that FrameExtractor itself releases its resources. Tests log only time, RGB, paths,
counts, and booleans, never request-header values.

The cancellation boundary first asserts that the public future is pending and that
the peer has not already closed. Client body-read, action, and server-observed EOF/RST
have monotonic `SystemClock.elapsedRealtime` timestamps. Public cancellation must
return true. Peer closure must occur after the action, within the original five/six-
second deadline, and less than thirty seconds after the actual body read. Main-thread
dispatch/close time counts toward those original deadlines; future waits use only
their remaining time. A late await returning true after a source timeout cannot pass.

The content provider exposes one public, synthetic, read-only file in the test APK.
It does not expose target-app files or demonstrate private-mode no-disk behavior.
The media generator and adjacent fixture README/manifest document source, FFmpeg
version, full commands, hashes, and expected decoded values.

## Build and isolated execution

Do not include this package in the established routine regression suite. Use
`-e notPackage app.miru.browser.capability` for an otherwise broad runner invocation.
The class also skips by assumption unless explicitly enabled; that guard is not an
acceptance result. An intentional run needs both the exact class and opt-in:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME = 'G:\Projects\Ringo-oneghg-ad-free\tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
# Install only after device owner/root grants the device slot and verifies both APK hashes.
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s emulator-5554 shell am instrument -w -r `
    -e class app.miru.browser.capability.SeekPreviewFrameExtractorCapabilityTest `
    -e miruSeekPreviewCapability true app.miru.browser.test/androidx.test.runner.AndroidJUnitRunner
```

Build logs, pre-build source freeze, exact executed command, and immutable copied main/
test APK hashes belong under `artifacts/m3-thumbnails/`. A checkout can change JS/license
line endings: a main APK produced here must have its own SHA-256 and must not be called
the same APK as another worktree's 0.5.10 binary.

The first build exposed a consistent-resolution conflict: the application's existing
ErrorProne annotations 2.15.0 versus Guava's 2.28.0 transitive annotations. Exclude that
compile-time annotation artifact only on the two test dependencies; main dependencies
remain unchanged. A second build established the need for explicit test OkHttp compile
visibility (HLS/DASH require no source imports). These build failures are retained in
the evidence directory and are not device capability results.

## Snapshot03 harness failure and resource-owner correction

Build03 succeeded. The installed 5554 main SHA-256 matched the immutable main snapshot
(`987845...6964d1`), so it was not reinstalled. Test snapshot03 (`766ace...293dd1`) was
installed and its device hash verified. A single explicit six-format instrumentation
run reported `numtests=6` but crashed in the first DASH method before any frame passed.
It returned `Process crashed` after about 2.7 seconds. This is not six completed tests,
an engine/decoder/driver verdict, or any P6 acceptance. Both cancellation cases were
paused and were not run against that known-invalid harness.

Read-only aapt2 dumps establish a concrete input error: test effect raw IDs
`0x7f080011` (vertex_shader_transformation_es2) and `0x7f080001`
(fragment_shader_copy_es2) map in the main APK to binary drawable XML
`$m3_avd_hide_password__1.xml` and `$avd_hide_password__1.xml`. Passing targetContext
to a test-only effect therefore loaded unrelated main resources as shader source.
The binary prefix `03 00 08 00 50` matches the garbage-character shader compiler error
and native JNI abort in `GLES20.glGetShaderInfoLog` / `NewStringUTF`.

The test-only correction wraps the real target Context for decoder construction,
overriding resources/assets with the test APK owner and applicationContext with the
wrapper itself. MediaCodecVideoRenderer and PlaybackVideoGraphWrapper both call
getApplicationContext, so a resource override that is lost during normalization is
insufficient. DefaultDataSource still uses the original target context, and no URI,
HTTP, authentication, shader, or decoder result is replaced. Before any GL invocation,
the probe asserts that the effect resource IDs resolve as raw shader text beginning
with `#version`. The unchanged production application remains outside this harness fix.

Snapshot03 source, APKs, exact selector input, runner output, full logcat, aapt2 maps,
and process-terminal evidence remain under `artifacts/m3-thumbnails/`. All new claims
must come from a new frozen/rebuilt test binary; different runs must not be combined
into an invented eight-test pass.

Snapshot04 corrected the resource input and completed one six-format run: five passed;
content URI failed before decoding because the standalone test-provider process could
not load `kotlin.jvm.internal.Intrinsics`. The test APK shares the target's runtime
during instrumentation but its separately launched provider does not. The fixture
provider is therefore converted to framework/Java-only code (as other standalone test
providers in this project already are). Its real exported read-only content URI and
target DefaultDataSource/ContentResolver remain unchanged. Snapshot04 is a separate
five-of-six run, never a substitute for a subsequent complete six-format result.

Snapshot05 completed an independent six-of-six format run in 3.511 seconds after the
Java-provider correction. Neither lifecycle case was run against snapshot03/04/05.
Before lifecycle execution, review found and corrected the weaker await-only release
oracle described above. New lifecycle results must identify the subsequent frozen
binary and their own independent instrumentation invocation.

## Remaining implementation/acceptance gates

All of these remain open even if every probe above passes:

- Select and document a complete engine route, preserving §8.4 direct/local and HLS
  scope; either accept a justified unified-engine deviation or implement the original
  MMR and one-ExoPlayer/ImageReader plan. DASH remains an explicit additional media gate.
- One active extraction plus one latest pending target, ten-second buckets, sixty-entry
  access-order memory LRU, bounded small aspect-correct images and safe bitmap ownership.
- Fast non-monotonic dragging with stale-frame/revision rejection; invalidation on
  bucket, source, authentication refresh, release, controls hidden, PiP, and cancellation.
- Integrate with current PlayerController **mutable** headers; real 401/403 one-time
  refresh and failed/repeated refresh handling. The probe only checks static synthetic
  headers; it does not prove that production authentication integration works.
- Reuse current source/cache policy: HLS manifest upstream and media cached; DASH and
  progressive construction; authenticated partitioning and normalized segment/Range
  keys; cross-origin redirect/segment Cookie and Authorization stripping, with real
  no-leak observations. Native MMR URL input must not bypass that policy.
- Private sessions bypass disk and leave no thumbnail/header/cache/history artifacts;
  actual stopped-source offline extraction must use the upstream-free offline factory.
- Show correct image/time while dragging; time-only fallback on errors without an empty
  frame; release seek once; preserve primary playback position, audio selection, GL/
  super-resolution output, and control auto-hide behavior.
- Recheck runtime regression, lifecycle bounds, decoder/network counts and release APK
  size only after a production integration exists. A test-only dependency says nothing
  about the eventual production size/cost.
