---
name: supernote-plugin-dev
description: "Build, debug, and extend Supernote e-ink device plugins using the sn-plugin-lib SDK (React Native + Android). Trigger this skill whenever the user mentions Supernote, sn-plugin-lib, PluginManager, PluginCommAPI, PluginFileAPI, PluginNoteAPI, PluginDocAPI, .snplg files, e-ink plugin development, or wants to create/modify a plugin for Supernote NOTE or DOC apps. Also trigger when the user discusses EMR coordinates, lasso operations on e-ink devices, or any React Native plugin targeting the Supernote PluginHost runtime. Even if the user just says 'plugin for my notebook' or 'extend my note-taking app' in the context of Supernote hardware, use this skill."
---

# Supernote Plugin Development Skill

You are an expert Supernote plugin developer. Supernote plugins extend the NOTE (handwriting notebook) and DOC (document reader) apps on Supernote e-ink devices. Plugins run inside a **PluginHost** process that provides a React Native runtime, and communicate with NOTE/DOC via AIDL + SDK interfaces.

## Before You Start

**Always read the appropriate reference file(s) before writing code:**

| Task | Read first |
|------|-----------|
| New project / environment setup | `references/setup-and-build.md` |
| Any API call or type question | `references/api-quick-ref.md` |
| Common recipes (insert text, lasso ops, coordinate conversion, pending button, etc.) | `references/patterns.md` |
| Type definitions (Element, Stroke, Geometry, TextBox, etc.) | `references/types.md` |
| Floating window overlay, screen adaptation | `references/floating-window.md` |
| i18n, multi-language buttons, string extraction workflow | `references/i18n.md` |
| Pen lasso, EMR pen disable, scoped pen lock | `references/pen-emr.md` |
| SQLite local storage in plugins | `references/sqlite.md` |

For complex tasks, read multiple files. The reference files contain the **authoritative API signatures and constraints** — do not rely on memory alone.

## Architecture (30-second overview)

```
┌─────────────┐     AIDL      ┌─────────────┐    SDK (TurboModule)    ┌──────────┐
│  NOTE / DOC │ ◄──────────► │  PluginHost │ ◄──────────────────────► │  Plugin  │
│  (Host App) │              │ (RN Runtime) │                        │(Your Code)│
└─────────────┘              └─────────────┘                         └──────────┘
```

- **Plugin**: Your React Native code. Entry = `index.js` (init + buttons) + `App.tsx` (UI).
- **PluginHost**: Loads, schedules, and renders plugins. Provides the RN runtime.
- **NOTE/DOC**: Host apps. Show plugin buttons in toolbar / lasso toolbar / text-selection toolbar.

Communication: Plugin → SDK (`sn-plugin-lib`) → TurboModule → Java → C/C++ → NOTE/DOC file operations.

## Plugin Lifecycle

1. **Install**: `.snplg` copied to `MyStyle/`, user installs via Settings → Apps → Plugins
2. **Init**: PluginHost starts RN env → executes `index.js` → `PluginManager.init()` → button registration
3. **Event**: User taps plugin button → AIDL event → PluginHost → plugin listener callback
4. **UI**: If `showType=1`, PluginHost renders `App.tsx` in a full-screen container
5. **API calls**: Plugin calls `PluginCommAPI` / `PluginFileAPI` / `PluginNoteAPI` / `PluginDocAPI`
6. **Close**: `PluginManager.closePluginView()` or user navigates away

## Development Workflow

When the user wants to create a new plugin:

1. **Scaffold**: `npx @react-native-community/cli init <n> --template @supernote-plugin/sn-plugin-template --version 0.79.2`
2. **Init** in `index.js`: `PluginManager.init()` after `AppRegistry.registerComponent(...)`
3. **Register buttons**: `PluginManager.registerButton(type, appTypes, config)` — type 1=toolbar, 2=lasso, 3=text-selection(DOC only)
4. **Write UI** in `App.tsx` using React Native components
5. **Call SDK** APIs as needed: `PluginCommAPI`, `PluginFileAPI`, `PluginNoteAPI`, `PluginDocAPI`
6. **Build**: In project root, run `.\buildPlugin.ps1` (PowerShell) or `./buildPlugin.sh` (bash)
7. **Deploy**: `adb push build\outputs\<n>.snplg /storage/emulated/0/MyStyle/` → install on device
8. **Debug**: `adb logcat -c` → trigger action → wait 10s → `adb logcat -d -s ReactNativeJS:V`

## Critical Constraints (memorize these)

### Coordinate Systems
- **EMR coordinates**: Hardware pen sampling coords, higher precision. Used for stroke points, Element.maxX/maxY.
- **Pixel coordinates**: Screen pixels (left-top origin). Used for Rect params, lasso, geometry insertion, UI layout.
- **Conversion**: `PointUtils.androidPoint2Emr(point, pageSize)` / `emrPoint2Android(…)`. Get pageSize from `PluginFileAPI.getPageSize(path, page)`. See `api-quick-ref.md §6` for supported sizes.
- **Which APIs use which?** Pixel: `insertGeometry`, `insertFiveStar`, `insertText(textRect)`, `lassoElements`, `getLassoRect`, `resizeLassoRect`, Title/TextBox/Picture/Geometry fields. EMR: `Stroke.points`, `FiveStar.points` (stored), `Element.maxX/maxY`.

### Layer Restrictions
- **Main layer (layer=0)**: Supports ALL element types.
- **Custom layers (layer 1-3)**: Only strokes, pictures, text boxes, and geometry. **NO titles, links, or five-stars**.
- **DOC files**: Only have one layer (main). Cannot insert text boxes, titles, or links.

### Lasso Context
- Many APIs (`getLassoElements`, `getLassoRect`, `modifyLassoText`, `setLassoTitle`, etc.) **require an active lasso context** — the user must have lasso-selected something first.
- `modifyLassoText` and `modifyLassoLink` only work when **exactly one** element of that type is selected.
- `setLassoBoxState(2)` = permanently removes the lasso. Use only when the operation is done. `setLassoBoxState(3)` (0.1.43+) = hides all lasso UI but preserves the lasso state internally.

### Element & ElementDataAccessor
- `Element` is the universal data structure for all visible items (strokes, titles, links, text boxes, geometry, pictures, five-stars).
- Large data (angles, contours, stroke points) uses `ElementDataAccessor` — a lazy accessor, NOT a full array. Call `size()`, `get(index)`, `getRange(start, end)` to fetch data on demand.
- Always call `element.recycle()` when done to free native-side memory.
- Always call `PluginCommAPI.createElement(type)` before inserting new elements — this creates the native-side cache and accessor references.

### API Response Pattern
All async APIs return `APIResponse<T>`:
```ts
{ success: boolean; result?: T; error?: { message: string } }
```
Always check `success` before reading `result`.

### PluginConfig.json
- `pluginKey` MUST match the first argument of `AppRegistry.registerComponent(...)`. Mismatch = plugin won't load.
- `pluginID` is auto-generated on first build. Never change it after distribution — it identifies the plugin.

## Build, Deploy & Debug

See `references/setup-and-build.md` for full details. Quick commands:

```powershell
.\buildPlugin.ps1                    # build → build/outputs/<n>.snplg
adb push build\outputs\*.snplg /storage/emulated/0/MyStyle/   # deploy
adb logcat -c; Start-Sleep 10; adb logcat -d -s ReactNativeJS:V  # debug
```

Key log tags: `ReactNativeJS` (console.log), `PluginHost` (lifecycle), `SNPlugin` (SDK native ops).

## Decision Tree: Which API Module?

```
What do you need to do?
│
├─ Manage plugin lifecycle, buttons, events, device info, touch events
│  → PluginManager (references/api-quick-ref.md §1) — includes registerMotionListener (0.1.43+)
│
├─ Work with current page context (lasso, stickers, geometry, reload)
│  → PluginCommAPI (references/api-quick-ref.md §2)
│
├─ Operate on file data (pages, elements, layers, templates, keywords)
│  → PluginFileAPI (references/api-quick-ref.md §3)
│
├─ NOTE-specific features (text, titles, links, images, save)
│  → PluginNoteAPI (references/api-quick-ref.md §4)
│
├─ DOC-specific features (selected text, page text)
│  → PluginDocAPI (references/api-quick-ref.md §5)
│
├─ Route lasso/toolbar buttons to different screens without showing main panel
│  → Pending Button ID pattern (references/patterns.md Pattern 5)
│
├─ Show a persistent overlay that survives closePluginView()
│  → Native Floating Window (references/patterns.md Pattern 6)
│
├─ Disable the EMR pen during a plugin-driven gesture (e.g. pen lasso on overlay)
│  so strokes don't leak into the .note file
│  → Scoped Pen Disable (references/patterns.md Pattern 16) + see Pattern 15 for
│    architecture and the PluginApp.showPluginView reflection release recipe
│
├─ Insert text sequentially across pages (e.g. streamed from phone/AI)
│  → Page-Anchored Sequential Insertion (references/patterns.md Pattern 13)
│
├─ OCR-recognise handwritten strokes / text boxes into a string
│  → PluginCommAPI.recognizeElements(elements, pageSize) (references/api-quick-ref.md §2)
│     1. getLassoElements() to get the Element array
│     2. getCurrentFilePath() + getCurrentPageNum() + getPageSize(path, page) for the full page size
│     3. recognizeElements(elements, pageSize) → APIResponse<string>
│     4. cancelRecognize() to abort a long-running recognition if needed
│
└─ Extract hardcoded strings / add multi-language support (i18n)
   → i18n Extract-Translate-Convert workflow (references/patterns.md Pattern 12)
      Step 1: scan files → .lang intermediate format
      Step 2: .lang → src/i18n/locales/{zh_CN,en_US,zh_TW,ja_JP}.json
      Step 3: rewrite source files with t('key') + useTranslation hook
```

## Common Gotchas

1. **Forgot `PluginManager.init()`**: All subsequent SDK calls will silently fail.
2. **Wrong button type**: type=3 (text-selection) is DOC-only. Registering it for NOTE is harmless but the button won't appear.
3. **Coordinate mismatch**: Inserting a geometry with EMR coords where pixel coords are expected (or vice versa) will place elements off-screen. Always check which coordinate system the API expects. Note: `insertFiveStar` uses **pixel coords** (not EMR).
4. **Not recycling elements**: Fetching elements without calling `recycle()` leaks native memory. Especially critical in loops.
5. **Assuming full arrays**: `element.angles` and `element.contoursSrc` are accessors, not arrays. Don't try to `.map()` or `.length` them — use `size()` and `get()`.
6. **Missing lasso context**: Calling lasso APIs without an active lasso selection causes errors. Always verify the lasso context first.
7. **DOC insertion limits**: Trying to insert text boxes, titles, or links into DOC files will be rejected.
8. **React Native version lock**: Must use RN 0.79.2. Other versions may cause PluginHost incompatibility.
9. **File-level API without saving**: Call `PluginNoteAPI.saveCurrentNote()` before `insertElements`/`modifyElements`/`replaceElements` to persist the in-memory cache first; otherwise data may be inconsistent.
10. **PluginFileAPI param order is inconsistent**: Read-only queries put page first: `getElements(page, filePath)`, `getElementCounts(pageNum, filePath)`, `getElementNumList(pageNum, filePath, type)`. Write operations put filePath first: `insertElements(filePath, page, elements[])`, `modifyElements(filePath, page, …)`, `replaceElements(…)`, `deleteElements(…)`, `getElement(filePath, page, numInPage)`. Always check the signature.
11. **Lasso button always shows main screen**: If `registerButtonListener` is set up inside `App.tsx`, there's a timing gap where the button event fires before the listener is registered. Use the **pending button ID** pattern (Pattern 5): store the pressed ID as a module-level variable in `index.js`, then consume it with `checkPendingButton()` as the first thing in the mount `useEffect`.
12. **Native floating window pitfalls**: Permission, render timing, tap handling, stale bubbles, and foreground detection — see Pattern 6 in `references/patterns.md` for all details.
13. **`registerLangListener` uses `onMsg` not `onLangChange`**: The callback is `onMsg: (msg) => {}` and language code is at `msg.lang`. The lang value uses underscores (`zh_CN`) — convert with `msg.lang.replace('_', '-')` before passing to i18next.
14. **`registerButton` name must be a JSON string for localization**: Passing a plain string means the button always shows that literal text regardless of device language. For multi-language support, serialize an object: `name: JSON.stringify({en: 'Sticker', zh_CN: '贴纸', ...})`.
15. **`onButtonPress` event has a `pressEvent` field**: For lasso toolbar buttons, `event.pressEvent === 3`. Don't rely solely on `id` — check `pressEvent` to confirm the event type before routing.
16. **`NativePluginManager` vs `PluginManager`**: Two different modules. `NativePluginManager.getPluginDirPath()` returns the plugin's private **data directory** (use for databases, sticker files). Cache this value — it's a slow async native call.
17. **Rotation needs three listeners**: Use `NativePluginManager.getOrientation()` for initial value on mount, `DeviceEventEmitter.addListener('plugin_event_rotation', ...)` for rotation events, and `Dimensions.addEventListener('change', ...)` for updated pixel dimensions. All three are needed for correct layout.
18. **`generateStickerThumbnail` takes a Size object**: The third argument is `{width, height}`, not two separate numbers. Call `PluginCommAPI.getStickerSize(path)` first.
19. **`saveStickerByLasso` takes a full file path**: The argument is the destination file path (e.g. `pluginDir + '/sticker/my.sticker'`), not just a name.
20. **`PluginNoteAPI.insertText` always targets the current displayed page**: There is no page parameter — text is inserted into whichever page the user is currently viewing. If your plugin tracks a `targetPage` for sequential insertion, you **must** call `PluginCommAPI.getCurrentPageNum()` before each `insertText` and verify the user is on the expected page. Inserting without this check will silently place text on the wrong page.
21. **`getLastElement()` takes no parameters**: The official signature is `getLastElement() → APIResponse<Element>`. It returns the last element of the **currently displayed page**. Do not pass `(page, filePath)` — those parameters are not part of the API.
22. **Sequential text insertion across pages needs page-wait**: After `insertNotePage()` + `reloadFile()`, do NOT immediately resume inserting. The user must flip to the new page first (since `insertText` targets the displayed page). Use a polling loop (`getCurrentPageNum`) to detect when the user arrives on the target page, then resume. A naïve timeout fallback that blindly resumes will insert text onto the wrong page.
23. **Note file switch detection**: If your plugin does background work (text insertion, etc.), periodically call `getCurrentFilePath()` to verify the user hasn't switched to a different note. The SDK does not emit a "file changed" event — you must poll.
24. **External page count changes**: If the user manually adds or removes pages while your plugin tracks a `targetPage`, page indices shift and your target becomes stale. Periodically call `getNoteTotalPageNum(path)` and compare against your expected count to detect external changes.
25. **`recognizeElements` needs full page size, not lasso rect**: Pass the result of `getPageSize(filePath, pageNum)` as the `size` argument — NOT the lasso bounding rect. Passing the lasso rect causes the firmware to throw `IllegalArgumentException: getRealMaxX, unknown pageSize` and recognition fails entirely.
26. **`recognizeElements` only supports strokes and text boxes**: Other element types (geometry, pictures, five-stars, links) are silently ignored. Filter your element list or check `getLassoElementTypeCounts()` before calling to avoid confusing empty results.
27. **`PluginManager.closePluginView()` does NOT fire `notifyClientPluginState(0)`**: The SDK skips the state-0 notification when transitioning the PluginApp to `stop`. Anything the note app does in response to `onPluginState(state=1)` (most importantly `sendFullScreenDisableArea` for the EMR pen lock) will **not be reversed** by `closePluginView` alone. To release such state, first call `PluginApp.showPluginView(0)` by reflection (see Pattern 15), then `closePluginView` for cleanup. **Note (0.1.43):** `closePluginView` also requires a `Promise` parameter in the native module — calling it via reflection with `null` triggers a non-fatal NPE at `promise.resolve(…)` after the close logic has already executed.
28. **`PluginManager.showPluginView()` (0.1.43) / `NativePluginManager.showPluginView()` — both no-arg only**: Calling either always opens the plugin view and triggers `notifyPluginState(1)`. **SDK change in 0.1.43**: The `PluginAppAPI` abstract class removed the `showPluginView(int showType)` overload — the abstract signature is now `showPluginView()` (no-arg). However, the **device-side PluginHost firmware still has the int-arg method** on the concrete `PluginApp` class (verified on A5X2 firmware 2026-05 with sn-plugin-lib 0.1.43). The reflection trick from Pattern 15 (`pluginApp.showPluginView(0)`) therefore still works at runtime, but should be coded defensively (graceful fallback if the 1-arg method disappears in a future firmware update). Also note: logcat shows `PluginStateTaskQueue` may DISCARD state:0 tasks under certain conditions — the actual pen disable release comes from the `disableAreaChanged` path triggered by UI layout changes, not solely from `notifyPluginState(0)`.
29. **`setFullAuto(false)` does NOT cancel a `state:1`-triggered full-screen pen disable**: They are independent code paths in drawAPP. `setFullAuto` writes drawAPP's `fullAuto` flag, while `state:1` runs `HandWriteClient.sendFullScreenDisableArea` which writes the rect list. Only an explicit `state:0` event (which triggers `disableAreaChanged → sendDisableAreaInfo`) will revoke a `sendFullScreenDisableArea` rect. Use `setFullAuto(false)` only as defensive coverage, not as the primary release.
30. **EMR pen disable does NOT block finger touch**: A `TYPE_APPLICATION_OVERLAY` toolbar above an EMR-disabled plugin view continues to receive touch normally. When designing a pen-lock toggle, **do not hide the toolbar** when entering the locked state — the user needs the same toolbar button to release the lock. The lock is on the digitizer (pen) input pipeline only.
31. **Two pen input pipelines coexist; an overlay only gates one of them**: `dev/input/pen` events fan out to (a) the standard input pipeline → `View.dispatchTouchEvent` with `SOURCE_STYLUS`, and (b) a hardware direct path → drawAPP native → straight into the active `.note` file. A `WindowManager` overlay can swallow (a) but never (b). Any feature where the user draws inside your plugin's own UI (pen lasso, signature pad, etc.) must engage full-screen EMR disable for the duration — see Pattern 16 — or strokes will silently land in the user's note file. PenGuard snapshot-and-cleanup is only adequate as a fallback for the rare race window.
32. **`EinkManager.enableFullUiAuto` is misnamed and does NOT control the digitizer on A5X2 (firmware 2025)**: Despite the suggestive method name, it's e-ink regal/refresh control. Empirically verified: it does not gate pen input. Don't waste a round trip on it for pen disable scenarios — use Pattern 15's `PluginApp.showPluginView` state pair instead.
33. **PluginHost ignores `MainApplication.getPackages()` — only `PluginConfig.json` `reactPackages` matters**: PluginHost loads NativeModules via the `"reactPackages"` array in `PluginConfig.json`, NOT through the standard RN `MainApplication` → `ReactNativeHost` → `getPackages()` path. The build script auto-discovers third-party packages from `node_modules/`, but your **own** ReactPackage (the one registering custom NativeModules) must be explicitly included. If missing, all custom `NativeModules.*` will be `null` at runtime — code compiles, JS executes, but every native call silently fails. When renaming, consolidating, or refactoring Package classes, always verify the fully-qualified class name appears in `build/generated/PluginConfig.json` after build.
34. **`logcat` chatty filter hides PluginHost init logs**: Android's chatty mechanism drops repeated lines. PluginHost startup triggers this heavily, hiding diagnostic `Log.i()` output. Disable with `adb logcat -P ""` before capturing, or filter by PID: `adb logcat --pid=$(adb shell pidof com.ratta.supernote.pluginhost)`.

## When Helping the User

- **For new plugin creation**: Walk through the full workflow (scaffold → init → buttons → UI → build). Generate complete `index.js` and `App.tsx` files.
- **For API questions**: Look up the exact signature in `references/api-quick-ref.md`. Provide working code with proper error handling.
- **For debugging**: Check the gotchas list first. Common issues: missing init, wrong coordinates, missing lasso context, wrong layer.
- **For complex features**: Combine patterns from `references/patterns.md`. Show the full flow including error handling and resource cleanup.
- **For i18n / localization requests** ("extract strings", "multi-language", "i18n"): Follow **Pattern 12** in `references/patterns.md` — scan for hardcoded strings → produce `.lang` intermediate file → convert to i18next JSON locale files → rewrite source files with `t('key')`. Always output all three phases in sequence.
- **Always**: Include TypeScript types, proper `APIResponse` checking, and `recycle()` calls where applicable.
