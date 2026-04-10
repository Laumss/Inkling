# Supernote Plugin API Quick Reference

All imports from `sn-plugin-lib`. All async methods return `APIResponse<T>` unless noted.

---

## §1 PluginManager

```ts
import { PluginManager } from 'sn-plugin-lib';
```

### Lifecycle & Info

| Method | Returns | Description |
|--------|---------|-------------|
| `init()` | `void` | **Must call first** after `AppRegistry.registerComponent(...)`. All other SDK calls fail without this. |
| `getPluginDirPath()` | `Promise<string>` | Plugin's runtime directory path |
| `getPluginName()` | `Promise<string>` | Plugin name from PluginConfig.json |
| `getDeviceType()` | `Promise<number>` | Device type: 0=A5, 1=A6, 2=A6X, 3=A5X, 4=Nomad, 5=Manta |
| `closePluginView()` | `void` | Close the plugin UI container |

### Button Registration

```ts
registerButton(type: number, appTypes: string[], config: PluginButton): Promise<boolean>;
```
- `type`: `1` = toolbar, `2` = lasso toolbar, `3` = text-selection toolbar (DOC only)
- `appTypes`: `['NOTE']`, `['DOC']`, or `['NOTE', 'DOC']`
- `config`:
  - `id: number` — unique button ID (keep stable after release)
  - `name: string` — **multi-language JSON string**, e.g. `'{"en":"Sticker","zh_CN":"贴纸","zh_TW":"貼紙","ja":"ステッカー"}'`. A plain string also works but won't localize.
  - `icon: string` — icon URI (use `Image.resolveAssetSource(require(...)).uri`)
  - `showType?: number` — `0` = no UI, `1` = show plugin UI (default `1`)
  - `editDataTypes?: number[]` — (type=2 only) lasso data types that trigger this button: `0`=stroke, `1`=title, `2`=picture, `3`=text, `4`=link, `5`=geometry. **⚠️ These are NOT `ElementType` values (0/100/200/…) — this is a separate 0–5 index.**

```ts
unregisterButton(type: number, buttonId: number): void;
```

```ts
getButtonState(type: number, buttonId: number): Promise<number>;
setButtonState(type: number, buttonId: number, state: number): void;
```

### Config Button

```ts
registerConfigButton(): Promise<boolean>;
registerConfigButtonListener(listener: { onClick(): void }): { remove(): void };
```

### Event Listeners

```ts
registerButtonListener(listener: {
  onButtonPress: (event: {
    id: number;
    name: string;
    icon: string;
    pressEvent?: number;   // 3 = lasso-toolbar button press
  }) => void;
}): { remove(): void };
```

```ts
registerEventListener(
  event: string,       // 'event_pen_up' | 'event_lasso_pen_up'
  registerType: number, // 0=first, 1=normal, 2=last
  listener: { onMsg(msg: Element[]): void }
): { remove(): void };
```

```ts
registerLangListener(listener: {
  onMsg: (msg: { lang: string }) => void;   // msg.lang e.g. "zh_CN", "en", "zh_TW", "ja"
}): { remove(): void };
// ⚠️ lang uses underscore (e.g. "zh_CN"), convert to dash for i18next: msg.lang.replace("_", "-")
```

```ts
addPluginLifeListener(listener: {
  onStart?: () => void;
  onStop?: () => void;
}): { remove(): void };
// onStop fires when the plugin panel is closed — use it to reset UI state (e.g. setStickerVisible(null))
```

---

## §1b NativePluginManager (Native Module)

```ts
import { NativePluginManager } from 'sn-plugin-lib';
```

> `NativePluginManager` is a lower-level native module distinct from `PluginManager`. It provides device/environment info that `PluginManager` doesn't expose.

| Method | Returns | Description |
|--------|---------|-------------|
| `getPluginDirPath()` | `Promise<string>` | Plugin's **private data directory** — use this for storing databases, sticker files, cached assets. Different from `PluginManager.getPluginDirPath()`. Cache the result (it's slow to call repeatedly). |
| `getOrientation()` | `Promise<number>` | Current device orientation. Use on mount to set initial layout. Listen to `plugin_event_rotation` for changes. |

### Orientation Change Event

React Native's `DeviceEventEmitter` fires `'plugin_event_rotation'` when the device rotates:

```ts
import { DeviceEventEmitter } from 'react-native';

DeviceEventEmitter.addListener('plugin_event_rotation', (msg: { rotation: number }) => {
  setRotation(msg.rotation);
});
```

Always also listen to `Dimensions.addEventListener('change', ...)` to get updated `width`/`height` after rotation.

⚠️ `getPluginDirPath()` is slow — cache the result in a module-level variable.

---

## §1c FileUtils

```ts
import { FileUtils } from 'sn-plugin-lib';
```

| Method | Signature | Notes |
|--------|-----------|-------|
| `copyFile` | `(srcPath, destPath) → Promise<boolean>` | Copy a file. Returns true on success. |
| `deleteFile` | `(path) → Promise<boolean>` | Delete a single file. |
| `deleteDir` | `(dirPath) → Promise<boolean>` | Delete a directory and all contents. |
| `listFiles` | `(dirPath) → Promise<{path: string}[]>` | List files in a directory. Returns array of file descriptor objects. |
| `getFileMD5` | `(path) → Promise<string>` | Compute MD5 hash of a file. |
| `renameToFile` | `(oldPath, newPath) → Promise<boolean>` | Rename/move a file. |

---

## §2 PluginCommAPI (Common — works in NOTE & DOC)

```ts
import { PluginCommAPI } from 'sn-plugin-lib';
```

### Element Creation & Lifecycle

| Method | Signature | Notes |
|--------|-----------|-------|
| `createElement` | `(type: number) → APIResponse<Element>` | Create native-side element with accessors. Always call before inserting. |
| `recycleElement` | `(uuid: string) → void` | Free native-side cache for an element. |
| `clearElementCache` | `() → void` | Free all cached element data. |

### Sticker Operations

| Method | Signature | Notes |
|--------|-----------|-------|
| `saveStickerByLasso` | `(stickerPath: string) → APIResponse<boolean>` | Save lasso selection as sticker file at the given path. Requires lasso context. |
| `getStickerSize` | `(stickerPath: string) → APIResponse<Size>` | Get sticker dimensions. |
| `generateStickerThumbnail` | `(stickerPath: string, outputPath: string, size: {width, height}) → APIResponse<boolean>` | Generate thumbnail image. Pass a Size object (not separate w/h args). |
| `convertElement2Sticker` | `({machineType, elements, stickerPath}) → APIResponse<boolean>` | Convert elements to sticker file. machineType from `getDeviceType()`. |
| `insertSticker` | `(stickerPath: string) → APIResponse<boolean>` | Insert sticker into current page. |

### Lasso Operations (⚠️ require active lasso context)

| Method | Signature | Notes |
|--------|-----------|-------|
| `getLassoRect` | `() → APIResponse<Rect>` | Lasso bounding box (pixel coords). |
| `resizeLassoRect` | `(rect: Rect) → APIResponse<boolean>` | Resize lasso box. **Only proportional scaling supported.** |
| `lassoElements` | `(rect: Rect) → APIResponse<boolean>` | Programmatically create a lasso selection from a rectangle (pixel coords). After success, other lasso APIs become usable. |
| `getLassoElements` | `() → APIResponse<Element[]>` | All elements in lasso selection. |
| `getLassoElementTypeCounts` | `() → APIResponse<object>` | Detailed count object: `{trailNum, trailLinkNum, textLinkNum, todoLinkNum, titleNum, normalTextBoxNum, digestTextBoxNum, digestTextBoxEditableNum, geometryNum, straightLineNum, circleNum, ellipseNum, polygonNum}`. |
| `deleteLassoElements` | `() → APIResponse<boolean>` | Delete all lasso-selected elements. |
| `setLassoBoxState` | `(state: number) → APIResponse<boolean>` | `0`=show, `1`=hide, `2`=remove (permanent). |
| `getLassoGeometries` | `() → APIResponse<Geometry[]>` | Geometries in lasso. |
| `modifyLassoGeometry` | `(geometry: Geometry) → APIResponse<boolean>` | Modify a single lasso geometry. |

### Page & File Context

| Method | Signature | Notes |
|--------|-----------|-------|
| `getCurrentPageNum` | `() → APIResponse<number>` | Current page number. |
| `getCurrentFilePath` | `() → APIResponse<string>` | Currently open file path. |
| `reloadFile` | `() → APIResponse<boolean>` | Reload currently opened file. |
| `getPenInfo` | `() → APIResponse<PenInfo>` | Get currently selected pen type in the app. |

### Recognition

| Method | Signature | Notes |
|--------|-----------|-------|
| `recognizeElements` | `(elements: Object[], size: {width, height}) → APIResponse<string>` | Recognize stroke/text box elements as text. `size` is the note page size in pixels. |
| `cancelRecognize` | `() → APIResponse<boolean>` | Cancel the current recognition task. |

### Geometry & Misc

| Method | Signature | Notes |
|--------|-----------|-------|
| `insertGeometry` | `(geometry: Geometry) → APIResponse<boolean>` | Insert geometry into current page. Pixel coords. Set `geometry.showLassoAfterInsert` to auto-select with lasso after insertion. |
| `getNoteSystemTemplates` | `() → APIResponse<Template[]>` | System note templates. |
| `insertFiveStar` | `(starPoints: Point[]) → APIResponse<boolean>` | Insert five-star into current page/layer. **Pixel coords.** Must be 6 points with first===last. |

---

## §3 PluginFileAPI (File operations — direct file manipulation)

```ts
import { PluginFileAPI } from 'sn-plugin-lib';
```

### Element CRUD

| Method | Signature | Notes |
|--------|-----------|-------|
| `getElements` | `(page, filePath) → APIResponse<Element[]>` | All elements on a page. |
| `insertElements` | `(filePath, page, elements: Element[]) → APIResponse<boolean>` | Insert elements. Call `createElement()` first. |
| `modifyElements` | `(filePath, page, elements: Element[]) → APIResponse<number[]>` | Modify existing elements. Returns indices of successfully modified elements. |
| `replaceElements` | `(filePath, page, elements: Element[]) → APIResponse<boolean>` | Replace ALL elements on a page. |
| `deleteElements` | `(filePath, page, numsInPage: number[]) → APIResponse<boolean>` | Delete elements by their indices in the page. |
| `getElementCounts` | `(pageNum, filePath) → APIResponse<number>` | Count elements on a page. |
| `getElementNumList` | `(pageNum, filePath, elementType) → APIResponse<number[]>` | List element nums by type. |
| `getElement` | `(filePath, page, numInPage) → APIResponse<Element>` | Get single element by page position. |
| `getLastElement` | `() → APIResponse<Element>` | Get the last element on the current page. |

### Page Management

| Method | Signature | Notes |
|--------|-----------|-------|
| `getNoteTotalPageNum` | `(filePath) → APIResponse<number>` | Total pages. |
| `getPageSize` | `(filePath, pageNum) → APIResponse<Size>` | Page size in pixels. Needed for coordinate conversion. |
| `insertNotePage` | `({notePath, page, template}) → APIResponse<boolean>` | Insert a new page after `page`. |
| `removeNotePage` | `(filePath, pageNum) → APIResponse<boolean>` | Remove a page. |
| `createNote` | `({notePath, template, mode, isPortrait}) → APIResponse<boolean>` | Create new note file. `mode`: 0=normal, 1=recognition. `template`: system template name OR custom template image path. |

### Layer Management

| Method | Signature | Notes |
|--------|-----------|-------|
| `getLayers` | `(filePath, pageNum) → APIResponse<Layer[]>` | Get page layers. |
| `insertLayer` | `(filePath, pageNum, layer: Layer) → APIResponse<boolean>` | Add a custom layer. |
| `modifyLayers` | `(filePath, pageNum, layers: Layer[]) → APIResponse<boolean>` | Modify layer properties. |
| `deleteLayers` | `(filePath, pageNum, layerNums: number[]) → APIResponse<boolean>` | Delete layers by number. |
| `sortLayers` | `(filePath, pageNum, layerNums: number[]) → APIResponse<boolean>` | Reorder layers. |
| `clearLayerElements` | `(filePath, pageNum, layerNum) → APIResponse<boolean>` | Clear all elements on a layer. |

### Titles, Keywords, Templates, Marks

| Method | Signature | Notes |
|--------|-----------|-------|
| `getTitles` | `(filePath, pageNum?) → APIResponse<Title[]>` | Get titles. Optional page filter. |
| `getKeyWords` | `(filePath) → APIResponse<KeyWord[]>` | Get all keywords. |
| `insertKeyWord` | `(filePath, keyWord: KeyWord) → APIResponse<boolean>` | Add a keyword. |
| `deleteKeyWord` | `(filePath, keyWord: KeyWord) → APIResponse<boolean>` | Remove a keyword. |
| `getNotePageTemplate` | `(filePath, pageNum) → APIResponse<Template>` | Get page template. |
| `getNoteType` | `(filePath) → APIResponse<number>` | Note type. |
| `getFileMachineType` | `(filePath) → APIResponse<string>` | Machine/device type. |
| `getMarkPages` | `(filePath) → APIResponse<number[]>` | Pages with marks. |
| `generateMarkThumbnails` | `(filePath, pageNum, outputPath) → APIResponse<boolean>` | Generate mark thumbnail. |
| `clearMarkElements` | `(filePath, pageNum) → APIResponse<boolean>` | Clear mark elements. |
| `searchFiveStars` | `(filePath) → APIResponse<number[]>` | Search five-star elements. Returns page indices. |

### Image Generation

| Method | Signature | Notes |
|--------|-----------|-------|
| `generateNotePng` | `(filePath, pageNum, outputPath, width?, height?) → APIResponse<boolean>` | Render page to PNG. |
| `generateNoteTemplatePng` | `(filePath, pageNum, outputPath) → APIResponse<boolean>` | Render template to PNG. |

---

## §4 PluginNoteAPI (NOTE-only)

```ts
import { PluginNoteAPI } from 'sn-plugin-lib';
```

### Text Box

| Method | Signature | Notes |
|--------|-----------|-------|
| `insertText` | `(textBox) → APIResponse<boolean>` | Insert text box. Pixel coords for `textRect`. NOTE main layer only. Supports undo/redo. |
| `getLassoText` | `() → APIResponse<TextBox[]>` | Get lasso-selected text boxes. |
| `modifyLassoText` | `(textBox) → APIResponse<boolean>` | Modify a single lasso-selected text box. |

`insertText` params: `{ textContentFull, textRect: Rect, fontSize?, fontPath?, textAlign? (0=left,1=center,2=right), textBold? (0/1), textItalics? (0/1), textFrameWidthType? (0=fixed,1=auto), textFrameStyle? (0=none,3=stroke), textEditable? (0=editable,1=locked) }`

### Image

| Method | Signature | Notes |
|--------|-----------|-------|
| `insertImage` | `(pngPath: string) → APIResponse<boolean>` | Insert PNG image into current page/layer. |

### Title (⚠️ main layer only, NOTE only)

| Method | Signature | Notes |
|--------|-----------|-------|
| `setLassoTitle` | `({style}) → APIResponse<boolean>` | Convert lasso strokes/geometry to title. `style`: 0=remove, 1=black, 2=gray-white, 3=gray-black, 4=shadow. |
| `getLassoTitles` | `() → APIResponse<Title[]>` | Get lasso-selected titles. |
| `modifyLassoTitle` | `({style}) → APIResponse<boolean>` | Change title style. |

### Link (⚠️ main layer only, NOTE only)

| Method | Signature | Notes |
|--------|-----------|-------|
| `getLassoLinks` | `() → APIResponse<Link[]>` | Get lasso-selected links. |
| `insertTextLink` | `(textLink: TextLink) → APIResponse<boolean>` | Insert text link. `linkType`: 0=note page, 1=note file, 2=doc, 3=image, 4=URL. |
| `setLassoStrokeLink` | `({destPath, destPage, style, linkType}) → APIResponse<boolean>` | Convert lasso strokes to link. |
| `modifyLassoLink` | `(lassoLink) → APIResponse<boolean>` | Modify single lasso link. |

### Save

| Method | Signature | Notes |
|--------|-----------|-------|
| `saveCurrentNote` | `() → APIResponse<boolean>` | Save current note. **Call before file-level APIs** (insertElements/modifyElements/replaceElements) to avoid cache-vs-file inconsistency. |

---

## §5 PluginDocAPI (DOC-only)

```ts
import { PluginDocAPI } from 'sn-plugin-lib';
```

| Method | Signature | Notes |
|--------|-----------|-------|
| `getCurrentDocText` | `(page: number) → APIResponse<string>` | Get text of specified page. |
| `getLastSelectedText` | `() → APIResponse<string>` | Get the most recently selected text, even when no text is currently selected. |
| `getCurrentTotalPages` | `() → APIResponse<number>` | Total pages in document. |

---

## §6 PointUtils (Coordinate Conversion)

```ts
import { PointUtils } from 'sn-plugin-lib';
```

| Method | Signature | Notes |
|--------|-----------|-------|
| `androidPoint2Emr` | `(point: Point, pageSize: Size) → Point` | Pixel → EMR |
| `emrPoint2Android` | `(point: Point, pageSize: Size) → Point` | EMR → Pixel |

**Supported page sizes** (others throw "unknown pageSize"):
- `1404×1872` (A5X portrait) → EMR max `15819×11864`
- `1872×1404` (A5X landscape) → EMR max `11864×15819`
- `1920×2560` (Manta portrait) → EMR max `21632×16224`
- `2560×1920` (Manta landscape) → EMR max `16224×21632`

**Constants**: `PointUtils.NORMAL_PAGE_SIZE`, `PointUtils.A5X2_PAGE_SIZE`, `PointUtils.MACHINE_TYPE_*` (A5=0..Manta=5), `PointUtils.ROTATION_*` (orientation constants). See `references/types.md §PointUtils Constants` for full list.