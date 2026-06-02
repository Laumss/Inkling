

import { NativeModules } from 'react-native';
import { ToolItem, withLatchFlag } from './FloatingToolbarBridge';
import { t } from './i18n';

const { FloatingToolbar } = NativeModules;

const CONFIG_STORE_KEY = 1;
const CLIP_STORE_KEY = 99;

export function getLayoutForCount(n: number): { cols: number; rows: number } {
  return { cols: n, rows: 1 };
}

export function isValidToolCount(n: number): boolean {
  return n >= 1 && n <= 10;
}

export interface ConfigData {
  tools: ToolItem[];
}

export interface ClipData {
  [slot: string]: string | null;
}

export type ToolCategory = 'layer' | 'insert' | 'text' | 'lasso';

const TOOL_DEFS: { id: string; icon: string; action: string; nameKey: string; nameParams?: any; category: ToolCategory }[] = [
  { id: 'insert_image',          icon: 'Im', action: 'insert_image',          nameKey: 'tool_insert_image',          category: 'insert' },
  { id: 'insert_doc_screenshot', icon: 'Sc', action: 'insert_doc_screenshot', nameKey: 'tool_insert_doc_screenshot', category: 'insert' },
  { id: 'insert_text',           icon: 'Tx', action: 'insert_text',           nameKey: 'tool_insert_text',           category: 'insert' },
  { id: 'send_ai',               icon: 'AI', action: 'lasso_smart_send',      nameKey: 'tool_lasso_smart_send',      category: 'lasso' },
  { id: 'insert_link',           icon: 'Lk', action: 'insert_link',           nameKey: 'tool_insert_link',           category: 'insert' },
  { id: 'voice_transcribe',      icon: 'Vc', action: 'voice_transcribe',      nameKey: 'tool_voice_transcribe',      category: 'insert' },
  { id: 'invert_ink',            icon: 'Rl', action: 'invert_ink',            nameKey: 'tool_invert_ink',            category: 'insert' },
];

export interface BubbleAction {
  id: string;
  icon: string;
  label: string;
  action: string;
}

const BUBBLE_ACTION_DEFS: { id: string; icon: string; action: string; nameKey: string }[] = [
  { id: 'lasso_send',      icon: 'Sd', action: 'lasso_send',      nameKey: 'tool_lasso_send' },
  { id: 'screenshot_send', icon: 'Ls', action: 'screenshot_send', nameKey: 'tool_screenshot_send' },
  { id: 'toggle_spacing',  icon: 'Sp', action: 'toggle_spacing',  nameKey: 'tool_toggle_spacing' },
];

const AI_BUBBLE_ACTION_DEFS: { id: string; icon: string; action: string; nameKey: string }[] = [
  { id: 'lasso_ai',       icon: 'AI', action: 'lasso_ai',       nameKey: 'tool_lasso_ai' },
  { id: 'screenshot_ai',  icon: 'St', action: 'screenshot_ai',  nameKey: 'tool_screenshot_ai' },
  { id: 'pen_lasso_ai',   icon: 'Sl', action: 'pen_lasso_ai',   nameKey: 'tool_pen_lasso_ai' },
  { id: 'cancel_ai',      icon: '✕',  action: 'cancel_ai',      nameKey: 'tool_cancel_ai' },
];

export function getAvailableBubbleActions(): BubbleAction[] {
  return BUBBLE_ACTION_DEFS.map(d => ({
    id: d.id,
    icon: d.icon,
    label: t(d.nameKey as any),
    action: d.action,
  }));
}

const DEFAULT_BUBBLE_ACTIONS = ['lasso_send', 'screenshot_send', 'toggle_spacing'];

const DEFAULT_AI_BUBBLE_ACTIONS = ['lasso_ai', 'screenshot_ai', 'pen_lasso_ai', 'cancel_ai'];

const BUBBLE_ACTION_STORE_KEY = 98;

export async function loadBubbleActions(): Promise<string[]> {
  try {
    const json = await FloatingToolbar?.loadPreset(BUBBLE_ACTION_STORE_KEY);
    if (json) {
      const data = JSON.parse(json);
      if (Array.isArray(data.enabledIds)) {
        const saved: string[] = data.enabledIds;
        const allKnown = new Set(BUBBLE_ACTION_DEFS.map(d => d.id));
        const newIds = DEFAULT_BUBBLE_ACTIONS.filter(id => allKnown.has(id) && !saved.includes(id));
        if (newIds.length > 0) {
          const merged = [...saved, ...newIds];
          saveBubbleActions(merged);
          return merged;
        }
        return saved;
      }
    }
  } catch (e) {
    console.warn('[ToolPresets]: loadBubbleActions:', e);
  }
  return DEFAULT_BUBBLE_ACTIONS;
}

export async function saveBubbleActions(enabledIds: string[]): Promise<void> {
  try {
    await FloatingToolbar?.savePreset(BUBBLE_ACTION_STORE_KEY, JSON.stringify({ enabledIds }));
  } catch (e) {
    console.warn('[ToolPresets]: saveBubbleActions:', e);
  }
}

export function resolveBubbleActions(enabledIds: string[]): BubbleAction[] {
  return enabledIds
    .map(id => {
      const def = BUBBLE_ACTION_DEFS.find(d => d.id === id);
      if (!def) return null;
      return { id: def.id, icon: def.icon, label: t(def.nameKey as any), action: def.action };
    })
    .filter((x): x is BubbleAction => x !== null);
}

const AI_BUBBLE_ACTION_STORE_KEY = 97;

export function getAvailableAiBubbleActions(): BubbleAction[] {
  return AI_BUBBLE_ACTION_DEFS.map(d => ({
    id: d.id,
    icon: d.icon,
    label: t(d.nameKey as any),
    action: d.action,
  }));
}

export async function loadAiBubbleActions(): Promise<string[]> {
  try {
    const json = await FloatingToolbar?.loadPreset(AI_BUBBLE_ACTION_STORE_KEY);
    if (json) {
      const data = JSON.parse(json);
      if (Array.isArray(data.enabledIds)) {
        const saved: string[] = data.enabledIds;
        const allKnown = new Set(AI_BUBBLE_ACTION_DEFS.map(d => d.id));
        const newIds = DEFAULT_AI_BUBBLE_ACTIONS.filter(id => allKnown.has(id) && !saved.includes(id));
        if (newIds.length > 0) {
          const merged = DEFAULT_AI_BUBBLE_ACTIONS.filter(id => saved.includes(id) || newIds.includes(id));
          saveAiBubbleActions(merged);
          return merged;
        }
        return saved;
      }
    }
  } catch (e) {
    console.warn('[ToolPresets]: loadAiBubbleActions:', e);
  }
  return DEFAULT_AI_BUBBLE_ACTIONS;
}

export async function saveAiBubbleActions(enabledIds: string[]): Promise<void> {
  try {
    await FloatingToolbar?.savePreset(AI_BUBBLE_ACTION_STORE_KEY, JSON.stringify({ enabledIds }));
  } catch (e) {
    console.warn('[ToolPresets]: saveAiBubbleActions:', e);
  }
}

export function resolveAiBubbleActions(enabledIds: string[]): BubbleAction[] {
  return enabledIds
    .map(id => {
      const def = AI_BUBBLE_ACTION_DEFS.find(d => d.id === id);
      if (!def) return null;
      return { id: def.id, icon: def.icon, label: t(def.nameKey as any), action: def.action };
    })
    .filter((x): x is BubbleAction => x !== null);
}

export function getAvailableTools(): ToolItem[] {
  return TOOL_DEFS.map(d => withLatchFlag({
    id: d.id,
    name: t(d.nameKey as any, d.nameParams),
    icon: d.icon,
    action: d.action,
  }));
}

export function getToolCategory(id: string): ToolCategory | null {
  return TOOL_DEFS.find(d => d.id === id)?.category ?? null;
}

export const AVAILABLE_TOOLS: ToolItem[] = getAvailableTools();
export const DEFAULT_TOOLS = getAvailableTools();

let _configCache: ConfigData | null = null;
let _clipCache: ClipData | null = null;

export function getCachedConfig(): ConfigData | null { return _configCache; }

export function getCachedClips(): ClipData | null { return _clipCache; }

export async function warmupCache(): Promise<void> {
  const [config, clips] = await Promise.all([loadConfig(), loadClips()]);
  _configCache = config;
  _clipCache = clips;
}

const DEFAULT_EXCLUDED_TOOL_IDS = new Set(['voice_transcribe']);

export async function loadConfig(): Promise<ConfigData> {
  try {
    const json = await FloatingToolbar?.loadPreset(CONFIG_STORE_KEY);
    if (json) {
      const data = JSON.parse(json) as ConfigData;
      if (Array.isArray(data.tools) && data.tools.length > 0) {
        _configCache = data;
        return data;
      }
    }
  } catch (e) {
    console.warn('[ToolPresets]: loadConfig:', e);
  }
  const result = { tools: getAvailableTools().filter(t => !DEFAULT_EXCLUDED_TOOL_IDS.has(t.id)) };
  _configCache = result;
  return result;
}

export async function saveConfig(data: ConfigData): Promise<boolean> {
  try {
    _configCache = data;
    await FloatingToolbar?.savePreset(CONFIG_STORE_KEY, JSON.stringify(data));
    return true;
  } catch (e) {
    console.warn('[ToolPresets]: saveConfig:', e);
    return false;
  }
}

export async function loadPreset(_num?: number): Promise<ConfigData> {
  return loadConfig();
}
export async function savePreset(_num: number, data: ConfigData): Promise<boolean> {
  return saveConfig(data);
}

export async function loadClips(): Promise<ClipData> {
  try {
    const json = await FloatingToolbar?.loadPreset(CLIP_STORE_KEY);
    if (json) {
      const result = JSON.parse(json) as ClipData;

      if (!('5' in result)) result['5'] = null;
      if (!('6' in result)) result['6'] = null;
      _clipCache = result;
      return result;
    }
  } catch (e) {
    console.warn('[ToolPresets]: loadClips:', e);
  }
  const result = { '1': null, '2': null, '3': null, '4': null, '5': null, '6': null };
  _clipCache = result;
  return result;
}

export async function saveClips(clips: ClipData): Promise<void> {
  try {
    _clipCache = clips;
    await FloatingToolbar?.savePreset(CLIP_STORE_KEY, JSON.stringify(clips));
  } catch (e) {
    console.warn('[ToolPresets]: saveClips:', e);
  }
}

export function injectClipStatus(
  tools: ToolItem[],
  clips: ClipData,
  activeMode: 'nospacing' | 'paragraph' | null = null,
): ToolItem[] {
  return tools.map(tool => {
    if (tool.id.startsWith('clip_')) {
      const slot = tool.id.split('_')[1];
      const hasContent = !!clips[slot];
      return {
        ...tool,
        name: hasContent ? t('tool_clip_paste', { n: slot }) : t('tool_clip', { n: slot }),
        icon: hasContent ? `p${slot}` : `c${slot}`,
      };
    }
    void activeMode;
    return tool;
  });
}
