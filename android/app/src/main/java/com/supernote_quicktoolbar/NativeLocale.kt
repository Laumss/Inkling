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
        "long_screenshot_active" to ("✦ 长截图" to "✦ Stitch"),
        "add_to_history"         to ("添加到队列" to "Add to Queue"),
        "insert_next"            to ("下次插入" to "Insert Next"),
        "screenshot_to_note"     to ("添加进队列并跳转笔记" to "Queue + Note"),
        "multi"                  to ("连续" to "Multi"),
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
