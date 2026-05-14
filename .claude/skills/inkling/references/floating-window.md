# Floating Window & Screen Adaptation Patterns

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

### Foreground app detection — auto-hide overlay when leaving Note app

`ActivityManager.getRunningTasks()` does NOT work: pluginhost lacks `GET_TASKS` permission, so it only sees its own process's tasks and can never detect another app in the foreground.

**Working approach**: `Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities"))` and parse the `mResumedActivity` line. Read line-by-line and stop at the first match to avoid consuming the full (large) dumpsys output.

```kotlin
private val resumedActivityRegex = Regex("""mResumedActivity:.*?(\S+)/(\S+)\s""")

private fun checkIsNoteAppForeground(): Boolean {
    val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities"))
    val reader = proc.inputStream.bufferedReader()
    var matched: MatchResult? = null
    reader.useLines { lines ->
        for (line in lines) {
            if ("mResumedActivity" in line) {
                matched = resumedActivityRegex.find(line)
                break
            }
        }
    }
    proc.waitFor()
    // matched.groupValues[1] = package, groupValues[2] = class (may start with ".")
}
```

**Match by package name, not Activity class**: The Note app (`com.ratta.supernote.note`) uses a single Activity (`NoteInsidePagesActivity`) for everything — note editing, layer manager, page manager, style settings, knowledge cards are all View-level switches inside this Activity. They do NOT create new Activities, Windows, or Fragments. `dumpsys` cannot distinguish between the editing canvas and these internal panels, so Activity-level filtering is not useful. Match `pkg == "com.ratta.supernote.note"` instead.

The Document app (`com.supernote.document`) is a separate package and IS detectable — decide per-plugin whether the overlay should remain visible in it.

---

## Pattern 8: Orientation & Screen Size Adaptation

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
