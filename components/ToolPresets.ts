

import { NativeModules } from 'react-native';
import { t } from './i18n';

const { FloatingToolbar } = NativeModules;

const CLIP_STORE_KEY = 99;

export interface ClipData {
  [slot: string]: string | null;
}

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
  { id: 'copilot',        icon: 'Co', action: 'invert_ink',     nameKey: 'tool_invert_ink' },
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

const DEFAULT_AI_BUBBLE_ACTIONS = ['lasso_ai', 'screenshot_ai', 'pen_lasso_ai', 'copilot'];

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

export async function loadClips(): Promise<ClipData> {
  try {
    const json = await FloatingToolbar?.loadPreset(CLIP_STORE_KEY);
    if (json) {
      const result = JSON.parse(json) as ClipData;

      if (!('5' in result)) result['5'] = null;
      if (!('6' in result)) result['6'] = null;
      return result;
    }
  } catch (e) {
    console.warn('[ToolPresets]: loadClips:', e);
  }
  return { '1': null, '2': null, '3': null, '4': null, '5': null, '6': null };
}

export async function saveClips(clips: ClipData): Promise<void> {
  try {
    await FloatingToolbar?.savePreset(CLIP_STORE_KEY, JSON.stringify(clips));
  } catch (e) {
    console.warn('[ToolPresets]: saveClips:', e);
  }
}

