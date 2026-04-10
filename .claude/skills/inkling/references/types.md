# Supernote Plugin SDK Type Definitions

All types from `sn-plugin-lib`.

---

## Core Types

### APIResponse\<T\>

Every async SDK method returns this wrapper.

```ts
interface APIResponse<T> {
  success: boolean;
  result?: T;          // only valid when success === true
  error?: {
    message: string;
  };
}
```

**Usage**: Always check `success` before reading `result`. When `success === false`, `result` is empty but `error.message` describes the failure.

### Point

```ts
interface Point {
  x: number;
  y: number;
}
```

### Rect

```ts
interface Rect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}
```

**Constraint**: Must have non-zero area (`right > left && bottom > top`) for text box insertion.

### Size

```ts
interface Size {
  width: number;
  height: number;
}
```

---

## Element (Core Data Model)

`Element` is the universal structure for all visible items in a note/document.

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `uuid` | `string` | Unique identifier |
| `type` | `number` | Element type (see `ElementType` below) |
| `pageNum` | `number` | Page number |
| `layerNum` | `number` | Layer number (0–3 in notes, 0 in docs) |
| `thickness` | `number` | Pen thickness |
| `recognizeResult` | `RecogResultData` | Handwriting recognition result |
| `maxX` | `number` | EMR X-axis max |
| `maxY` | `number` | EMR Y-axis max |
| `angles` | `ElementDataAccessor<Point>` | Angle points (accessor, not array) |
| `status` | `number` | Element status |
| `userData` | `string` | Custom user-defined data field |
| `numInPage` | `number` | Index within page (from 0) |
| `contoursSrc` | `ElementDataAccessor<Point[]>` | Contour points in pixel coords (accessor) |
| `stroke` | `Stroke \| null` | Stroke data (type=0 only) |
| `title` | `Title \| null` | Title data (type=100 only) |
| `textBox` | `TextBox \| null` | Text box data (type=500/501/502) |
| `geometry` | `Geometry \| null` | Geometry data (type=700 only) |
| `link` | `Link \| null` | Link data (type=600 only) |
| `fiveStar` | `FiveStar \| null` | Five-star data (type=800 only) |
| `picture` | `Picture \| null` | Picture data (type=200 only) |

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `recycle()` | `Promise<void>` | Free native-side cache. **Always call when done.** |

### ElementType Constants

```ts
import { ElementType } from 'sn-plugin-lib';
```

| Constant | Value | Description | Sub-field | Layer restriction |
|----------|-------|-------------|-----------|-------------------|
| `TYPE_STROKE` | `0` | Handwriting stroke | `stroke` | Main + custom layers |
| `TYPE_TITLE` | `100` | Title | `title` | **Main layer only** |
| `TYPE_PICTURE` | `200` | Image | `picture` | Main + custom layers |
| `TYPE_TEXT` | `500` | Text box | `textBox` | Main + custom layers |
| `TYPE_TEXT_DIGEST_QUOTE` | `501` | Quote excerpt text box | `textBox` | **Main layer only** |
| `TYPE_TEXT_DIGEST_CREATE` | `502` | Created excerpt text box | `textBox` | **Main layer only** |
| `TYPE_LINK` | `600` | Link | `link` | **Main layer only** |
| `TYPE_GEO` | `700` | Geometry shape | `geometry` | Main + custom layers |
| `TYPE_FIVE_STAR` | `800` | Five-star | `fiveStar` | **Main layer only** |

---

## ElementDataAccessor\<T\>

Lazy accessor for large data (stroke points, angles, contours). Data lives on the native side.

**⚠️ NOT an array. Do not call `.map()`, `.length`, or `.forEach()` on it.**

| Method | Returns | Description |
|--------|---------|-------------|
| `size()` | `Promise<number>` | Total number of items |
| `get(index: number)` | `Promise<T \| null>` | Get single item by index |
| `getRange(start, count)` | `Promise<T[]>` | Get `count` items starting at `start` |
| `add(index, value)` | `Promise<boolean>` | Insert item at position (clears cache) |
| `set(index, value)` | `Promise<boolean>` | Overwrite item at index (clears cache) |
| `setRange(index, endIndex, valueArray)` | `Promise<boolean>` | Overwrite range (clears cache) |
| `clearCache()` | `void` | Clear RN-side cache |
| `getCacheStats()` | `{cachedCount, totalSize}` | Cache statistics |
| `preload(startIndex, count)` | `Promise<void>` | Preload range into cache |
| `isCached(index)` | `boolean` | Whether item is cached |

```ts
// Example: read first 10 stroke points
const pointCount = await element.stroke.points.size();
const first10 = await element.stroke.points.getRange(0, Math.min(10, pointCount));
// Example: write stroke points
await element.stroke.points.setRange(0, points.length - 1, points);
```

### ElementPointDataType (enum)

| Enum | Value | Description |
|------|------:|-------------|
| `ANGLE_POINT` | `0` | angle points |
| `CONTOUR_POINT` | `1` | contour points |
| `STROKE_SAMPLE_POINT` | `2` | stroke sample points |
| `STROKE_PRESSURE_POINT` | `3` | stroke pressure points |
| `ERASE_LINE_DATA` | `4` | erase line data |
| `WRITE_FLAG` | `5` | write flag |
| `MARK_PEN_DIRECTION` | `6` | marker pen direction |
| `RECOGNITION_DATA_POINT` | `7` | recognition data points |

---

## Stroke

Available when `element.type === 0`.

| Field | Type | Description |
|-------|------|-------------|
| `points` | `ElementDataAccessor<Point>` | Stroke sample points (EMR coords) |
| `pressures` | `ElementDataAccessor<number>` | Pressure values per point (0–4096) |
| `penColor` | `number` | `0x00`=black, `0x9D`=dark gray, `0xC9`=light gray, `0xFE`=white |
| `penType` | `number` | `1`=pressure pen, `10`=technical pen, `11`=marker pen |
| `eraseLineTrailNums` | `ElementDataAccessor<number>` | Erase line data |
| `flagDraw` | `ElementDataAccessor<boolean>` | Write flag |
| `markPenDirection` | `ElementDataAccessor<Point>` | Marker pen direction |
| `recognPoints` | `ElementDataAccessor<RecognData>` | Recognition point data (pixel coords, for MyScript etc.) |

Note: `penWidth` is not a direct Stroke field; stroke width is controlled via `Element.thickness`.

---

## PenInfo

Returned by `PluginCommAPI.getPenInfo()`.

| Field | Type | Description |
|-------|------|-------------|
| `type` | `number` | Pen type: `1`=pressure, `10`=fineliner, `11`=marker, `14`=calligraphy |
| `color` | `number` | Pen color: `0x00`=black, `0x9D`=dark gray, `0xC9`=light gray, `0xFE`=white |
| `width` | `number` | Pen width (minimum `100`) |

---

## Title

Available when `element.type === 100`. **NOTE only, main layer only.**

```ts
class Title {
  X: number;              // Top-left X (pixel coords)
  Y: number;              // Top-left Y (pixel coords)
  width: number;          // Title width
  height: number;         // Title height
  page: number;           // Page number
  num: number;            // Index within page
  style: number;          // 0=remove, 1=black, 2=gray-white, 3=gray-black, 4=shadow
  controlTrailNums: number[];  // Associated stroke indices
}
```

---

## TextBox

Available when `element.type` is 500, 501, or 502.

| Field | Type | Description |
|-------|------|-------------|
| `textContentFull` | `string` | Full text content |
| `textRect` | `Rect` | Bounding rectangle (pixel coords) |
| `fontSize` | `number` | Font size |
| `fontPath` | `string` | Custom font file path (optional) |
| `textDigestData` | `string \| null` | Digest data (present on digest TextBox elements, type 501/502) |
| `textAlign` | `number` | `0`=left, `1`=center, `2`=right |
| `textBold` | `number` | `0`=normal, `1`=bold |
| `textItalics` | `number` | `0`=normal, `1`=italic |
| `textFrameWidthType` | `number` | `0`=fixed width, `1`=auto width |
| `textFrameStyle` | `number` | `0`=no border, `3`=stroke border |
| `textEditable` | `number` | `0`=editable, `1`=locked |

---

## Geometry

Available when `element.type === 700`.

| Field | Type | Description |
|-------|------|-------------|
| `showLassoAfterInsert` | `boolean` | Only effective in `insertGeometry`. When true, the inserted geometry is auto-selected with lasso after insertion. |
| `type` | `string` | Geometry type (see below) |
| `penColor` | `number` | Pen color |
| `penType` | `number` | Pen type |
| `penWidth` | `number` | Line thickness |
| `ellipseCenterPoint` | `Point` | Center (pixel coords) — for circle/ellipse |
| `ellipseMajorAxisRadius` | `number` | Major axis radius — for circle/ellipse |
| `ellipseMinorAxisRadius` | `number` | Minor axis radius — for circle/ellipse |
| `ellipseAngle` | `number` | Rotation angle in radians — for ellipse |
| `points` | `Point[]` | Vertex points (pixel coords) — for line/triangle/rect/polygon |

### Geometry Type Strings

| Type | Value | Key fields |
|------|-------|-----------|
| Straight line | `'straightLine'` | points (2 points) |
| Circle | `'GEO_circle'` | center, major=minor radius |
| Ellipse | `'GEO_ellipse'` | center, major/minor radius, angle |
| Polygon | `'GEO_polygon'` | points (N points; use for triangle, rect, etc.) |

Also available as constants: `Geometry.TYPE_STRAIGHT_LINE`, `Geometry.TYPE_CIRCLE`, `Geometry.TYPE_ELLIPSE`, `Geometry.TYPE_POLYGON`.

Pen color/type/width values are the same as `PenInfo` (see above).

---

## Link

Available when `element.type === 600`. **Main layer only.**

| Field | Type | Description |
|-------|------|-------------|
| `category` | `number` | `0`=text link, `1`=stroke link |
| `X` | `number` | Top-left X (pixel coords) |
| `Y` | `number` | Top-left Y (pixel coords) |
| `width` | `number` | Width (pixels) |
| `height` | `number` | Height (pixels) |
| `page` | `number` | Page number where link is located |
| `destPath` | `string` | Destination path (file path or URL) |
| `destPage` | `number` | Destination page number |
| `style` | `number` | `0`=solid underline, `1`=solid border, `2`=dashed border |
| `linkType` | `number` | `0`=note page, `1`=note file, `2`=document, `3`=image, `4`=URL, `6`=digest (read-only) |
| `fontSize` | `number` | Font size (text link) |
| `fullText` | `string` | Full link text (text link) |
| `showText` | `string` | Displayed link text (text link) |
| `italic` | `number` | `0`=normal, `1`=italic (text link) |
| `controlTrailNums` | `number[]` | Associated stroke indices (stroke link) |

### TextLink (for insertTextLink)

Extends Link fields with:

| Field | Type | Description |
|-------|------|-------------|
| `rect` | `Rect` | Display area (pixel coords) |
| `fontSize` | `number` | Font size |
| `fullText` | `string` | Full link text |
| `showText` | `string` | Displayed link text |
| `isItalic` | `number` | `0`=normal, `1`=italic |

### LassoLink (for modifyLassoLink)

| Field | Type | Description |
|-------|------|-------------|
| `category` | `number` | Link category (from `getLassoLinks` result) |
| `destPath` | `string` | Destination path |
| `destPage` | `number` | Destination page |
| `linkType` | `number` | Link type (0–4) |
| `style` | `number` | Display style |
| `showText` | `string` | Displayed text (text links only) |
| `fullText` | `string` | Full text (text links only) |
| `italic` | `number` | `0`=normal, `1`=italic |

---

## Picture

Available when `element.type === 200`.

| Field | Type | Description |
|-------|------|-------------|
| `picturePath` | `string` | Image file path |
| `rect` | `Rect` | Image rectangle (pixel coords) |

---

## FiveStar

Available when `element.type === 800`. **Main layer only.**

| Field | Type | Description |
|-------|------|-------------|
| `points` | `Point[]` | Five-star vertex coordinates (EMR coords when read from file; but `insertFiveStar()` accepts **pixel coords** and converts internally) |

---

## Layer

| Field | Type | Description |
|-------|------|-------------|
| `layerNum` | `number` | Layer number (0=main, 1-3=custom) |
| `name` | `string` | Layer display name |
| `visible` | `boolean` | Layer visibility |

---

## Template

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Template name (used in `createNote` and `insertNotePage`) |
| `path` | `string` | Template file path |

---

## KeyWord

| Field | Type | Description |
|-------|------|-------------|
| `keyword` | `string` | Keyword text |
| `page` | `number` | Associated page |

---

## RecogResultData

Handwriting recognition result.

| Field | Type | Description |
|-------|------|-------------|
| `data` | `RecognData[]` | Recognition candidates |

### RecognData

| Field | Type | Description |
|-------|------|-------------|
| `text` | `string` | Recognized text |
| `score` | `number` | Confidence score |

---

## PointUtils Constants

### Page Orientation

| Constant | Value | Description |
|----------|------:|-------------|
| `PointUtils.ROTATION_0` | `1000` | 0° portrait |
| `PointUtils.ROTATION_0_LR` | `2000` | 0° portrait, L/R split |
| `PointUtils.ROTATION_90` | `1090` | 90° landscape |
| `PointUtils.ROTATION_90_UD` | `2090` | 90° landscape, T/B split |
| `PointUtils.ROTATION_180` | `1180` | 180° portrait |
| `PointUtils.ROTATION_180_LR` | `2180` | 180° portrait, L/R split |
| `PointUtils.ROTATION_270` | `1270` | 270° landscape |
| `PointUtils.ROTATION_270_UD` | `2270` | 270° landscape, T/B split |

### Device Model (machineType)

| Constant | Value |
|----------|------:|
| `PointUtils.MACHINE_TYPE_A5` | `0` |
| `PointUtils.MACHINE_TYPE_A6` | `1` |
| `PointUtils.MACHINE_TYPE_A6X` | `2` |
| `PointUtils.MACHINE_TYPE_A5X` | `3` |
| `PointUtils.MACHINE_TYPE_NOMAD` | `4` |
| `PointUtils.MACHINE_TYPE_MANTA` | `5` |

### Page Size Constants

| Constant | Value |
|----------|-------|
| `PointUtils.NORMAL_PAGE_SIZE` | `{ width: 1404, height: 1872 }` |
| `PointUtils.A5X2_PAGE_SIZE` | `{ width: 1920, height: 2560 }` |