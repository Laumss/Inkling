# Pen Lasso & EMR Pen Disable Patterns

---

## Pattern 14: Pen Lasso — Convert a Drawn Stroke into a Rectangular Lasso

**Use case**: User presses a "lasso mode" button, draws a freehand stroke to define a region, and the plugin converts that stroke into a native rectangular lasso selection.

### How it works

1. Plugin arms a one-shot `event_pen_up` listener via `PluginManager.registerEventListener('event_pen_up', 1, ...)`.
2. On next pen-up, the SDK delivers the new stroke element.
3. Read the bounding box from `stroke.recognizeResult` (pixel coordinates in the render buffer).
4. Call `PluginCommAPI.lassoElements(rect)` immediately — no page reload needed.
5. Call `PluginCommAPI.setLassoBoxState(0)` to display the lasso selection box.

### Coordinate system

`recognizeResult` fields (`up_left_point_x/y`, `down_right_point_x/y`) are **pixel coordinates in the render buffer** (1920×2560 for A5X2 in portrait). For A5X2 the logical page size returned by `getPageSize` is also 1920×2560, so scale = 1. Always verify via `getPageSize` in case device/orientation differs.

```ts
const psRes = await PluginFileAPI.getPageSize(filePath, page);
const { width: lw, height: lh } = psRes.result;
const renderW = lw <= lh ? 1920 : 2560;
const renderH = lw <= lh ? 2560 : 1920;
const scaleX = lw / renderW;
const scaleY = lh / renderH;

const rect = {
  left:   Math.max(0, Math.floor(x0 * scaleX) - PADDING),
  top:    Math.max(0, Math.floor(y0 * scaleY) - PADDING),
  right:  Math.ceil(x1 * scaleX) + PADDING,
  bottom: Math.ceil(y1 * scaleY) + PADDING,
};
```

A padding of ~100px is recommended: `lassoElements` uses `findTrailsContourInBox` which requires strokes to be **fully inside** the rect — any contour point outside causes the stroke to be missed. Padding compensates for edge strokes.

### Critical: do NOT call deleteElements before lassoElements

The intuitive flow is: delete the lasso stroke → reload → lasso. **This is wrong.**

`deleteElements` triggers a full page reload in the note app (`clearPageStatus` → `loadNoteCurrentPageInfo` → `loadNoteSinglePage` ~300ms). During this reload the note app's binder thread pool is saturated — any subsequent IPC call (`lassoElements`, `reloadFile`, etc.) gets queued and may not execute for **5–6 seconds**. The lasso call effectively never arrives.

**Correct flow**: call `lassoElements` immediately after `deleteElements` returns, without waiting. The lasso stroke remains visible briefly but is covered by the native lasso selection UI — users don't notice.

If removing the stroke after lasso is desired, it can be done after `lassoElements` succeeds.

### Do NOT use EMR coordinate path as fallback

`PluginClientCommImpl.lassoElements` performs its own internal Android→EMR conversion. If you pass EMR coordinates (e.g. from `stroke.stroke.points`) thinking you're providing pixel coords, the result is a rect with negative or out-of-range EMR values (e.g. `top = -845`), which causes `lassoElements` to return `false`.

Only use `recognizeResult` (pixel coords). If `recognizeResult` is missing, abort — do not fall back to reading raw EMR points.

### Why reloadFile hangs

`PluginCommAPI.reloadFile()` sends a `reLoadNote` IPC request. If called while note app is mid-reload (triggered by `deleteElements`), the callback may never fire — the promise hangs indefinitely. Never `await reloadFile()` in the lasso flow.

### Floating window disappears when opening layer panel

Opening the Note app's "more" panel (layer management, etc.) causes `closeAllForSettings` to fire in the plugin overlay, clearing all overlay windows. This is a window manager layer conflict — the plugin overlay is destroyed when the native panel takes focus. No workaround from the plugin side; it's system behavior.

---

## Pattern 15: EMR Pen Disable — Architecture, Programmatic Release, and Debugging

### How EMR pen disable works internally

The pen disable system has three layers:

1. **drawpath service** (`com.ratta.drawpath`) — native `librecgnition.so` maintains a `disableAreaList`. Pen input within these rects is silently dropped at the hardware level. **EMR (digitizer pen) is gated; finger touch is unaffected.**
2. **HandWriteClient** (`com.ratta.supernote.eventlibrary.HandWriteClient`) — Java singleton in the note process that serializes `List<Rect>` into a Parcel and transacts with drawpath via Binder service `service.myservice` (interface token `android.demo.IMyService`, payload prefix `superNoteNote`). Two operations: `sendDisableAreaInfo(List<Rect>)` (replaces full list) and `sendFullScreenDisableArea(Rect)` (full-screen shortcut).
3. **EinkManager** (`android.os.EinkManager`) — system service accessible via `context.getSystemService("eink")`. Only provides `enableFullUiAuto(boolean)` for full-screen toggle.

### Who can call what

| Caller | Path | Access |
|--------|------|--------|
| Note app (`com.ratta.supernote.note`) | `HandWriteClient.sendDisableAreaInfo(List<Rect>)` | ✅ Custom SELinux domain allows `find` on `service.myservice` |
| Pluginhost (`com.ratta.supernote.pluginhost`) | Same as above via reflection | ❌ SELinux `system_app` domain denied `{ find }` for `service.myservice` |
| Pluginhost | `EinkManager.enableFullUiAuto(boolean)` | ✅ Accessible, full-screen only |
| Pluginhost | `NativePluginManager.setFullAuto(boolean)` | ✅ Drives drawAPP's `fullAuto` flag — independent code path from `sendFullScreenDisableArea`; cannot reverse a state:1-driven full-screen disable |
| Pluginhost | PluginClient IPC → `notifyPluginState(int)` (driven by `PluginApp.showPluginView(int)`) | ✅ Note hardcodes: state=1 → full-screen Rect; state=0 → recompute rects from layout |

### Note app's disable rect flow (state-driven)

```
PluginApp.showPluginView(showType)
  → PluginContainerService.onVisibilityChange(visibility)
  → notifyClientPluginState → params=[showType]
  → NoteInsidePagesActivity.onPluginState(state)
      if (state == 1):
          HandWriteClient.sendFullScreenDisableArea(Rect(0,0,W,H))   ← full-screen disable
      else if (state == 0):
          disableAreaChanged → getDisableList:
            - noteView.getDisableRectList()        (toolbar: 0,0,1920,116)
            - sideBarView.getDisableRect()         (sidebar / plugin list / more panel)
          HandWriteClient.sendDisableAreaInfo(rectList)               ← shrink back
```

**Key invariant**: `sendFullScreenDisableArea` is only revoked by an explicit `state:0` notification triggering the rect-recompute path. `setFullAuto(false)` does NOT revoke it (it writes a different drawAPP field). `closePluginView()` does NOT revoke it (the SDK implementation skips `notifyClientPluginState` on the stop transition — see Gotcha below).

### Selective (rect-list) EMR disable from a plugin: NOT POSSIBLE

Three paths were attempted and all failed:

1. **Direct reflection** (`Class.forName` + `createPackageContext`): Class loads successfully but `getBinder()` fails with SELinux denial — pluginhost (different pid from note) cannot look up `service.myservice` from ServiceManager.
2. **EinkManager exploration**: methods limited to display control (regal, gamma, refresh, split screen). No area-based pen disable.
3. **PluginClient IPC**: only `notifyPluginState(0|1)` is wired. Note hardcodes full-screen rect when state=1; no parameter for custom rects.

### Full-screen EMR disable IS possible — and IS programmatically releasable

The **only viable mechanism** on A5X2 (firmware 2025) is the PluginApp state pair:

> **`EinkManager.enableFullUiAuto(boolean)` does NOT work for pen disable on this firmware** — despite the name, it only controls e-ink regal/refresh mode, not the digitizer pipeline. Empirically verified failed. Don't waste a round trip on it.

#### Mechanism — `PluginApp.showPluginView(1)` ↔ `PluginApp.showPluginView(0)` (the state pair)

How it works: triggering `state:1` makes the note app run `sendFullScreenDisableArea(0,0,W,H)` (full-screen pen disable). Triggering `state:0` makes it run `disableAreaChanged → sendDisableAreaInfo(...)` which recomputes from layout — shrinking back to just the toolbar rect. The trick is releasing it:

- **Disable**: call `NativePluginManager.showPluginView()` (no-arg). PluginApp internally defaults to `showType=1`, fires `notifyPluginState(1)`, note runs `sendFullScreenDisableArea`.
- **Release**: NativePluginManager exposes only the no-arg variant — it cannot pass `showType=0`. **Reach into the `pluginApp` field by reflection** and call the int-arg overload directly:

```kotlin
@ReactMethod
fun releasePenLock() {
    handler.post {
        try {
            val pm = reactApplicationContext.catalystInstance
                .getNativeModule("NativePluginManager") ?: return@post
            val paField = pm::class.java.declaredFields
                .firstOrNull { it.name == "pluginApp" } ?: return@post
            paField.isAccessible = true
            val pa = paField.get(pm) ?: return@post
            val showM = pa::class.java.methods.firstOrNull {
                it.name == "showPluginView" && it.parameterCount == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                 it.parameterTypes[0] == java.lang.Integer::class.java)
            } ?: return@post
            showM.invoke(pa, 0)   // fires notifyPluginState(0) → note recomputes rects
        } catch (e: Exception) { Log.e(TAG, "releasePenLock: ${e.message}", e) }
    }
}
```

JS-side release flow (give the cross-process state:0 chain ~200ms before tearing down):

```ts
FloatingToolbarBridge.releasePenLock();   // PluginApp.showPluginView(0) — triggers state:0 → release
FloatingToolbarBridge.disablePenBlock();  // setFullAuto(false) — defensive double-coverage
setTimeout(() => PluginManager.closePluginView(), 200);  // collapse plugin view
```

**Why a TYPE_APPLICATION_OVERLAY toolbar stays usable during state:1**: EMR is gated, finger touch is not. A `WindowManager` overlay above the plugin view continues to receive `MotionEvent`s normally — so the user can tap the same toolbar button to release. **Do NOT `hide()` the toolbar when entering pen-lock**, or the user loses the release affordance.

### Debugging methodology — log-chain comparison

The release path was solved by comparing the **full** logcat output of disable vs. release attempts. The methodology:

1. **Identify the success chain.** When disable works, capture every log line from the user gesture down to the hardware effect. For pen disable on A5X2 (firmware 2025) that's:

   ```
   FloatingToolbar  openPenLockView: calling showPluginView(1)
   PluginApp        showPluginView showType:1
   PluginContainerService  onVisibilityChange visibility:0
   PluginContainerService  notifyClientPluginState
   PluginClientService     requestClient ... method='notifyPluginState', params=[1]
   NoteInsidePagesActivity onPluginState state:1
   HandWriteClient  sendFullScreenDisableArea Rect(0,0-1920,2560)   ← effect
   ```

2. **Diff against the failing chain.** A failing release would print:

   ```
   FloatingToolbar  callSetFullAuto(false) called
   PluginApp        PluginApp closePluginView state:stop
   ```

   — and **stop**. No `onVisibilityChange`, no `notifyClientPluginState`, no `state:0`, no `disableAreaChanged`, no `sendDisableAreaInfo`. The missing links localize the bug: the SDK's `closePluginView` skips the visibility-change notification on stop.

3. **Pick the broken link to bypass.** Here, `notifyClientPluginState` only fires from `PluginContainerService.onVisibilityChange`, which is only called from `PluginApp.showPluginView(int)`. Reaching that method directly = reflection into the `pluginApp` field of NativePluginManager.

4. **Verify by log.** After fix, the release chain should mirror the disable chain — same five intermediate tags, just with `params=[0]` and `sendDisableAreaInfo` (not `sendFullScreenDisableArea`).

This pattern generalizes: **whenever an SDK wrapper "should" reverse a state but doesn't, dump the disable log chain, dump the release log chain, find the missing link, then reflect into the underlying object that owns it.** Most Supernote SDK wrappers strip parameters or skip notifications on the reverse path; the underlying `PluginApp` / `PluginContainerService` / `HandWriteClient` always have the full-arity methods.

### EinkManager full method list (A5X2, firmware 2025)

```
disableRegal(boolean, int)        — e-ink refresh mode control
enableAutoRegal(boolean, int)     — auto regal mode
enableFullUiAuto(boolean)         — name suggests pen control but actually e-ink regal/refresh only; does NOT gate digitizer
enableFullUiAuto(boolean, boolean) — variant with extra param (same scope)
enterSplitScreenMode(...)         — split screen
freezeScreen(boolean, int)        — freeze display
getMode() → String                — current display mode
screenRefresh(boolean, int)       — force screen refresh
sendHwcCmd(int, int[])            — raw hardware composer command
setDitherType(int)                — dither settings
setMode(String)                   — set display mode
setScreenRotation(int)            — rotation
setStylusGuestureEnabled(boolean) — stylus gesture toggle
setWindowTitle(String, int)       — window title for display
```

---

## Pattern 16: Scoped Pen Disable Around a Pen Operation

When you build a feature where the user performs a pen gesture inside your plugin's own UI (e.g., a pen lasso on a TYPE_APPLICATION_OVERLAY), you almost always want EMR pen disable engaged for the duration of that gesture so the strokes never reach the underlying note's `.note` file. This pattern wraps Pattern 15's release recipe into a `engage / release` pair scoped to the operation.

### Why not just rely on the overlay to swallow events

Two pipelines deliver pen input on Supernote:

1. **Standard input pipeline** (`dev/input/pen` → InputDispatcher → `View.dispatchTouchEvent` with `SOURCE_STYLUS`) — what your overlay sees.
2. **Hardware direct path** (`dev/input/pen` → drawAPP native, bypassing the view tree) — writes strokes straight into the active `.note` file.

A `WindowManager` overlay only gates pipeline 1. Pipeline 2 always fires regardless of overlay z-order. Without a scoped pen disable, every stroke the user draws in your overlay also lands as a stroke in their note. The historical workaround (PenGuard snapshot + post-hoc `deleteElements`) leaves a brief visible flicker. Engaging full-screen EMR disable for the gesture window is the clean fix.

### Recipe

```ts
// ── helpers (scoped to the feature, e.g. PenLasso.ts) ──

const PEN_LOCK_ENGAGE_MS = 150;   // give state:1 → sendFullScreenDisableArea time to land
const PEN_LOCK_RELEASE_MS = 200;  // give state:0 → sendDisableAreaInfo time to land

async function engagePenLock(): Promise<void> {
  FloatingToolbarBridge.setPendingScreen('penLock');  // React renders transparent placeholder if plugin view boots
  FloatingToolbarBridge.openPenLockView();            // showPluginView(no-arg) → PluginApp defaults to showType=1
  await new Promise<void>(r => setTimeout(r, PEN_LOCK_ENGAGE_MS));
}

function releasePenLock(): void {
  FloatingToolbarBridge.releasePenLock();             // reflection: PluginApp.showPluginView(0)
  FloatingToolbarBridge.disablePenBlock();            // setFullAuto(false) — defensive
  setTimeout(() => {
    try { PluginManager.closePluginView(); } catch (_) {}
  }, PEN_LOCK_RELEASE_MS);
}

// ── usage in arm() ──

await engagePenLock();
await PenGuard.begin();                  // belt-and-braces: keep the snapshot for race-condition fallback
showOverlay();                           // overlay receives stylus motion via standard input pipeline (unaffected by EMR disable)

// ── usage in completion / cancel handlers ──

await PenGuard.end();                    // cleans up any leaked strokes (rare, but cheap insurance)
releasePenLock();
restoreToolbar();
```

### Why each step matters

| Step | Why |
|------|-----|
| `setPendingScreen('penLock')` | If plugin view is currently closed, `openPenLockView` will boot it and React will mount. Without this hint, React renders the default `'main'` screen and flashes the main panel briefly. |
| `openPenLockView()` (no-arg) | `NativePluginManager.showPluginView` is no-arg; `PluginApp` defaults to `showType=1`, fires `notifyPluginState(1)`, note runs `sendFullScreenDisableArea`. |
| `await 150ms` | Cross-process state:1 chain (`PluginApp → PluginContainerService → PluginClientService → NoteInsidePagesActivity → HandWriteClient → drawAPP`) takes ~100ms. If the user's first stroke lands before drawAPP has the disable rect, that stroke leaks. |
| `PenGuard.begin()` | EMR disable is fired-and-forget — there's no ack. Snapshot fallback covers any leaked stroke from the race window or unknown firmware behavior. |
| `releasePenLock()` | The reflection-only path that fires `notifyPluginState(0)`. SDK `closePluginView` skips this notification. |
| `disablePenBlock()` | `setFullAuto(false)` covers the (rare) case where the state:0 chain fires but doesn't reach `disableAreaChanged` —  e.g. NoteInsidePagesActivity is paused. |
| `await 200ms` then `closePluginView` | Lets the state:0 → `disableAreaChanged` → `sendDisableAreaInfo` chain finish before tearing down. Tearing down too early means PluginApp transitions to `stop` mid-flight, and the note may end up in an inconsistent state next time the plugin opens. |
| `PenGuard.end()` | Final cleanup of any leaked strokes from the engage-race window. |

### Caveat: `isPenLocked` latch

If your toolbar has a user-facing "pen lock" toggle (with its own `isPenLocked` Kotlin state and ⊘/✏ icon swap), the `engagePenLock`/`releasePenLock` recipe above does NOT touch that latch — it goes through `openPenLockView` / `releasePenLock` which are pure side-effect entrypoints. Visually, the user's manual toggle remains in its previous state. Don't accidentally call `setPenLocked(true)` from a scoped pen operation, or the user will see the toggle "switch by itself" and won't be able to release it via the toolbar after your operation ends.
