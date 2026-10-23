# Surface integration

This module has no Media3 or Flutter dependency. It runs the original Anime4K v4
CNN and AMD FSR EASU/RCAS shader assets, not a replacement filter.

## Ready-to-bind Android view

`RoutedVideoView(context)` owns one SurfaceView and the switching lifecycle.
Bind `setDecoderSurfaceListener { surface -> currentPlayer?.setVideoSurface(surface) }`
once on creation. When replacing the player, release/disconnect the former
player first, then call `rebindDecoderSurface()` to attach the current route to
the new player. Do not replace the listener on every Compose recomposition:
replacing it deliberately disconnects its previous owner.

Send `SuperResViewConfig` via `configure(...)`; the default mode is OFF and
creates no GL renderer. The view polls real status and thermal state every
300 ms, reduces its session tier when needed, and emits `onProtection` when
the caller should persist a stable device ceiling or show a notification.
`onStatusChanged` supplies a typed `SuperResViewState` for the quality panel.
All callbacks run on main. `setCompare` updates only the retained frame split.
Call `dispose()` from Compose AndroidView.onRelease, never from each update.

The constructor's optional renderer factory is an integration-test seam. A
test can return a real renderer configured with a nonexistent shader, verifying
that an actual shader loading failure returns the same player to direct output.
The default always uses the full real GL renderer.

The view serializes output handoffs: disconnect decoder → release EGL → bind
direct, or disconnect decoder → initialize GL → bind its decoder input.
While release is in flight, no second producer attaches. Surface and route
epochs reject callbacks from destroyed surfaces, disposed views, and old GL
instances. Direct output fits the child SurfaceView; enhanced output fills the
parent viewport and fits inside GL. `imageRect()` always uses the parent view's
physical coordinates, so Compose comparison labels do not change coordinate
systems when switching modes.

## Renderer-only integration

`SuperResRenderer(assets, onInputSurface, onError)` initializes on its own GL
thread. Both callbacks run on main. Caller methods enqueue work and return;
`status()` returns an immutable, volatile snapshot. A failed renderer stays
failed until released. Ignore callbacks belonging to an older player/session.

1. Keep direct video output until `onInputSurface` is ready. Then disconnect
   the decoder from the display Surface and connect it to the supplied input
   Surface. Configure the desired shaders and fit mode.
2. On SurfaceView `surfaceCreated` / `surfaceChanged`, call
   `attachOutput(holder.surface, physicalWidth, physicalHeight)`. Its dimensions
   are the SurfaceView's full buffer viewport, not the fitted picture dimensions.
   The renderer never releases the caller-owned display Surface.
3. On actual decoder size changes, call `setVideoSize(w, h, pixelRatio)`.
   The Media3 caller is responsible for rotation-adjusted dimensions, HDR /
   protected-format checks, and observing adaptive format changes.
4. On Surface destruction call `detachOutput()`. Keep draining its input only
   while the player is otherwise entitled to continue playback.
5. To disable, fail back, or dispose: first disconnect the decoder from the GL
   input Surface, then call `release { ... }`. Its callback runs on main after
   EGL has disconnected from the display Surface. Only then may the decoder
   connect directly to that same display Surface. No codec/source reload is
   necessary. The completion callback can be registered on repeated releases.

`SuperResPolicy.imageRect` and `status.imageRect` use physical top-left pixels.
Contain gives letterbox offsets; cover may give negative offsets; fill uses the
whole viewport. GL clears unused viewport pixels and fits/crops the actual
picture. Comparison split is relative to that picture; use `splitAt(x)` and
`splitX(split)` for the UI line and handle. `setCompare` redraws the retained
frame even when playback is paused, without compiling shaders or resetting
statistics. The caller draws its accessible 2 dp split line/labels/handle.

Call `SuperResPolicy.decide` on preferences/runtime changes. It never mutates
user preferences. The caller applies `SuperResGuard` actions, stores persistent
device ceilings only when requested, and polls stats every 300 ms. Thermal
status uses Android PowerManager numeric values (SEVERE=3, CRITICAL=4), with
the caller using 0 on Android versions before 29. HDR, >1.5x speed, PiP and
protected-content bypasses use direct decoder output.

The renderer tries `GL_EXT_disjoint_timer_query` to measure every completed
frame asynchronously. Unsupported devices use synchronized rendering wall
time, identified by `SuperResTiming.SYNCHRONIZED_RENDER`; do not label that as
hardware GPU timer measurements. Missing/disjoint samples break the consecutive
overload count. An overload event latches until the next configuration generation
so a 300 ms UI poll cannot miss 30 consecutive expensive frames.

The bundled three-second 720P/24fps clip permits network-independent benchmarks.
Compile and run all three Anime4K tiers against a real 1920x1080 output; wait for
at least 60 completed frames with nonzero timing for each separate generation.
FSR has the same two-pass chain for all tier preferences. Emulator correctness
and real-device performance acceptance are separate checks.
