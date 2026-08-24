package com.supernote_quicktoolbar

import java.util.Locale

object NativeLocale {

    @Volatile
    private var localeOverride: String? = null

    private val isZh: Boolean
        get() {
            val override = localeOverride
            if (override != null) return override.startsWith("zh")
            return Locale.getDefault().language.startsWith("zh")
        }

    fun setLocale(loc: String) {
        localeOverride = loc.lowercase()
    }

    private val STRINGS = mapOf(

        "airrelay_pair_request" to ("发现 %s（%s）的 AI 服务，是否连接？" to "Found AI server %s (%s). Connect?"),
        "airrelay_auth_unsupported" to ("%s 开启了身份验证，AIRelay 无法连接。请在该设备的 Web 服务器设置中关闭「启用身份验证」。" to "%s has authentication enabled; AIRelay can't connect. Turn off \"Enable authentication\" in its web server settings."),

        "image_panel_title"  to ("插入图片" to "Insert Image"),
        "cropper_title"      to ("裁剪图片" to "Crop Image"),
        "crop_and_insert"    to ("裁剪并插入" to "Crop & Insert"),
        "cancel"             to ("取消" to "Cancel"),
        "no_images"          to ("此目录没有图片文件" to "No image files in this directory"),
        "no_received"        to ("还没有接收到图片文件\n从其他设备通过 LocalSend 发送图片即可在此查看"
                                 to "No images received yet\nSend images from another device via LocalSend"),
        "file_read_permission_needed" to ("需要授予 Inkling 文件读取权限，请在弹窗中允许后重试"
                                 to "Inkling needs file read permission. Allow it in the dialog, then retry."),
        "file_write_permission_needed" to ("需要授予 Inkling 文件写入权限，请在弹窗中允许后重试"
                                  to "Inkling needs file write permission. Allow it in the dialog, then retry."),

        "doc_panel_title"    to ("插入文档链接" to "Insert Doc Link"),
        "doc_insert_link"    to ("链接文档" to "Insert Link"),
        "doc_no_files"       to ("此目录没有文档文件" to "No document files in this directory"),

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
        "image_send_rejected" to ("对方拒绝了图片接收" to "The receiver rejected the image"),
        "image_receive_ask" to ("检测到 %s 传来一张图片" to "An image is arriving from %s"),
        "image_receive_more" to ("其他选项" to "More options"),
        "image_receive_insert_now" to ("直接插入" to "Insert now"),
        "image_receive_keep_ask" to ("接收图片后保留在收件箱，便于后续手动插入" to "Keep the image in Inbox for manual insertion"),
        "image_receive_reject" to ("拒绝接收" to "Reject"),
        "extracting"         to ("正在提取套索内容..." to "Extracting lasso content..."),
        "rescan"             to ("重新扫描" to "Rescan"),

        "screenshot_panel_title" to ("文档截图" to "Doc Screenshots"),
        "no_queue"               to ("没有待插入的截图\n在文档中使用截图裁切功能添加" to "No queued screenshots\nUse screenshot crop in DOC to add"),
        "no_history"             to ("没有历史截图" to "No history screenshots"),
        "insert"                 to ("插入" to "Insert"),
        "delete"                 to ("删除" to "Delete"),

        "confirm"                to ("确认" to "Confirm"),
        "rotation_sync_message"  to ("请完成屏幕旋转，确认页面和悬浮按钮位置稳定后点击“旋转完成”。" to "Finish rotating the screen. When the page and floating controls are stable, tap Rotation complete."),
        "rotation_sync_wait"     to ("继续等待" to "Keep waiting"),
        "rotation_sync_done"     to ("旋转完成" to "Rotation complete"),

        "multi_select"           to ("选择多项" to "Select Multiple"),

        "long_screenshot"        to ("长截图" to "Stitch"),
        "long_screenshot_active" to ("长截图" to "✦ Stitch"),
        "add_to_history"         to ("添加到队列" to "Add to Queue"),
        "added_to_doc_screenshots" to ("已添加到文档截图" to "Added to Doc Screenshots"),
        "send_to_other_devices"  to ("发送到其他设备" to "Send to Other Devices"),
        "insert_next"            to ("下次插入" to "Insert Next"),
        "multi"                  to ("多" to "Multi"),

        "paste_image"            to ("贴图" to "Pin"),
        "sticky_limit_reached"   to ("最多同时贴 5 张\n点按屏幕上的贴图可将其关闭"
                                     to "Up to 5 pinned images at a time.\nTap a pinned image to remove it."),
        "screencap_failed"       to ("截图失败\n\n请手动按 电源+音量下 截图，\n然后重新打开插件。"
                                     to "Could not capture screenshot.\n\nPlease press Power + Volume Down\nto take a screenshot manually,\nthen reopen the plugin."),
        "screenshot_save_failed" to ("截图保存失败，请检查文件权限后重试"
                                     to "Could not save the screenshot. Check file permissions and retry."),
        "screenshot_panel_failed" to ("无法打开截图面板，请检查悬浮窗权限后重试"
                                      to "Could not open the screenshot panel. Check overlay permission and retry."),

        "stitch_waiting"         to ("等待第二张截图..." to "Waiting for second image…"),
        "stitch_waiting_hint"    to ("翻到下一页，然后再按一次截图按钮。"
                                     to "Flip the page, then press the DOC button again."),

        "no_wifi"                to ("未连接 WiFi" to "WiFi is not connected"),
        "peer_default_badge"     to ("默认" to "Default"),
        "config_default_peer"    to ("默认发送设备" to "Default send device"),
        "config_default_peer_none" to ("未绑定（在发送面板长按设备可绑定）" to "Not bound (long-press a device in the send panel)"),
        "config_default_peer_unbind" to ("解绑" to "Unbind"),
        "config_airrelay_phone"  to ("AIRelay 手机" to "AIRelay phone"),
        "config_airrelay_none"   to ("未绑定（发现 RikkaHub 时会询问）" to "Not paired (you will be asked when RikkaHub is found)"),
        "config_airrelay_unbind" to ("解除绑定" to "Unpair"),
        "btn_cancel"             to ("取消" to "Cancel"),
        "btn_confirm"            to ("确定" to "OK"),
        "text_recv_closed"       to ("检测到文本发送，请先打开文本接收悬浮窗。" to "Text send detected. Please open the text receive bubble first."),

        "config_tools"           to ("工具列表" to "Tools"),
        "config_add"             to ("+ 添加" to "+ Add"),
        "config_add_title"       to ("添加工具" to "Add Tool"),
        "config_done"            to ("完成" to "Done"),
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

        "palette_presets"        to ("笔槽" to "Presets"),
        "palette_color"          to ("颜色" to "Color"),
        "palette_thickness"      to ("粗细" to "Thickness"),
        "palette_pen_type"       to ("笔型" to "Pen Type"),
        "palette_apply"          to ("应用" to "Apply"),
        "palette_add_col"        to ("＋ 增加一列" to "＋ Add Column"),
        "palette_remove_col"     to ("－ 减少一列" to "－ Remove Column"),
        "palette_delete_confirm" to ("确定删除最后 %d 个预设槽位？" to "Delete last %d preset slots?"),
        "palette_reset"          to ("重置" to "Reset"),
        "palette_tier_thin"      to ("细" to "Thin"),
        "palette_tier_thick"     to ("粗" to "Thick"),
        "palette_marker_black"   to ("马克·黑" to "Mk·Black"),
        "palette_marker_gray"    to ("马克·灰" to "Mk·Gray"),
        "palette_marker_white"   to ("马克·白" to "Mk·White"),
        "palette_marker_note2"   to ("荧光笔颜色随笔型固定" to "Marker color is fixed by pen type"),
        "palette_header_title"   to ("笔槽设置" to "Pen Slot Settings"),
        "palette_header_new"     to ("新建笔" to "New Pen"),

        "palette_snapshot_header"          to ("还原点" to "Restore Points"),
        "palette_snapshot_create"          to ("创建" to "Create"),
        "palette_snapshot_empty"           to ("暂无还原点，点「创建」保存当前页快照" to "No restore points. Tap Create to snapshot this page"),
        "palette_snapshot_restore_confirm" to ("将该还原点粘贴到当前页？" to "Paste this restore point onto the current page?"),
        "palette_snapshot_delete_confirm"  to ("删除该还原点？" to "Delete this restore point?"),

        "pen_needle"             to ("针管笔" to "Needle"),
        "pen_ball"               to ("墨水笔" to "Ink Pen"),
        "pen_calligraphy"        to ("书法笔" to "Brush"),
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
        "filter"                 to ("滤镜" to "Filter"),
        "filter_original"        to ("原图" to "Original"),
        "filter_enhance"         to ("文档增强" to "Enhance"),
        "filter_text_bw"         to ("黑白文本" to "B&W Text"),
        "density"                to ("浓度" to "Density"),

        "perm_required"          to ("需要权限" to "Permission Required"),
        "perm_desc_internet"     to ("Inkling 需要访问局域网以提供 LocalSend 发送与接收"
                                     to "Inkling needs LAN access for LocalSend send and receive"),
        "perm_desc_file_write"   to ("Inkling 需要写入存储以保存剪藏与配置"
                                     to "Inkling needs storage write access to save clips and settings"),
        "perm_desc_file_delete"  to ("Inkling 需要删除存储中的临时导出文件"
                                     to "Inkling needs storage delete access for temporary export files"),
        "perm_desc_file_read"    to ("Inkling 需要读取笔记与文档以提供快捷工具"
                                     to "Inkling needs read access to notes and documents for the toolbar"),

        "relay_edit"             to ("编辑" to "Edit"),
        "relay_readd"            to ("重新加入" to "Re-add"),
        "relay_delete"           to ("删除" to "Delete"),
        "relay_clear"            to ("清除" to "Clear"),
        "relay_insert"           to ("插入" to "Insert"),
        "relay_selected_items"   to ("%d 项" to "%d items"),
        "relay_selected_rows"    to ("%d 行" to "%d rows"),
        "relay_empty"            to ("暂无 AIRelay 消息" to "No AIRelay messages"),
        "relay_sent_to_note"     to ("已写入笔记" to "Sent to note"),
        "relay_source_ai"        to ("AI" to "AI"),
        "relay_source_manual"    to ("手动" to "Manual"),
        "relay_source_dictation" to ("听写" to "Dictation"),
        "relay_msg_unavailable"  to ("消息不可用" to "Message unavailable"),
        "relay_no_message"       to ("没有消息" to "No message"),
        "relay_select"           to ("选择" to "Select"),
        "relay_replace_handwriting" to ("替换手写" to "Replace handwriting"),
        "relay_image_alt"        to ("[图片: %s]" to "[Image: %s]"),

        "stitch_grid"            to ("⊞ 网格" to "⊞ Grid"),
        "stitch_overlap"         to ("重叠: %dpx" to "Overlap: %dpx"),
        "stitch_images"          to ("%d 张" to "%d images"),
        "stitch_strip"           to ("条带" to "Strip"),
        "stitch_swap"            to ("交换" to "Swap"),
        "stitch_swap_last"       to ("交换末张" to "Swap last"),
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
