# Common Supernote Plugin Patterns

Reusable code patterns for the most frequent plugin development tasks.

---

## Pattern 1: Minimal Plugin Setup (index.js + App.tsx)

### index.js — Init + register all three button types

```ts
import { AppRegistry, Image } from 'react-native';
import App from './App';
import { name as appName } from './app.json';
import { PluginManager } from 'sn-plugin-lib';

// 1. Register UI component
AppRegistry.registerComponent(appName, () => App);

// 2. Init plugin SDK (MUST be after registerComponent)
PluginManager.init();

// 3. Toolbar button (NOTE + DOC)
PluginManager.registerButton(1, ['NOTE', 'DOC'], {
  id: 100,
  name: 'My Plugin',
  icon: Image.resolveAssetSource(require('./assets/icon/icon.png')).uri,
  showType: 1, // 1=show UI, 0=background only
});

// 4. Lasso toolbar button (optional)
PluginManager.registerButton(2, ['NOTE', 'DOC'], {
  id: 200,
  name: 'Lasso Action',
  icon: Image.resolveAssetSource(require('./assets/icon/icon.png')).uri,
  editDataTypes: [0, 1, 2, 3, 4, 5], // all element types
  showType: 1,
});

// 5. Text-selection toolbar button (DOC only, optional)
PluginManager.registerButton(3, ['DOC'], {
  id: 300,
  name: 'Selection Action',
  icon: Image.resolveAssetSource(require('./assets/icon/icon.png')).uri,
  showType: 1,
});
```

### App.tsx — Key pattern: button listener in useEffect

```tsx
useEffect(() => {
  const sub = PluginManager.registerButtonListener({
    onButtonPress: (event) => {
      switch (event.id) {
        case 100: /* toolbar action */ break;
        case 200: /* lasso action */  break;
        case 300: /* selection action */ break;
      }
    },
  });
  return () => sub.remove();
}, []);
```

Standard RN component structure — `View`, `StyleSheet`, `export default`. Background color `#fff` for e-ink contrast.

---

## Pattern 2: Coordinate Conversion Helper

```ts
import { PluginFileAPI, PointUtils } from 'sn-plugin-lib';
import type { Point, Size } from 'sn-plugin-lib';

/** Cache page size to avoid repeated API calls. */
let cachedPageSize: Size | null = null;

async function getPageSize(filePath: string, pageNum: number): Promise<Size> {
  if (cachedPageSize) return cachedPageSize;
  const res = await PluginFileAPI.getPageSize(filePath, pageNum);
  if (!res?.success || !res.result) {
    throw new Error(res?.error?.message ?? 'Failed to get page size');
  }
  cachedPageSize = res.result;
  return cachedPageSize;
}

export async function pixelToEmr(filePath: string, page: number, pixel: Point): Promise<Point> {
  const size = await getPageSize(filePath, page);
  return PointUtils.androidPoint2Emr(pixel, size);
}

export async function emrToPixel(filePath: string, page: number, emr: Point): Promise<Point> {
  const size = await getPageSize(filePath, page);
  return PointUtils.emrPoint2Android(emr, size);
}
```

---

## Pattern 3: Insert a Text Box

```ts
import { PluginNoteAPI } from 'sn-plugin-lib';

export async function insertTextBox(
  text: string,
  rect: { left: number; top: number; right: number; bottom: number },
  options?: {
    fontSize?: number;
    bold?: boolean;
    italic?: boolean;
    align?: 'left' | 'center' | 'right';
    editable?: boolean;
    border?: boolean;
  }
) {
  const alignMap = { left: 0, center: 1, right: 2 };
  const res = await PluginNoteAPI.insertText({
    textContentFull: text,
    textRect: rect,
    fontSize: options?.fontSize ?? 32,
    textAlign: alignMap[options?.align ?? 'left'],
    textBold: options?.bold ? 1 : 0,
    textItalics: options?.italic ? 1 : 0,
    textFrameWidthType: 0,
    textFrameStyle: options?.border ? 3 : 0,
    textEditable: options?.editable === false ? 1 : 0,
  });
  if (!res.success) throw new Error(res.error?.message ?? 'insertText failed');
  return res.result;
}
```

---

## Pattern 4: Insert a Geometry (Circle)

```ts
import { PluginCommAPI } from 'sn-plugin-lib';

/**
 * Insert a circle at given pixel coordinates.
 * Note: geometry coordinates are in PIXEL coordinate system.
 */
export async function insertCircle(
  centerX: number,
  centerY: number,
  radius: number,
  options?: { penColor?: number; penType?: number; penWidth?: number }
) {
  const res = await PluginCommAPI.insertGeometry({
    penColor: options?.penColor ?? 0x9d,
    penType: options?.penType ?? 10,
    penWidth: options?.penWidth ?? 3,
    type: 'GEO_circle',
    ellipseCenterPoint: { x: centerX, y: centerY },
    ellipseMajorAxisRadius: radius,
    ellipseMinorAxisRadius: radius,
    ellipseAngle: 0,
  });
  if (!res?.success) throw new Error(res?.error?.message ?? 'insertGeometry failed');
  return res.result;
}
```

---

## Pattern 5: Lasso Button Routing — Prevent Accidental Main Panel (Pending Button ID)

**Problem**: All buttons with `showType=1` cause PluginHost to open the plugin view. If button dispatch is handled *only* inside `App.tsx`, there's a timing gap: the button event fires, the view opens, but the listener may not yet be registered, causing lasso buttons to always show the main screen.

**Solution**: Store the pressed button ID at module level in `index.js` *before* the view opens, then consume it immediately on `App.tsx` mount.

### index.js — store pending ID at module level

```js
import { DeviceEventEmitter } from 'react-native';
import { PluginManager } from 'sn-plugin-lib';

// Module-level: survives across component mounts
let pendingButtonId = null;

PluginManager.registerButtonListener({
  onButtonPress(event) {
    pendingButtonId = event.id;
    // Also emit for already-mounted components
    DeviceEventEmitter.emit('pluginButton', { id: event.id });
  },
});

// Consume once — call from App.tsx on mount
export const checkPendingButton = () => {
  const val = pendingButtonId;
  pendingButtonId = null;
  return val;
};
```

### App.tsx — consume pending ID on mount + listen for live events

```tsx
import { DeviceEventEmitter } from 'react-native';
import { PluginManager } from 'sn-plugin-lib';
import { checkPendingButton } from './index';

function App(): React.JSX.Element {
  const [screen, setScreen] = useState<'main' | 'lasso-action'>('main');

  useEffect(() => {
    const handleButton = (buttonId: number) => {
      if (buttonId === 100) {
        // Toolbar button → main panel
        setScreen('main');
      } else if (buttonId === 200) {
        // Lasso button → go directly to lasso screen, never show main
        setScreen('lasso-action');
        // ... extract lasso content here
      }
    };

    // 1. Consume button pressed BEFORE this component mounted
    const pending = checkPendingButton();
    if (pending !== null) handleButton(pending);

    // 2. Listen for buttons pressed WHILE component is mounted
    const sub = DeviceEventEmitter.addListener('pluginButton', ({ id }) => {
      checkPendingButton(); // clear the store
      handleButton(id);
    });

    return () => sub.remove();
  }, []);
  // ...
}
```

**Key rules**:
- Register `registerButtonListener` in `index.js` at module level, not inside `App.tsx`.
- `pendingButtonId` must be a plain module variable, not React state (it lives outside the component lifecycle).
- Call `checkPendingButton()` as the *first* side-effect in the mount `useEffect` to avoid even one render frame on the wrong screen.
- For buttons that should do background work + close (no UI): call `PluginManager.closePluginView()` inside the async handler; the user sees no UI at all.

---

## Pattern 6: Native System Floating Window

→ See [`floating-window.md`](floating-window.md) for TYPE_APPLICATION_OVERLAY overlay architecture, FloatingBubbleBridge API, foreground detection, and enter-bubble flow.

---

## Pattern 7: Multi-language Button Names

→ See [`i18n.md`](i18n.md) for `registerButton` JSON name format and `editDataTypes` reference.

---

## Pattern 8: Orientation & Screen Size Adaptation

→ See [`floating-window.md`](floating-window.md) for three-listener rotation pattern and device screen width constants.

---

## Pattern 9: Plugin Lifecycle — Reset State on Close

Use `addPluginLifeListener` to reset UI state whenever the plugin panel is closed. Without this, re-opening the panel may show stale UI from a previous session.

```ts
// In your root component's useEffect
import { PluginManager } from 'sn-plugin-lib';

useEffect(() => {
  const lifeSub = PluginManager.addPluginLifeListener({
    onStart: () => {
      // Optional: refresh data on re-open
    },
    onStop: () => {
      // Reset UI — called when user closes the plugin panel
      setCurrentScreen(null);
      setSelectedItems([]);
    },
  });

  return () => lifeSub.remove();
}, []);
```

---

## Pattern 10: Language Switching with i18next

→ See [`i18n.md`](i18n.md) for `registerLangListener` setup and i18next init config.

---

## Pattern 11: SQLite Local Storage

→ See [`sqlite.md`](sqlite.md) for `react-native-sqlite-storage` setup, `node_change/` pattern, and sandboxed DB path.

---

## Pattern 12: i18n Extract-Translate-Convert Workflow

→ See [`i18n.md`](i18n.md) for the full three-phase workflow: scan → .lang intermediate → JSON locales → source rewrite.

---

## Pattern 13: Page-Anchored Sequential Text Insertion

**Problem**: `PluginNoteAPI.insertText` always writes to the **currently displayed page**. When inserting text across multiple pages (e.g., receiving streamed text from a phone), the plugin must: (a) create new pages when the current page fills up, (b) wait for the user to flip to the new page before continuing, and (c) detect if the user switches notes or modifies page structure externally.

### Core state machine

```
[Active: inserting on targetPage]
    │
    ├─ page full → insertNotePage() + reloadFile()
    │   └─ [Page-Wait: timerRef=null, _isPageWait=true]
    │       ├─ user flips to targetPage → resume inserting
    │       ├─ 30s timeout → relocate targetPage to currentPage
    │       └─ note switched / pages changed externally → stop
    │
    ├─ getCurrentPageNum() !== targetPage before insertText
    │   └─ [Page-Wait] (same as above)
    │
    └─ queue empty → [Idle: timerRef=null, _isPageWait=false]
        └─ new enqueue() → resume inserting
```

### Key principles

1. **Pre-insert page verification**: Before every `insertText`, call `getCurrentPageNum()`. If it doesn't match `targetPage`, return to page-wait state instead of inserting onto the wrong page.

2. **`enqueue()` must respect page-wait**: When new text arrives during page-wait (`_isPageWait=true`), it goes into the queue but does NOT wake the scheduler. Only the polling loop (`_checkPageAndResume`) can resume after detecting the user has navigated to `targetPage`.

3. **Page-wait polling** (via `NativePageCheckerBridge` or `setInterval`):
   ```ts
   // Simplified logic inside the polling callback:
   const currentPage = await getCurrentPageNum();
   if (_isPageWait && currentPage === targetPage) {
     _isPageWait = false;
     // wait 1.5s for UI to stabilize, then resume
     await refreshOccupiedRanges();
     scheduleNext();
   }
   ```

4. **Timeout fallback** (30s, not 8s): If the user never flips to the new page, after 30s relocate `targetPage` to wherever the user currently is, refresh occupied ranges, and resume. This prevents permanent deadlock without inserting onto the wrong page.

5. **Note context monitoring** (throttled, every ~5s):
   ```ts
   // Inside the polling callback, every N ticks:
   const filePath = await getCurrentFilePath();
   if (filePath !== this.notePath) {
     stop(); onNoteChanged('note_switched', ...); return;
   }
   const totalPages = await getNoteTotalPageNum(this.notePath);
   if (totalPages !== this._expectedTotalPages) {
     stop(); onNoteChanged('pages_changed', ...); return;
   }
   ```
   When the plugin itself creates pages via `insertNotePage`, increment `_expectedTotalPages` so it isn't treated as an external change.

6. **Page annotation logging**: Every `insertText` success should log which page it targeted, for debugging:
   ```ts
   FileLogger.logEvent('PageAnnotation',
     `text inserted to page=${targetPage} currentPage=${currentPage}`);
   ```

### Anti-patterns to avoid

- ❌ Resuming insertion after a fixed timeout (e.g. 8s) without checking `currentPage === targetPage`
- ❌ Waking the scheduler from `enqueue()` during page-wait state
- ❌ Assuming `insertText` writes to the page you specify — there is no page parameter
- ❌ Not detecting note file switches during long-running background insertion

---

## Pattern 14: Pen Lasso — Stroke to Rectangular Lasso

→ See [`pen-emr.md`](pen-emr.md) for pen lasso flow, coordinate system, and critical ordering constraints.

---

## Pattern 15: EMR Pen Disable Architecture & Release

→ See [`pen-emr.md`](pen-emr.md) for full EMR disable internals, the `PluginApp.showPluginView` state pair, reflection-based release, and debugging methodology.

---

## Pattern 16: Scoped Pen Disable Around a Pen Operation

→ See [`pen-emr.md`](pen-emr.md) for the engage/release recipe, dual-pipeline explanation, and timing requirements.
