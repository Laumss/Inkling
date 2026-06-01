# ui_common —— 悬浮工具箱控件库

`ui_common` 是所有 WindowManager 叠层面板共用的**控件库**。它的定位类似 STM32 的 HAL：
**模板定好之后，加新界面靠"填配置 + 拼积木"，而不是每个界面糊一个专用类。**

> 反面教材：`PanelActionBar`（已删除）—— 当初为裁切栏量身定做，换个形状就报废。
> 现在统一成 `PanelBar` 模板 + `Style` 预设，裁切栏和长截图栏都只是它的两份配置。

---

## 一、核心原则

1. **先复用，再配置，最后才新建。**
   - 已有控件能满足 → 直接用。
   - 形状一致、只是参数不同 → 给它加一个 `Style` 预设 / 构造参数，**不要新建类**。
   - 真的是一种全新的结构 → 才新建组件，且必须做成**参数化、与具体面板解耦**的。

2. **判断"专用 vs 通用"的标准：**
   一个组件的 API 如果是照着*某一个界面的具体内容*长出来的（按钮清单、字段名、业务回调），它就是专用件，迟早报废。
   通用件的 API 只描述*结构和槽位*，业务逻辑通过回调/参数传入。

3. **不留死代码。** 没有调用方的变体（如曾经的 `PanelTabBar.Tab.Text`）要删掉，不要"先留着以后可能用"。

---

## 二、两套尺寸管线（`ScreenScale`）

UI 按 1920×2560（短边 1920）设计。两种缩放方式，**别混用**：

| 管线 | API | 用途 |
|---|---|---|
| **比例 dp** | `ScreenScale.factor(ctx)` → 乘进 `v * density * factor` | 标准面板内容。小屏按比例缩小，占同样的视觉比例。`PanelBase.dp()/sp()` 走这条。 |
| **绝对像素** | `ScreenScale.px(ctx, n)` / `textPx(ctx, n)` | 1:1 复刻 Ratta 系统 chrome（如 Inbox 黑色顶栏）。`n` 直接填 Ratta `explorer_*_px_N` 的物理像素值，跨分辨率保持同一物理尺寸。 |

`PanelBar.Style.absolutePx` 就是用来在这两条管线间切换的。

---

## 三、组件清单

### 基础设施（非控件）

| 文件 | 作用 |
|---|---|
| `PanelBase` | 所有面板的基类。标准居中面板（`buildContent`）或全屏面板（`buildFullScreenContent`）。提供 `dp/sp`、窗口生命周期、`makeOutlinedBtn/makeFilledBtn/makeBottomBar/makeDivider/makeEmptyView` 等共享 UI 辅助。**旋转策略**也在这里：`closeOnRotation`（默认 `!fullScreen`）+ `onRotation()`，由 `ToolRegistry.handleRotation()` 统一分发，各面板自己声明，不再硬编码名单。详见根目录 `CLAUDE.md`。 |
| `ScreenScale` | 上面那两套尺寸管线。 |
| `UiUtils` | `loadAssetIcon(...)`：把 assets/icons 下的矢量 XML 渲染成 Bitmap（PluginHost 沙箱拿不到 R.drawable）。 |

### 顶栏

全屏叠层（裁切、长截图）的黑色顶栏走 `PanelBar`；标准弹窗面板的白色标题栏走 `PanelHeader`。两者场景不同，各管一类，**不合并**。

| 组件 | 作用 | 关键 API |
|---|---|---|
| **`PanelBar`** | **全屏黑色顶栏模板**。槽位 `left / center / right` + 原子 `Cell`（`TextBtn` / `Action` / `Check`）+ `Style` 预设。 | `PanelBar.build(ctx, style, left, center, right)` → `Handle(view, heightPx, setChecked)` |
| `PanelHeader` | 白底加粗居中标题栏 + 底部分隔线，标准弹窗面板用。 | `PanelHeader.create(ctx, title, onClose?)` |

`PanelBar` 现有预设：
- `Style.INBOX`：黑底、绝对像素、复刻 Ratta Inbox 编辑栏。目前**两个黑栏都用它**（裁切、长截图）。

**右槽约定（固定可复用）**：右侧 `right` 槽要么放一个 `Check`（多选/连续模式的复选框），要么放一个确认 `Action`（单确认流程的「确认」按钮）——两者通过同一套 `Cell` 复用，不要手糊。

```kotlin
// 裁切栏（footer 流程）：中间多动作 + 右侧复选框
val bar = PanelBar.build(
    ctx, PanelBar.Style.INBOX,
    left   = listOf(PanelBar.TextBtn(t("cancel")) { close() }),
    center = listOf(PanelBar.Action("icons/ic_edit_stitch.xml", t("long_screenshot")) { … }, …),
    right  = listOf(PanelBar.Check(t("multi")) { multi = !multi; bar.setChecked(multi) })
)

// 长截图 / 单确认流程：右侧放确认按钮
val bar = PanelBar.build(
    ctx, PanelBar.Style.INBOX,
    left  = listOf(PanelBar.TextBtn(t("cancel")) { cancel() }),
    right = listOf(PanelBar.Action("icons/ic_edit_confirm.xml", t("confirm")) { confirm() })
)
```

### 导航 / 筛选

| 组件 | 作用 | 关键 API |
|---|---|---|
| `PanelTabBar` | 图标 Tab 条 + 选中下划线 + 中缝分隔线。 | `PanelTabBar(ctx, listOf(Tab.Icon(asset, desc), …)) { idx -> }` → `createView()` / `setSelection(i)` |
| `PanelChips` | 横向胶囊筛选条（Ratta `search_tag` 规格）。 | `PanelChips(ctx, keys) { selected -> }` → `createView()` / `setSelection(k)` / `rebuildChips()` |

### 内容容器 / 布局

| 组件 | 作用 | 关键 API |
|---|---|---|
| `PanelScrollHost` | 滚动容器 + Ratta 风格可拖拽滚动条（独立 lane，不遮内容；无溢出时自动收起）。 | `PanelScrollHost(ctx, overlayScrollbar?)` → `.view` / `.content` / `availableContentWidth(panelW)` / `scrollToTop()` |
| **`PanelGrid`** | **响应式等宽缩略图网格**。短边 ≥1920 → 3 列，否则 2 列。负责分行、列间距、末行补位。 | `PanelGrid.build(ctx, host, screenW, panelW, items) { item, colW -> cellView }` |

```kotlin
// 网格：只管"怎么画一个单元格"，列数/列宽/分行交给模板
PanelGrid.build(reactContext, scrollHost, screenW, winW, items) { item, colW ->
    createGridCell(item, colW)   // 返回宽度为 colW 的单元格 View
}
```

### 独立小控件

| 组件 | 作用 |
|---|---|
| `SelectionButton` | 包一个 TextView，按"是否有选中"切换 enabled/alpha 态。`update(Boolean)`。 |
| `PanelCheckbox` | 带标签的复选框行（底栏「多选」开关）。`view` 作 `makeBottomBar` 的 `leftFlex`；`setChecked(b)` / `setLabel(s)` / `setActive(b)`。 |
| `FolderCoverView` | Inbox 风格文件夹封面（缺口外框 + 2×2 缩略图）。`setupChildSlots(w,h)` / `setChildBitmap(i, bmp)`。 |

---

## 四、标准弹窗「三明治」

标准居中弹窗面板都是同一套三明治，三层各用一个 ui_common 件，在 `buildContent()` 里堆起来：

| 层 | 用件 |
|---|---|
| 🍞 顶（标题栏） | `PanelHeader.create()` |
| 🥬 中（滚动内容） | `PanelScrollHost`（内容可再套 `PanelGrid` / `PanelChips` / `PanelTabBar`）|
| 🍞 底（动作栏） | `PanelBase.makeBottomBar(leftButtons?, leftFlex?, rightButtons)` |

底座是 `PanelBase`。新建标准面板就照这个三层拼，不要自己写裸 `ScrollView` 或手糊底栏。

---

## 五、谁在用（现状）

| 组件 | 调用方 |
|---|---|
| `PanelBar` | CropPanel、StitchPanel |
| `PanelHeader` | ImagePanel、SendPanel、DocScreenshotPanel、DocLinkPanel |
| `PanelTabBar` | ImagePanel、DocScreenshotPanel |
| `PanelChips` | ImagePanel、DocLinkPanel |
| `PanelScrollHost` | ImagePanel、DocScreenshotPanel、DocLinkPanel、SendPanel |
| `PanelGrid` | ImagePanel、DocScreenshotPanel |
| `PanelCheckbox` | ImagePanel、DocLinkPanel |
| `SelectionButton` | ImagePanel、DocScreenshotPanel、DocLinkPanel |
| `FolderCoverView` | ImagePanel |

---

## 六、加新东西的准则

- 新增顶栏样式：**加 `PanelBar.Style` 预设**，不要新建顶栏类。
- 新增标准面板：照「三明治」三层拼现成件。
- 黑栏右槽只有两种内容：`Check` 或确认 `Action`——复用，别另起炉灶。
- `PanelHeader`（白标题栏）和 `PanelBar`（黑全屏栏）是两类场景，**保持分开，不合并**。
