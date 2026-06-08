


import { NativeModules, Platform, DeviceEventEmitter } from 'react-native';

export type Locale = 'zh' | 'en';

const STRINGS = {

  app_title:              { zh: '快捷工具栏',   en: 'QuickToolbar' },
  add_tool:               { zh: '+ 添加',       en: '+ Add' },
  back:                   { zh: '返回',         en: 'Back' },
  add_tool_title:         { zh: '添加工具',     en: 'Add Tool' },

  perm_required:          { zh: '需要权限',     en: 'Permission Required' },
  perm_text:              { zh: '快捷工具栏需要"显示在其他应用上层"权限来显示悬浮工具栏。', en: 'QuickToolbar needs "Display over other apps" permission to show the floating toolbar.' },
  perm_open_settings:     { zh: '打开设置',     en: 'Open Settings' },
  perm_recheck:           { zh: '重新检查',     en: 'Re-check' },
  perm_check_settings:    { zh: '请在系统设置中授权', en: 'Check system settings' },
  perm_granted:           { zh: '权限已授予',   en: 'Permission granted' },
  perm_not_granted:       { zh: '尚未授权',     en: 'Not yet granted' },

  tool_insert_image:      { zh: '插入图片',     en: 'Insert Image' },
  tool_insert_doc_screenshot: { zh: '文档截图', en: 'Doc Screenshot' },
  tool_insert_link:       { zh: '链接文档',   en: 'Insert Link' },
  tool_clip:              { zh: '剪贴板 {n}',   en: 'Clip {n}' },
  tool_clip_paste:        { zh: '粘贴 {n}',     en: 'Paste {n}' },
  tool_lasso_send:        { zh: '发送选区',     en: 'Send Lasso' },
  tool_lasso_ai:          { zh: '发给 AI',      en: 'Send to AI' },

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
  tool_pen_lasso_ai:      { zh: '笔套索 AI',  en: 'Pen Lasso AI' },

  tool_toggle_spacing:    { zh: '切换间距',   en: 'Toggle Gap' },
  tool_insert_text:       { zh: '文本接收',   en: 'Text Receive' },
  tool_voice_transcribe:  { zh: '接收 AI',    en: 'AI Receive' },
  tool_lasso_smart_send:  { zh: '套索发送',   en: 'Lasso Send' },
  tool_invert_ink:        { zh: 'Inkling 工坊',     en: 'Relay' },
  tool_cancel_ai:         { zh: '取消 AI',    en: 'Cancel AI' },

  preview:                { zh: '实时预览',     en: 'Preview' },
  tool_list:              { zh: '工具列表',     en: 'Tools' },
  done:                   { zh: '完成',         en: 'Done' },
  selected_count:         { zh: '已选 {n} 个',  en: '{n} selected' },
  cat_all:                { zh: '全部',         en: 'All' },
  cat_layer:              { zh: '图层',         en: 'Layer' },
  cat_insert:             { zh: '插入',         en: 'Insert' },
  cat_text:               { zh: '文本',         en: 'Text' },
  cat_lasso:              { zh: '套索',         en: 'Lasso' },
  empty_tools:            { zh: '工具列表为空',  en: 'No tools added' },
  empty_tools_hint:       { zh: '请点击「+ 添加」添加工具', en: 'Tap "+ Add" to add tools' },

  save:                   { zh: '保存',          en: 'Save' },
  collapse:               { zh: '收起',          en: 'Collapse' },
  orient_h:               { zh: '横向',          en: 'H' },
  orient_v:               { zh: '纵向',          en: 'V' },
  dock_to_edge:           { zh: '贴边隐藏',      en: 'Dock' },

  layer_err_recognition:  { zh: '实时识别笔记不支持图层操作', en: 'Recognition notes do not support layer operations' },
  layer_err_unmovable:    { zh: '标题、链接、文本框或图片不支持跨图层移动', en: 'Titles, links, text boxes and images cannot be moved across layers' },
  layer_err_max:          { zh: '已达到最大图层数', en: 'Maximum layer count reached' },
  layer_err_need_new:     { zh: '上方没有更多图层，请先新增图层', en: 'No layer above; please add a new layer first' },
  layer_err_at_top:       { zh: '已在最顶层',     en: 'Already at the top layer' },
  layer_err_at_bottom:    { zh: '已在最底层',     en: 'Already at the bottom layer' },
  layer_err_at_main:      { zh: '已在主图层，无法继续向下移动', en: 'Already at the main layer' },

  clip_err_title:         { zh: '标题暂不支持剪贴板保存', en: 'Titles cannot be saved to clipboard' },
  clip_err_link:          { zh: '链接暂不支持剪贴板保存', en: 'Links cannot be saved to clipboard' },
  clip_err_textbox:       { zh: '文本框暂不支持剪贴板保存', en: 'Text boxes cannot be saved to clipboard' },
  clip_err_image:         { zh: '图片暂不支持剪贴板保存', en: 'Images cannot be saved to clipboard' },
  clip_overwrite:         { zh: '剪贴板已有内容，是否覆盖？', en: 'Clipboard slot is not empty. Overwrite?' },
  clip_empty_hint:        { zh: '剪贴板为空，请先用套索选中内容保存', en: 'Clipboard is empty. Select content with lasso first.' },
  btn_cancel:             { zh: '取消', en: 'Cancel' },
  btn_confirm:            { zh: '确定', en: 'OK' },

  no_wifi:                { zh: '未连接 WiFi', en: 'WiFi is not connected' },
  localsend_ask_enable:   { zh: 'LocalSend 未开启，是否立即开启？', en: 'LocalSend is not running. Start it now?' },
  localsend_started:      { zh: 'LocalSend 已开启', en: 'LocalSend started' },
  localsend_stopped:      { zh: 'LocalSend 已关闭', en: 'LocalSend stopped' },
  localsend_btn_on:       { zh: 'LocalSend ●', en: 'LocalSend ●' },
  localsend_btn_off:      { zh: 'LocalSend ○', en: 'LocalSend ○' },

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
