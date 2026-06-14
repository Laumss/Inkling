package com.supernote_quicktoolbar

import java.util.Locale

object NativeLocale {

    private val isZh: Boolean by lazy {
        Locale.getDefault().language.startsWith("zh")
    }

    private val STRINGS = mapOf(

        "image_panel_title"  to ("插入图片" to "Insert Image"),
        "tab_received"       to ("已接收" to "Received"),
        "tab_browse"         to ("浏览" to "Browse"),
        "dir_inbox"          to ("收件箱" to "Inbox"),
        "dir_mystyle"        to ("模板" to "MyStyle"),
        "dir_document"       to ("文档" to "Document"),
        "dir_screenshot"     to ("截图" to "Screenshot"),
        "dir_export"         to ("导出" to "Export"),
        "cropper_title"      to ("裁剪图片" to "Crop Image"),
        "insert_original"    to ("插入原图" to "Insert Original"),
        "crop_and_insert"    to ("裁剪并插入" to "Crop & Insert"),
        "cancel"             to ("取消" to "Cancel"),
        "back"               to ("返回" to "Back"),
        "no_images"          to ("此目录没有图片文件" to "No image files in this directory"),
        "no_received"        to ("还没有接收到图片文件\n从其他设备通过 LocalSend 发送图片即可在此查看"
                                 to "No images received yet\nSend images from another device via LocalSend"),

        "doc_panel_title"    to ("插入文档链接" to "Insert Doc Link"),
        "doc_insert_link"    to ("链接文档" to "Insert Link"),
        "doc_no_files"       to ("此目录没有文档文件" to "No document files in this directory"),
        "doc_dir_localsend"  to ("LocalSend" to "LocalSend"),
        "doc_dir_download"   to ("下载" to "Download"),

        "send_title"         to ("发送到设备" to "Send to Device"),
        "peers_scanning"     to ("正在扫描局域网设备..." to "Scanning LAN peers..."),
        "peers_none"         to ("未发现设备。请确保对方已打开 LocalSend。"
                                 to "No peers found. Make sure LocalSend is open on the other device."),
        "send_text_btn"      to ("发送文本" to "Send Text"),
        "send_files_btn"     to ("发送文件" to "Send Files"),
        "sending"            to ("发送中..." to "Sending..."),
        "send_success"       to ("发送成功" to "Sent successfully"),
        "send_failed"        to ("发送失败" to "Send failed"),
        "sync_clipboard_btn" to ("同步剪贴板" to "Sync Clipboard"),
        "sync_packaging"     to ("正在打包剪贴板..." to "Packaging clipboard..."),
        "sync_clipboard_empty" to ("剪贴板为空" to "Clipboard is empty"),
        "sync_clipboard_ask" to ("是否接受来自 %s 的剪贴板？" to "Accept clipboard from %s?"),
        "sync_clipboard_ok"  to ("剪贴板已同步" to "Clipboard synced"),
        "sync_waiting"       to ("等待对方确认…" to "Waiting for confirmation…"),
        "sync_rejected"      to ("对方拒绝了同步请求" to "Sync request rejected"),
        "extracting"         to ("正在提取套索内容..." to "Extracting lasso content..."),
        "rescan"             to ("重新扫描" to "Rescan"),
        "close"              to ("关闭" to "Close"),

        "screenshot_panel_title" to ("文档截图" to "Doc Screenshots"),
        "tab_queue"              to ("待插入" to "Queue"),
        "tab_history"            to ("历史" to "History"),
        "no_queue"               to ("没有待插入的截图\n在文档中使用截图裁切功能添加" to "No queued screenshots\nUse screenshot crop in DOC to add"),
        "no_history"             to ("没有历史截图" to "No history screenshots"),
        "insert"                 to ("插入" to "Insert"),
        "delete"                 to ("删除" to "Delete"),

        "confirm"                to ("确认" to "Confirm"),
        "lasso_clear"            to ("清除" to "Clear"),
        "lasso_hint"             to ("在要发送的内容外画一个闭合圈" to "Draw a closed shape around the content"),

        "multi_select"           to ("选择多项" to "Select Multiple"),

        "long_screenshot"        to ("长截图" to "Stitch"),
        "long_screenshot_active" to ("长截图" to "✦ Stitch"),
        "add_to_history"         to ("添加到队列" to "Add to Queue"),
        "insert_next"            to ("下次插入" to "Insert Next"),
        "screenshot_to_note"     to ("保存到笔记" to "Queue + Note"),
        "multi"                  to ("多" to "Multi"),
        "compositing"            to ("合成中..." to "Compositing…"),

        "screenshot_bubble"      to ("截图" to "Snip"),
        "screencap_failed"       to ("截图失败\n\n请手动按 电源+音量下 截图，\n然后重新打开插件。"
                                     to "Could not capture screenshot.\n\nPlease press Power + Volume Down\nto take a screenshot manually,\nthen reopen the plugin."),

        "stitch_waiting"         to ("等待第二张截图..." to "Waiting for second image…"),
        "stitch_waiting_hint"    to ("翻到下一页，然后再按一次截图按钮。"
                                     to "Flip the page, then press the DOC button again."),

        "page_indicator"         to ("%d / %d" to "%d / %d"),

        "no_wifi"                to ("未连接 WiFi" to "WiFi is not connected"),
        "localsend_ask_enable"   to ("LocalSend 未开启，是否立即开启？" to "LocalSend is not running. Start it now?"),
        "btn_cancel"             to ("取消" to "Cancel"),
        "btn_confirm"            to ("确定" to "OK"),

        "config_tools"           to ("工具列表" to "Tools"),
        "config_add"             to ("+ 添加" to "+ Add"),
        "config_add_title"       to ("添加工具" to "Add Tool"),
        "config_done"            to ("完成" to "Done"),
        "config_save"            to ("保存" to "Save"),
        "config_collapse"        to ("收起" to "Collapse"),
        "config_dock"            to ("贴边隐藏" to "Dock"),
        "config_empty"           to ("工具列表为空" to "No tools added"),
        "config_empty_hint"      to ("请点击「+ 添加」添加工具" to "Tap \"+ Add\" to add tools"),
        "config_selected_count"  to ("已选 %d 个" to "%d selected"),
        "config_cat_all"         to ("全部" to "All"),
        "config_cat_insert"      to ("插入" to "Insert"),
        "config_cat_text"        to ("文本" to "Text"),
        "config_cat_lasso"       to ("套索" to "Lasso"),
        "config_tool_image"      to ("插入图片" to "Insert Image"),
        "config_tool_doc"        to ("文档截图" to "Doc Screenshot"),
        "config_tool_text"       to ("文本接收" to "Text Receive"),
        "config_tool_lasso_ai"   to ("套索发送" to "Lasso Send"),
        "config_tool_link"       to ("链接文档" to "Insert Link"),
        "config_tool_voice"      to ("接收 AI" to "AI Receive"),
        "config_tool_palette"    to ("Palette" to "Palette"),

        "palette_title"          to ("Palette" to "Palette"),
        "palette_presets"        to ("笔槽" to "Presets"),
        "palette_color"          to ("颜色" to "Color"),
        "palette_thickness"      to ("粗细" to "Thickness"),
        "palette_pen_type"       to ("笔型" to "Pen Type"),
        "palette_apply"          to ("应用" to "Apply"),
        "palette_marker_note"    to ("马克笔颜色固定，无需选择" to "Marker color is fixed"),
        "palette_add_col"        to ("＋ 增加一列" to "＋ Add Column"),
        "palette_remove_col"     to ("－ 减少一列" to "－ Remove Column"),
        "palette_delete_confirm" to ("确定删除最后 %d 个预设槽位？" to "Delete last %d preset slots?"),
        "palette_reset"          to ("重置" to "Reset"),
        "palette_slot_info"      to ("%d 列 · %d 槽位" to "%d cols · %d slots"),
        "palette_tier_thin"      to ("细" to "Thin"),
        "palette_tier_medium"    to ("中" to "Med"),
        "palette_tier_thick"     to ("粗" to "Thick"),
        "palette_product_color"  to ("产品色" to "Product"),
        "palette_ext_color"      to ("扩展色板" to "Extended"),
        "palette_ext_bright"     to ("亮调" to "Bright"),
        "palette_marker_black"   to ("马克·黑" to "Mk·Black"),
        "palette_marker_gray"    to ("马克·灰" to "Mk·Gray"),
        "palette_marker_white"   to ("马克·白" to "Mk·White"),
        "palette_marker_note2"   to ("荧光笔颜色随笔型固定" to "Marker color is fixed by pen type"),
        "palette_header_title"   to ("笔槽设置" to "Pen Slot Settings"),
        "palette_header_editing" to ("编辑中" to "Editing"),
        "palette_header_new"     to ("新建笔" to "New Pen"),
        "palette_slots_used"     to ("已用" to "Used"),
        "palette_color_fixed"    to ("固定" to "Fixed"),
        "palette_color_count"    to ("%d 色" to "%d colors"),
        "palette_tier_label"     to ("%s 档" to "%s tier"),
        "palette_stepper_hint"   to ("微调当前档" to "Fine-tune tier"),
        "palette_marker_sub"     to ("荧光笔 · 颜色固定" to "Marker · Fixed color"),

        "pen_needle"             to ("针管笔" to "Needle"),
        "pen_ball"               to ("墨水笔" to "Ink Pen"),
        "pen_calligraphy"        to ("书法笔" to "Brush"),
        "pen_marker"             to ("马克笔" to "Marker"),
        "color_black"            to ("黑色" to "Black"),
        "color_dark_gray"        to ("深灰" to "Dark Gray"),
        "color_light_gray"       to ("浅灰" to "Light Gray"),
        "color_ghost"            to ("白色" to "White"),
        "color_blue"             to ("蓝" to "Blue"),
        "color_red"              to ("红" to "Red"),
        "color_pink"             to ("粉" to "Pink"),
        "color_orange"           to ("橙" to "Orange"),
        "color_green"            to ("绿" to "Green"),
        "color_cyan"             to ("青" to "Cyan"),
        "color_lime"             to ("柠" to "Lime"),
        "color_purple"           to ("紫" to "Purple"),
        "horizontal"             to ("横向" to "Horizontal"),
        "vertical"               to ("纵向" to "Vertical"),
        "opacity"                to ("透明度" to "Opacity"),
    )

    fun t(key: String): String {
        val pair = STRINGS[key] ?: return key
        return if (isZh) pair.first else pair.second
    }

    fun t(key: String, vararg args: Any): String {
        val pair = STRINGS[key] ?: return key
        val template = if (isZh) pair.first else pair.second
        return String.format(template, *args)
    }

    fun itemCount(count: Int): String {
        return if (isZh) "共${count}项" else "$count items"
    }
}
