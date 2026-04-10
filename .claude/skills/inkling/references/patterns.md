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

## Pattern 6: Native System Floating Window (TYPE_APPLICATION_OVERLAY)

**Use case**: Persistent status bubble that survives `closePluginView()` — e.g., background processing indicator.

**Architecture**: Custom Android `NativeModule` → `WindowManager.addView()` with `TYPE_APPLICATION_OVERLAY`. Requires `SYSTEM_ALERT_WINDOW` on package `com.ratta.supernote.pluginhost`.

### FloatingBubbleBridge.ts — API surface

```ts
import { NativeModules, NativeEventEmitter } from 'react-native';
const { FloatingBubble } = NativeModules;

// Methods
FloatingBubbleBridge.isAvailable       // boolean — native module registered?
FloatingBubbleBridge.show(statusText)  // show or update bubble
FloatingBubbleBridge.hide()            // remove from screen
FloatingBubbleBridge.updateText(text)  // update text without recreating
FloatingBubbleBridge.setPageHeight(h)  // for drag→EMR coordinate mapping
FloatingBubbleBridge.setScreenHeight(h)
FloatingBubbleBridge.checkPermission() // → Promise<boolean>
FloatingBubbleBridge.requestPermission() // open system overlay settings

// Events (via NativeEventEmitter)
FloatingBubbleBridge.onTap(cb)              // bubble tapped
FloatingBubbleBridge.onDragEnd(cb)          // cb({screenY, pageY}) — pageY pre-converted to EMR
FloatingBubbleBridge.onPermissionDenied(cb) // show() called without permission
```

### Enter bubble flow

```ts
const ok = await FloatingBubbleBridge.checkPermission();
if (ok) {
  FloatingBubbleBridge.setPageHeight(pageHeightEMR);
  FloatingBubbleBridge.setScreenHeight(screenHeight);
  FloatingBubbleBridge.show(statusText);
  setTimeout(() => PluginManager.closePluginView(), 150); // wait for native render
} else {
  FloatingBubbleBridge.requestPermission();
}
```

### Critical notes
- Always `FloatingBubbleBridge.hide()` at the top of mount `useEffect` — cleans up stale bubbles from previous session.
- 150ms delay before `closePluginView()` is required: `show()` dispatches via `handler.post`; closing too early freezes before render.
- When `onBubbleTap` fires, native has already called `showPluginView()` — do NOT call it again from JS.
- `pageY` from `onDragEnd` is pre-converted to EMR by the native module.

---

## Pattern 7 — Multi-language Button Names

The `name` field in `registerButton` is not a plain string — it's an embedded JSON string that displays the matching name based on device language.

```ts
// index.js
import { Image } from 'react-native';
import { PluginManager } from 'sn-plugin-lib';

PluginManager.registerButton(1, [], {
  id: 100,
  // name must be a serialized JSON string — NOT a plain string
  name: JSON.stringify({
    en: 'Sticker',
    zh_CN: '贴纸',
    zh_TW: '貼紙',
    ja: 'ステッカー',
  }),
  icon: Image.resolveAssetSource(require('./assets/icon.png')).uri,
  color: 0xffffff,
  bgColor: 0x000000,
});

// Lasso button with editDataTypes — only shows when specific element types are selected
PluginManager.registerButton(2, [], {
  id: 200,
  name: JSON.stringify({ en: 'New Sticker', zh_CN: '新建贴纸', zh_TW: '新建貼紙', ja: '新しいステッカー' }),
  icon: Image.resolveAssetSource(require('./assets/icon.png')).uri,
  bgColor: 0x000000,
  editDataTypes: [0, 5],  // 0=stroke, 5=geometry — button only shows when these types are lasso-selected
});
```

**`editDataTypes` values** (type=2 lasso buttons only):

| Value | Element Type |
|-------|-------------|
| 0 | Stroke (trail) |
| 1 | Title |
| 2 | Picture |
| 3 | Text box |
| 4 | Link |
| 5 | Geometry |

---

## Pattern 8 — Orientation & Screen Size Adaptation

Supernote devices can rotate and have different screen sizes. Always handle both.

```tsx
// StickerPage.tsx
import React, { useEffect, useRef, useState } from 'react';
import { DeviceEventEmitter, Dimensions } from 'react-native';
import { NativePluginManager } from 'sn-plugin-lib';

const MyPage = () => {
  const [rotation, setRotation] = useState<number | null>(null);
  const [screenWidth, setScreenWidth]   = useState(Dimensions.get('window').width);
  const [screenHeight, setScreenHeight] = useState(Dimensions.get('window').height);

  useEffect(() => {
    // 1. Get initial orientation on mount
    NativePluginManager.getOrientation().then(r => setRotation(r));

    // 2. Listen for rotation events
    const rotSub = DeviceEventEmitter.addListener(
      'plugin_event_rotation',
      (msg: { rotation: number }) => setRotation(msg.rotation),
    );

    // 3. Listen for dimension changes
    const dimSub = Dimensions.addEventListener('change', ({ window: { width, height } }) => {
      setScreenWidth(width);
      setScreenHeight(height);
    });

    return () => {
      rotSub.remove();
      dimSub.remove();
    };
  }, []);

  // Known device screen widths (portrait):
  //   A5X  (portrait) : 994  | (landscape) : 1325
  //   Manta (portrait): 1024 | (landscape) : 1365
  //   Nomad / A6X      : smaller values
  const wd = Math.round(screenWidth);
  const isManta = wd === 1024 || wd === 1365;
  const isA5X   = wd === 994  || wd === 1325;

  // Apply device-specific styles
  const styles = isManta ? stylesLG : isA5X ? stylesMD : stylesXS;

  return <View style={{ width: screenWidth, height: screenHeight }}>...</View>;
};
```

---

## Pattern 9 — Plugin Lifecycle: Reset State on Close

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

## Pattern 10 — Language Switching with i18next

Listen to `registerLangListener` and update i18next dynamically. Note the underscore-to-dash conversion.

```ts
// src/i18n/index.ts
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import * as RNLocalize from 'react-native-localize';
import { PluginManager } from 'sn-plugin-lib';
import en from './locales/en_US.json';
import zhCN from './locales/zh_CN.json';
import zhTW from './locales/zh_TW.json';
import ja from './locales/ja_JP.json';

const resources = {
  en:    { translation: en },
  'zh-CN': { translation: zhCN },
  'zh-TW': { translation: zhTW },
  ja:    { translation: ja },
};

// Initial language from device locale
const systemLang = (RNLocalize.getLocales()[0]?.languageTag ?? 'en');

// Listen for language changes from the host app
PluginManager.registerLangListener({
  onMsg: (msg: { lang: string }) => {
    // msg.lang uses underscores: "zh_CN" → i18next needs dashes: "zh-CN"
    const nextLng = msg.lang.replace('_', '-');
    if (nextLng !== i18n.language) {
      i18n.changeLanguage(nextLng);
    }
  },
});

i18n.use(initReactI18next).init({
  resources,
  lng: systemLang,
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export default i18n;
```

---

## Pattern 11 — SQLite Local Storage in a Plugin

Plugins can use SQLite for persistent data storage via `react-native-sqlite-storage`. The database path must be prefixed with `plugins/<pluginID>/` to stay within the plugin's sandboxed directory.

### Setup

Place a patched copy of the library under `node_change/react-native-sqlite-storage/` and reference it in `package.json`:

```json
{
  "dependencies": {
    "react-native-sqlite-storage": "file:./node_change/react-native-sqlite-storage"
  }
}
```

The `node_change/` directory holds third-party npm packages that need source-level patches to work inside the plugin environment. The build script (`buildPlugin.sh`) auto-detects native code there and compiles it into `app.npk`.

### Database Initialization

```ts
// src/db/index.ts
import SQLite from 'react-native-sqlite-storage';
import PluginConfig from '../../PluginConfig.json';

// DB path prefix — keeps files inside the plugin's sandbox
const PLUGIN_ID = PluginConfig.pluginID;
const DB_LOCATION = `plugins/${PLUGIN_ID}/`;

let db: SQLite.SQLiteDatabase | null = null;

export async function initDB() {
  db = SQLite.openDatabase(
    { name: 'my_plugin.db', location: DB_LOCATION },
    () => console.log('DB opened'),
    (err) => console.error('DB open error', err),
  );
  // Create tables
  await runSQL(`CREATE TABLE IF NOT EXISTS Items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    path TEXT NOT NULL,
    created_at INTEGER
  )`);
}

export function runSQL(sql: string, args: any[] = []): Promise<{ rows: any[]; insertId?: number }> {
  return new Promise((resolve, reject) => {
    db!.transaction(tx => {
      tx.executeSql(
        sql, args,
        (_, result) => resolve({ rows: result.rows.raw(), insertId: result.insertId }),
        (_, err)    => { reject(err); return false; },
      );
    });
  });
}
```

---

## Pattern 12 — i18n Extract-Translate-Convert Workflow

**Trigger**: User says "help me internationalize", "extract hardcoded strings", "add multi-language support", "i18n this file", or uploads `.js`/`.tsx` files and asks for translation.

---

### Overall Flow

```
Source code (.js/.tsx)
      │
      ▼ Phase 1 — Scan & extract
  Intermediate file (.lang)    ← Human-editable intermediate format, key=value lines
      │
      ▼ Phase 2 — Convert
  JSON locale files            ← src/i18n/locales/{zh_CN,en_US,zh_TW,ja_JP}.json
      │
      ▼ Phase 3 — Rewrite source
  Modified source files        ← Hardcoded strings → t('key'), inject useTranslation hook
```

---

### Phase 1 — Scan Rules

#### Include (these are UI text)

| Type | Code example | Include? |
|------|-------------|----------|
| JSX text node | `<Text>OK</Text>` | Yes |
| JSX string prop | `placeholder="Enter name"` | Yes (UI-related props only) |
| state setter call | `setModalTitle('New Group')` | Yes |
| toast / alert message | `setToastText('Deleted')` | Yes |
| Parameterized message | `` `${name} was deleted` `` | Yes → convert to `%1$s was deleted` |

**Included prop names**: `placeholder`, `title`, `label`, `message`, `text`, `hint`, `description`, `emptyText`, `helperText`

#### Exclude (do not translate)

| Type | Example | Reason |
|------|---------|--------|
| Already wrapped in i18n | `t('btn.ok')` | Already handled |
| console.* content | `console.log('init DB')` | Dev logs |
| Technical strings | `'plugin_sticker'`, `'sticker_group_'` | Keys/identifiers |
| File paths / URLs | `'/sticker/'`, `'https://...'` | Not translatable |
| Pure numbers or symbols | `'0'`, `'/'`, `'\n'` | No meaning |
| Regex / format templates | `'%'`, `'.sticker'` | Format tokens |
| English-only technical names | `'ReactNativeJS'`, `'Database OPENED'` | Logs/system |

#### Key naming convention

```
<screen>.<component>.<element>
```

| Source | Key example |
|--------|------------|
| Common buttons (shared) | `btn.ok`, `btn.cancel`, `btn.close`, `btn.delete`, `btn.rename` |
| Modal title/content | `modal.create_group.title`, `modal.delete.message` |
| Toast / Snackbar | `toast.delete_success`, `toast.import_failed` |
| Error messages | `error.file_not_found`, `error.name_conflict` |
| Page / Screen | `page.sticker.title`, `page.create.placeholder_name` |
| Parameterized messages | `toast.progress` = `Completed %1$s%%`, `msg.killed_by` = `%1$s was killed by %2$s` |

**Rules**:
- All lowercase, `_` between words, `.` for hierarchy
- Same-meaning strings share one key across pages (e.g. all "OK" buttons use `btn.ok`)
- Parameters use `%1$s`, `%2$s` (numbered from 1); numeric params also use `%1$s`

---

### .lang intermediate format spec

```lang
# === <filename or screen name> ===

# <component / feature block description>
<key>=<text in current language>

# Parameterized example
toast.progress=%1$s / %2$s completed
msg.killed_by=%1$s was killed by %2$s
```

**Rules**:
- One entry per line, `key=value`, no spaces around `=`
- `#` lines are comments noting source file/context for translators
- Each key appears only once (deduplicate, keep the comment from first occurrence)
- Blank lines separate feature blocks
- Don't put translated text in the key (`ok=OK` not `OK=OK`)

---

### Phase 2 — .lang → i18next JSON conversion rules

| .lang input | JSON output |
|------------|-----------|
| `btn.ok=OK` | `"btn.ok": "OK"` |
| `msg.killed_by=%1$s was killed by %2$s` | `"msg.killed_by": "{{param1}} was killed by {{param2}}"` |
| `toast.progress=%1$s/%2$s` | `"toast.progress": "{{param1}}/{{param2}}"` |
| `# comment line` | *(discarded)* |
| blank line | *(discarded)* |

**Parameter conversion**: `%N$s` / `%N$d` → `{{paramN}}` (N is the numeric index)

**Output file paths** (standard Supernote plugin structure):

```
src/i18n/locales/
  zh_CN.json   ← Chinese Simplified (primary language, all values filled)
  en_US.json   ← English (same keys, values translated to English)
  zh_TW.json   ← Chinese Traditional
  ja_JP.json   ← Japanese
```

When generating: output `zh_CN.json` first (with translations), then for other locales keep all keys but either mark values as `"TODO: <zh_CN value>"` as a translation hint, or have Claude translate them directly.

---

### Phase 3 — Source file rewrite rules

#### Add hook (top of each component that uses `t()`)

```ts
// 1. Top-level import (if not already present)
import { useTranslation } from 'react-i18next';

// 2. First line inside the component function body
const { t } = useTranslation();
```

#### Replacement rules

```tsx
// Before
<Text>OK</Text>
<TextInput placeholder="Enter name" />
setModalTitle('New Group');
setToastText(`${count} items deleted`);

// After
<Text>{t('btn.ok')}</Text>
<TextInput placeholder={t('page.create.placeholder_name')} />
setModalTitle(t('modal.create_group.title'));
setToastText(t('toast.delete_count', { param1: count }));
```

**i18next parameterized call**: `t('key', { param1: val1, param2: val2 })`

---

### Full example (compact)

**Input**: `<Text>Sticker Library</Text>` / `setToastText('Deleted')` / `` setModalMessage(`Delete ${groupName}?`) ``

**Phase 1** → `.lang`: `page.sticker_base.title=Sticker Library` / `toast.delete_success=Deleted` / `modal.delete.message=Delete %1$s?`

**Phase 2** → `en_US.json`: `"modal.delete.message": "Delete {{param1}}?"` (`%N$s` → `{{paramN}}`)

**Phase 3** → source: `{t('page.sticker_base.title')}` / `t('toast.delete_success')` / `t('modal.delete.message', { param1: groupName })`

---

### Claude's steps

1. **Scan**: Find hardcoded strings file by file → 2. **Deduplicate + assign keys** → 3. **Output .lang** → 4. **Output JSON** (primary language + other locales) → 5. **Output modified source** → 6. **Check Pattern 10** (i18next init config)

Single file: .lang + JSON + modified code all in one pass. Full project: merge .lang → JSON → modify files one by one.
