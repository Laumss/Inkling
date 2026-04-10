# Setup, Build & Debug Guide

---

## §1 Environment Requirements

| Dependency | Minimum Version | Notes |
|-----------|----------------|-------|
| Node.js | ≥ 18 LTS | `node -v` to verify |
| JDK | ≥ 19 | Oracle or OpenJDK. `javac -version` to verify |
| Android Studio | Narwhal 2025.1.2+ | Includes SDK Manager |
| Android SDK Platform | 35 (Android 15 VanillaIceCream) | Via SDK Manager → SDK Platforms |
| Android SDK Build-Tools | 35.0.0 | Via SDK Manager → SDK Tools |
| Yarn | latest | `npm install -g yarn` |
| React Native | **0.79.2** (locked) | Must match PluginHost runtime. Other versions will break. |

### Environment Variables (Windows)

```
ANDROID_HOME = C:\Users\<username>\AppData\Local\Android\Sdk
Path += %ANDROID_HOME%\platform-tools
```

Set via: Control Panel → System → Advanced → Environment Variables. Restart terminal after changes.

### npm Registry

If npm official registry is unreachable, switch to a mirror before creating the project:

```bash
npm config set registry https://registry.npmmirror.com
```

---

## §2 Create a New Plugin Project

```bash
npx @react-native-community/cli init <project_name> \
  --template @supernote-plugin/sn-plugin-template \
  --version 0.79.2
```

- `<project_name>`: your plugin name (e.g., `my-plugin`). Keep it simple, no spaces.
- **Do not change `--version 0.79.2`** — this must match the PluginHost RN runtime.
- If you previously installed `react-native-cli` globally, uninstall it first:
  ```bash
  npm uninstall -g react-native-cli react-native
  ```

After creation (~2-3 min), the project structure will be generated with `index.js`, `App.tsx`, `android/`, and build scripts.

---

## §3 Build & Package

### Build Command

In the plugin project root directory, use **PowerShell** to execute the build script:

```powershell
# Windows (PowerShell) — most common workflow
.\buildPlugin.ps1
```

```bash
# Linux / macOS
./buildPlugin.sh
```

### First Build

On first run, `PluginConfig.json` is auto-generated in the project root:

```json
{
  "name": "my-plugin",
  "pluginKey": "my-plugin",
  "pluginID": "98blcl1mp5fxamrm",
  "iconPath": "",
  "desc": "",
  "versionCode": "1",
  "versionName": "0.0.1",
  "jsMainPath": "index"
}
```

**Critical fields:**

| Field | Rule |
|-------|------|
| `pluginKey` | **Must match** `AppRegistry.registerComponent(appName, ...)` 's `appName`. Mismatch = plugin won't load. |
| `pluginID` | Auto-generated. **Never change** after first distribution — changing it makes the system treat it as a different plugin. |
| `iconPath` | Manually fill in the relative path to your plugin icon (e.g., `assets/icon/icon.png`). |
| `desc` | Plugin description. Fill manually. |
| `author` | Optional. Add manually; packaging does not generate it. |
| `jsMainPath` | Default `index`. Don't change unless you renamed your entry file. |

### Build Output

```
build/
├── generated/
│   ├── PluginConfig.json
│   ├── drawable-mdpi/
│   │   └── assets_icon_icon.png
│   └── my-plugin.bundle
└── outputs/
    └── my-plugin.snplg        ← This is the installable plugin package
```

### Moving the Project Directory

If you move the project folder to a new path, the Gradle build cache stores absolute paths and becomes stale. Clean it before rebuilding:

```powershell
# Run from the project root
Remove-Item -Recurse -Force android\.gradle, android\app\build, android\build -ErrorAction SilentlyContinue
.\buildPlugin.ps1
```

Without this step, Gradle reports:
```
No matching variant of project :react-native-fs was found. The consumer was configured to find a library ... but: No variants exist.
```
even though the source code and dependencies are unchanged.

---

## §4 Install to Device

### Method 1: Manual Copy

1. Connect Supernote to PC via USB
2. Copy `build/outputs/<name>.snplg` to the device's `MyStyle/` directory
3. On device: Settings → Apps → Plugins → Add Plugin → select your `.snplg` file

### Method 2: ADB Push (recommended for development)

If ADB is connected:

```powershell
adb push build\outputs\my-plugin.snplg /storage/emulated/0/MyStyle/
```

Then install on device via Settings → Apps → Plugins → Add Plugin.

### Verifying ADB Connection

```powershell
adb devices
```

Should show your device as `device` (not `unauthorized` or `offline`).

---

## §5 Debugging with ADB Logs

When the user has ADB connected, use this workflow to capture plugin runtime logs:

### Standard Debug Flow

```powershell
# Step 1: Clear all existing logs
adb logcat -c

# Step 2: Trigger your plugin action on the device
#         (tap the button, perform the lasso, etc.)

# Step 3: Wait ~10 seconds for the action to complete and logs to flush

# Step 4: Capture the logs
adb logcat -d > plugin_logs.txt
```

### Filtered Log Capture (recommended)

React Native logs typically go through `ReactNativeJS` tag. To filter:

```powershell
# Clear → wait → capture only RN JS logs
adb logcat -c
# ... perform action on device, wait 10 seconds ...
adb logcat -d -s ReactNativeJS:V > rn_logs.txt
```

### Real-time Monitoring

For continuous monitoring during development:

```powershell
# Clear first
adb logcat -c

# Stream RN logs in real time (Ctrl+C to stop)
adb logcat -s ReactNativeJS:V
```

### Common Log Tags

| Tag | Source |
|-----|--------|
| `ReactNativeJS` | All `console.log/warn/error` from your plugin JS/TS code |
| `PluginHost` | PluginHost lifecycle, plugin loading, AIDL communication |
| `PluginManager` | Button registration, event dispatching |
| `SNPlugin` | SDK native-side operations |

### Debug Tips

1. **Add strategic `console.log` in your plugin code** — they appear under `ReactNativeJS` tag
2. **Log API responses** — always log `res.success` and `res.error?.message` to diagnose failures
3. **Check for silent failures** — if `PluginManager.init()` was missed, most APIs fail silently
4. **Timestamp correlation** — use `adb logcat -v time` to add timestamps for correlating device actions with log entries

### Quick Debug Script (PowerShell)

Save this as `debug.ps1` in your project root for one-command debug capture:

```powershell
# debug.ps1 — Clear logs, wait for user action, capture
Write-Host "Clearing logs..." -ForegroundColor Yellow
adb logcat -c
Write-Host "Perform your action on the device now. Waiting 10 seconds..." -ForegroundColor Green
Start-Sleep -Seconds 10
Write-Host "Capturing logs..." -ForegroundColor Yellow
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$logFile = "debug_$timestamp.txt"
adb logcat -d -s ReactNativeJS:V PluginHost:V SNPlugin:V > $logFile
Write-Host "Logs saved to $logFile" -ForegroundColor Cyan
Get-Content $logFile | Select-Object -Last 50
```

### Common Error Patterns in Logs

| Log Message Pattern | Likely Cause | Fix |
|--------------------|-------------|-----|
| `"pluginKey mismatch"` or plugin fails to load | `PluginConfig.json` `pluginKey` ≠ `appName` in `app.json` | Align the two values |
| No ReactNativeJS output at all | `PluginManager.init()` not called | Add init call after `AppRegistry.registerComponent` |
| `"getLassoElements error"` / `"no lasso context"` | Calling lasso API without active selection | Ensure user has lasso-selected before calling |
| `"unknown pageSize"` from PointUtils | Passing incorrect Size to coordinate conversion | Use `PluginFileAPI.getPageSize()` result directly |
| `"insert failed"` for title/link in DOC | DOC doesn't support titles/links | Use these APIs only in NOTE context |
| `"layer not supported"` | Inserting title/link/five-star on non-main layer | Use `layerNum: 0` for these element types |

---

## §5b Including Third-Party Native Modules (node_change Pattern)

Plugins can bundle third-party React Native libraries that require Android native code (`.java`/`.kt`). The standard approach used by Ratta's own demo is the **`node_change/` directory pattern**.

### How it works

1. Place the (possibly patched) library source under `node_change/<library-name>/`
2. Reference it in `package.json` with a `file:` path:
   ```json
   {
     "dependencies": {
       "react-native-sqlite-storage": "file:./node_change/react-native-sqlite-storage",
       "react-native-zip-archive":    "file:./node_change/react-native-zip-archive"
     }
   }
   ```
3. Run `yarn install` — npm/yarn copies the local packages into `node_modules/` as usual.

### Build script auto-detection

`buildPlugin.sh` / `buildPlugin.ps1` automatically:
- Scans `node_modules/` for packages containing Android native code (`.java`/`.kt`)
- Triggers a Gradle build (`buildCustomApkDebug`)
- Copies the resulting APK as `app.npk` into `build/generated/`
- Sets `"nativeCodePackage": "/app.npk"` in the generated `PluginConfig.json`
- Collects all `ReactPackage` implementations and writes them to `"reactPackages": [...]`

You don't need to configure any of this manually — just put the library under `node_change/` and let the build script handle it.

### PluginConfig.json with native code

After a native build, `build/generated/PluginConfig.json` will look like:

```json
{
  "pluginID": "98blcl1mp5fxamrm",
  "pluginKey": "my-plugin",
  "name": { "en": "My Plugin", "zh_CN": "我的插件", "zh_TW": "我的插件", "ja": "マイプラグイン" },
  "desc": { "en": "...", "zh_CN": "..." },
  "iconPath": "/icon.png",
  "versionName": "0.1.0",
  "versionCode": "1",
  "jsMainPath": "index",
  "nativeCodePackage": "/app.npk",
  "reactPackages": [
    "com.example.myplugin.MyPluginPackage",
    "org.pgsqlite.SQLitePluginPackage"
  ]
}
```

### PluginConfig.json — multi-language name/desc

The `name` and `desc` fields in `PluginConfig.json` support either a plain string **or** a multi-language object:

```json
{
  "name": {
    "en": "My Plugin",
    "zh_CN": "我的插件",
    "zh_TW": "我的插件",
    "ja": "マイプラグイン"
  },
  "desc": {
    "en": "A helpful plugin for notes.",
    "zh_CN": "一个实用的笔记插件。"
  }
}
```

The device will display the entry matching the current system language.

---

## §6 Iterative Development Workflow

The typical dev loop for Supernote plugin development:

```
┌──────────────┐
│  Edit code   │ ← index.js / App.tsx / helpers
└──────┬───────┘
       ▼
┌──────────────┐
│   Build      │ ← .\buildPlugin.ps1
└──────┬───────┘
       ▼
┌──────────────┐
│  ADB push    │ ← adb push build\outputs\*.snplg /storage/emulated/0/MyStyle/
└──────┬───────┘
       ▼
┌──────────────────────┐
│  Reinstall on device │ ← Settings → Apps → Plugins → Add Plugin
└──────┬───────────────┘
       ▼
┌──────────────┐
│  Test + Logs │ ← adb logcat -c → action → wait 10s → adb logcat -d
└──────┬───────┘
       ▼
   Fix bugs → repeat
```

### Quick Rebuild & Deploy (PowerShell one-liner)

```powershell
.\buildPlugin.ps1; if($?) { adb push build\outputs\my-plugin.snplg /storage/emulated/0/MyStyle/; Write-Host "Deployed!" -ForegroundColor Green }
```