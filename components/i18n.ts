import { NativeModules, Platform, DeviceEventEmitter } from 'react-native';

export type Locale = 'zh' | 'en';

const STRINGS = {

  app_title:              { zh: '快捷工具栏',   en: 'QuickToolbar' },
  add_tool:               { zh: '+ 添加',       en: '+ Add' },
  save_config:            { zh: '保存配置',     en: 'Save Config' },
  show_toolbar:           { zh: '显示工具栏',   en: 'Show Toolbar' },
  back:                   { zh: '返回',         en: 'Back' },
  close:                  { zh: '关闭',         en: 'Close' },
  added:                  { zh: '已添加',       en: 'added' },
  config_saved:           { zh: '配置已保存（{n} 个按钮）', en: 'Config saved ({n} buttons)' },
  add_tool_title:         { zh: '添加工具',     en: 'Add Tool' },
  hint_clipboard:         { zh: '套索选中后点击 Clip 按钮保存。工具栏中点击粘贴，长按清除。', en: 'Lasso select + tap Clip button to save. Tap in toolbar to paste. Long press to clear.' },
  tools_count_hint:       { zh: '当前 {n} 个按钮。至少需要 4 个才能保存。', en: '{n} buttons. Need at least 4 to save.' },
  tools_count_ok:         { zh: '当前 {n} 个按钮，可以保存。', en: '{n} buttons, ready to save.' },
  config_save_disabled:   { zh: '按钮数量不符合布局要求', en: 'Button count doesn\'t match layout' },

  perm_required:          { zh: '需要权限',     en: 'Permission Required' },
  perm_text:              { zh: '快捷工具栏需要"显示在其他应用上层"权限来显示悬浮工具栏。', en: 'QuickToolbar needs "Display over other apps" permission to show the floating toolbar.' },
  perm_hint:              { zh: '应用包名：com.ratta.supernote.pluginhost', en: 'Package: com.ratta.supernote.pluginhost' },
  perm_open_settings:     { zh: '打开设置',     en: 'Open Settings' },
  perm_recheck:           { zh: '重新检查',     en: 'Re-check' },
  perm_check_settings:    { zh: '请在系统设置中授权', en: 'Check system settings' },
  perm_granted:           { zh: '权限已授予',   en: 'Permission granted' },
  perm_not_granted:       { zh: '尚未授权',     en: 'Not yet granted' },

  tool_layer_prev:        { zh: '上一图层',     en: 'Layer Up' },
  tool_layer_next:        { zh: '下一图层',     en: 'Layer Down' },
  tool_insert_image:      { zh: '插入图片',     en: 'Insert Image' },
  tool_insert_doc_screenshot: { zh: '文档截图', en: 'Doc Screenshot' },
  tool_insert_link:       { zh: '插入链接',   en: 'Insert Link' },
  tool_clip:              { zh: '剪贴板 {n}',   en: 'Clip {n}' },
  tool_clip_paste:        { zh: '粘贴 {n}',     en: 'Paste {n}' },
  tool_text_nospacing:    { zh: '文本(无间距)', en: 'Text (No Gap)' },
  tool_text_paragraph:    { zh: '文本(段落)',   en: 'Text (Paragraph)' },
  tool_lasso_send:        { zh: '发送选区',     en: 'Send Lasso' },
  tool_lasso_ai:          { zh: '发给 AI',      en: 'Send to AI' },

  image_panel_title:      { zh: '插入图片',     en: 'Insert Image' },
  dir_inbox:              { zh: '收件箱',       en: 'Inbox' },
  dir_mystyle:            { zh: '模板',         en: 'MyStyle' },
  dir_document:           { zh: '文档',         en: 'Document' },
  dir_screenshot:         { zh: '截图',         en: 'Screenshot' },
  dir_export:             { zh: '导出',         en: 'Export' },
  tab_received:           { zh: '已接收',       en: 'Received' },
  tab_browse:             { zh: '浏览',         en: 'Browse' },

  cropper_title:          { zh: '裁剪图片',     en: 'Crop Image' },
  insert_original:        { zh: '插入原图',     en: 'Insert Original' },
  crop_and_insert:        { zh: '裁剪并插入',   en: 'Crop & Insert' },
  cancel:                 { zh: '取消',         en: 'Cancel' },

  send_title:             { zh: '发送到设备',   en: 'Send to Device' },
  peers_scanning:         { zh: '正在扫描局域网设备...', en: 'Scanning LAN peers...' },
  peers_none:             { zh: '未发现设备。请确保对方已打开 LocalSend。', en: 'No peers found. Make sure LocalSend is open on the other device.' },
  send_text_btn:          { zh: '发送文本',     en: 'Send Text' },
  send_files_btn:         { zh: '发送文件',     en: 'Send Files' },
  sending:                { zh: '发送中...',    en: 'Sending...' },
  send_success:           { zh: '发送成功',     en: 'Sent successfully' },
  send_failed:            { zh: '发送失败',     en: 'Send failed' },
  no_text_to_send:        { zh: '没有可发送的文本', en: 'No text to send' },
  no_files_to_send:       { zh: '没有可发送的文件', en: 'No files to send' },
  text_preview:           { zh: '文本预览',     en: 'Text Preview' },
  files_preview:          { zh: '待发送文件 ({n})', en: 'Files ({n})' },

  bubble_recv_nospacing:  { zh: '无间距接收中', en: 'Receiving (No Gap)' },
  bubble_recv_paragraph:  { zh: '段落接收中',   en: 'Receiving (Paragraph)' },
  bubble_ai_recognizing:  { zh: '识别中…',     en: 'Recognizing…' },
  bubble_ai_sending:      { zh: '发送中…',     en: 'Sending…' },
  bubble_ai_waiting:      { zh: '等待 AI 回复', en: 'Waiting for AI' },
  bubble_ai_no_lasso:     { zh: '请先套索选中文字', en: 'Select text with lasso first' },
  bubble_ai_ready:        { zh: '就绪，点 AI 套索发送', en: 'Ready · tap AI to send' },

  note_switched_stop:     { zh: '笔记已切换，文本接收已停止', en: 'Note switched, text receiving stopped' },
  pages_changed_stop:     { zh: '页面结构变更，文本接收已停止', en: 'Page structure changed, text receiving stopped' },

  tool_screenshot_ai:     { zh: '截图发 AI',  en: 'Screenshot AI' },
  tool_screenshot_send:   { zh: '截图发送',   en: 'Screenshot Send' },

  tool_toggle_spacing:    { zh: '切换间距',   en: 'Toggle Gap' },
  tool_send_ai:           { zh: '接收 AI',    en: 'AI Receive' },
  bubble_screenshot_todo: { zh: '截图功能开发中…', en: 'Screenshot coming soon…' },
  bubble_actions_title:   { zh: '气泡快捷按钮', en: 'Bubble Actions' },
  bubble_actions_hint:    { zh: '选择显示在文本接收气泡上的按钮', en: 'Choose buttons shown on the text bubble' },

  preview:                { zh: '实时预览',     en: 'Preview' },
  tool_list:              { zh: '工具列表',     en: 'Tools' },
  layout_label:           { zh: '布局',         en: 'Layout' },
  buttons_count:          { zh: '{n} 按钮',     en: '{n} buttons' },
  overflow_warn:          { zh: '前 {max} 个工具将显示在 {layout} 布局中，超出 {extra} 个不会显示',
                            en: 'First {max} tools shown in {layout} layout, {extra} hidden' },
  done:                   { zh: '完成',         en: 'Done' },
  selected_count:         { zh: '已选 {n} 个',  en: '{n} selected' },
  cat_all:                { zh: '全部',         en: 'All' },
  cat_layer:              { zh: '图层',         en: 'Layer' },
  cat_insert:             { zh: '插入',         en: 'Insert' },
  cat_text:               { zh: '文本',         en: 'Text' },
  cat_lasso:              { zh: '套索',         en: 'Lasso' },
  cat_clip:               { zh: '剪贴板',       en: 'Clipboard' },
  dock_left:              { zh: '← 左',         en: '← L' },
  dock_right:             { zh: '右 →',         en: 'R →' },
  empty_tools:            { zh: '工具列表为空',  en: 'No tools added' },
  empty_tools_hint:       { zh: '请点击「+ 添加」添加工具', en: 'Tap "+ Add" to add tools' },

  language:               { zh: '语言',         en: 'Language' },
  lang_zh:                { zh: '中文',         en: '中文' },
  lang_en:                { zh: 'English',      en: 'English' },
  quit_plugin:            { zh: '退出插件',      en: 'Quit Plugin' },
  save:                   { zh: '保存',          en: 'Save' },
  collapse:               { zh: '收起',          en: 'Collapse' },
  orient_h:               { zh: '横向',          en: 'H' },
  orient_v:               { zh: '纵向',          en: 'V' },
};

type StringKey = keyof typeof STRINGS;

function detectLocale(): Locale {
  try {
    let lang: string | undefined;
    if (Platform.OS === 'ios') {
      lang = NativeModules.SettingsManager?.settings?.AppleLocale
           || NativeModules.SettingsManager?.settings?.AppleLanguages?.[0];
    } else {
      lang = NativeModules.I18nManager?.localeIdentifier;
    }
    if (lang && lang.toLowerCase().startsWith('zh')) return 'zh';
  } catch {}
  return 'en';
}

let _locale: Locale = detectLocale();

export function getLocale(): Locale {
  return _locale;
}

export function setLocale(loc: Locale): void {
  if (loc !== _locale) {
    _locale = loc;
    DeviceEventEmitter.emit('localeChanged', { locale: loc });
  }
}

export function t(key: StringKey, params?: Record<string, string | number>): string {
  const entry = STRINGS[key];
  let s = entry ? entry[_locale] || entry.en : String(key);
  if (params) {
    for (const k of Object.keys(params)) {
      s = s.replace(new RegExp(`\\{${k}\\}`, 'g'), String(params[k]));
    }
  }
  return s;
}
