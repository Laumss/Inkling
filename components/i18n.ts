

import { NativeModules, Platform } from 'react-native';

export type Locale = 'zh' | 'en';

const STRINGS = {

  perm_required:          { zh: '需要权限',     en: 'Permission Required' },
  initial_permissions_required: {
    zh: 'Inkling 需要文件读写和网络权限来使用工具栏、套索识别、图片、文档、截图及 LocalSend。完成授权后请再次点击 Inkling。',
    en: 'Inkling needs file read/write and network permissions for the toolbar, lasso recognition, images, documents, screenshots, and LocalSend. Complete authorization, then tap Inkling again.',
  },
  perm_desc_file_read: {
    zh: 'Inkling 需要读取笔记与文档以提供快捷工具',
    en: 'Inkling needs read access to notes and documents for the toolbar',
  },
  perm_desc_file_write: {
    zh: 'Inkling 需要写入存储以保存剪藏与配置',
    en: 'Inkling needs storage write access to save clips and settings',
  },
  perm_desc_file_delete: {
    zh: 'Inkling 需要删除存储中的临时导出文件',
    en: 'Inkling needs storage delete access for temporary export files',
  },
  perm_desc_internet: {
    zh: 'Inkling 需要访问局域网以提供 LocalSend 发送与接收',
    en: 'Inkling needs LAN access for LocalSend send and receive',
  },

  tool_lasso_send:        { zh: '发送选区',     en: 'Send Lasso' },
  tool_lasso_ai:          { zh: '发给 AI',      en: 'Send to AI' },

  bubble_recv_nospacing:  { zh: '无间距接收中', en: 'Receiving (No Gap)' },
  bubble_recv_paragraph:  { zh: '段落接收中',   en: 'Receiving (Paragraph)' },
  bubble_ai_recognizing:  { zh: '识别中…',     en: 'Recognizing…' },
  bubble_ai_sending:      { zh: '发送中…',     en: 'Sending…' },
  bubble_ai_waiting:      { zh: '等待 AI 回复', en: 'Waiting for AI' },
  bubble_ai_no_lasso:     { zh: '请先套索选中文字', en: 'Select text with lasso first' },
  lasso_send_ocr_failed:  { zh: '无法识别所选笔迹内容', en: 'Unable to recognize the selected handwriting' },
  bubble_ai_ready:        { zh: '就绪，点 AI 套索发送', en: 'Ready · tap AI to send' },
  bubble_ai_not_paired:   { zh: '未连接手机，请先在手机开启 RikkaHub', en: 'Phone not connected · start RikkaHub' },
  bubble_ai_queued:       { zh: '手机离线，已排队待发送', en: 'Phone offline · queued' },
  bubble_ai_send_failed:  { zh: '发送失败，请重试',      en: 'Send failed · try again' },
  bubble_relay_on:        { zh: 'AIRelay 已开启',         en: 'AIRelay is on' },
  bubble_relay_off:       { zh: 'AIRelay 已关闭，点 Co 开启', en: 'AIRelay is off · tap Co to start' },
  bubble_relay_disabled:  { zh: '请先点 Co 开启 AIRelay', en: 'Tap Co to start AIRelay first' },
  bubble_ai_reply_ready:  { zh: '回复已就绪，点下方卡片查看', en: 'Reply ready · tap a card below' },
  bubble_formula_rendering: { zh: '渲染公式中…', en: 'Rendering formula…' },
  bubble_formula_failed:  { zh: '公式处理失败', en: 'Formula failed' },
  formula_no_relay:       { zh: 'AIRelay 未连接，请确认手机端已启动并可访问', en: 'AIRelay is not connected. Confirm the phone endpoint is running and reachable.' },
  bubble_insert_sent:     { zh: '正在插入笔记…', en: 'Inserting into note…' },
  bubble_insert_done:     { zh: '已插入',         en: 'Inserted' },
  bubble_ai_timeout:      { zh: 'AI 无响应，请检查中转站', en: 'AI timed out · check the relay' },

  note_switched_stop:     { zh: '笔记已切换，文本接收已停止', en: 'Note switched, text receiving stopped' },
  pages_changed_stop:     { zh: '页面结构变更，文本接收已停止', en: 'Page structure changed, text receiving stopped' },

  tool_screenshot_ai:     { zh: '截图发 AI',  en: 'Screenshot AI' },
  tool_screenshot_send:   { zh: '截图发送',   en: 'Screenshot Send' },
  tool_pen_lasso_ai:      { zh: '笔套索 AI',  en: 'Pen Lasso AI' },

  tool_toggle_spacing:    { zh: '切换间距',   en: 'Toggle Gap' },
  tool_invert_ink:        { zh: 'Inkling 工坊',     en: 'Relay' },
  tool_cancel_ai:         { zh: '取消 AI',    en: 'Cancel AI' },

  layer_err_recognition:  { zh: '实时识别笔记不支持图层操作', en: 'Recognition notes do not support layer operations' },
  layer_err_unmovable:    { zh: '标题、链接、文本框或图片不支持跨图层移动', en: 'Titles, links, text boxes and images cannot be moved across layers' },
  layer_err_max:          { zh: '已达到最大图层数', en: 'Maximum layer count reached' },
  layer_err_need_new:     { zh: '上方没有更多图层，请先新增图层', en: 'No layer above; please add a new layer first' },
  layer_err_at_top:       { zh: '已在最顶层',     en: 'Already at the top layer' },
  layer_err_at_bottom:    { zh: '已在最底层',     en: 'Already at the bottom layer' },
  layer_err_at_main:      { zh: '已在主图层，无法继续向下移动', en: 'Already at the main layer' },
  doc_link_main_only:     { zh: '请切换到主图层后再插入文档链接', en: 'If you want to insert a document link, you should switch to the main layer first' },

  clip_err_title:         { zh: '标题暂不支持剪贴板保存', en: 'Titles cannot be saved to clipboard' },
  clip_err_link:          { zh: '链接暂不支持剪贴板保存', en: 'Links cannot be saved to clipboard' },
  clip_err_textbox:       { zh: '文本框暂不支持剪贴板保存', en: 'Text boxes cannot be saved to clipboard' },
  clip_err_image:         { zh: '图片暂不支持剪贴板保存', en: 'Images cannot be saved to clipboard' },
  clip_overwrite:         { zh: '剪贴板已有内容，是否覆盖？', en: 'Clipboard slot is not empty. Overwrite?' },
  clip_empty_hint:        { zh: '剪贴板为空，请先用套索选中内容保存', en: 'Clipboard is empty. Select content with lasso first.' },
  palette_ink_only:       { zh: 'Palette 只对手写笔迹生效', en: 'Palette only works on handwriting' },
  clip_err_read_failed:   { zh: '读取套索内容失败，请稍候重试', en: 'Failed to read lasso content. Please try again.' },
  clip_save_failed:       { zh: '剪贴板保存失败，请重试', en: 'Clipboard save failed, please retry' },
  snapshot_empty_page:    { zh: '当前页没有可保存的内容', en: 'Nothing on this page to snapshot' },
  snapshot_create_failed: { zh: '创建还原点失败，请重试', en: 'Failed to create restore point, please retry' },
  snapshot_missing:       { zh: '还原点文件已丢失', en: 'Restore point file is missing' },
  snapshot_restore_failed:{ zh: '还原失败，请重试', en: 'Restore failed, please retry' },
  btn_cancel:             { zh: '取消', en: 'Cancel' },
  btn_confirm:            { zh: '确定', en: 'OK' },

  no_wifi:                { zh: '未连接 WiFi', en: 'WiFi is not connected' },
  localsend_started:      { zh: 'LocalSend 已开启', en: 'LocalSend started' },
  localsend_stopped:      { zh: 'LocalSend 已关闭', en: 'LocalSend stopped' },

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

function syncNativeLocale(loc: Locale): void {
  try {
    NativeModules.FloatingToolbar?.setLocale?.(loc);
  } catch {}
}

export function getLocale(): Locale {
  return _locale;
}

export function setLocale(loc: Locale): void {
  if (loc !== _locale) {
    _locale = loc;
  }
  syncNativeLocale(_locale);
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
