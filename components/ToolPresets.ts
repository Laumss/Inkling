

import { NativeModules } from 'react-native';
import { t } from './i18n';

const { FloatingToolbar } = NativeModules;

const CLIP_STORE_KEY = 99;
const SNAPSHOT_STORE_KEY = 95;

export interface ClipData {
  [slot: string]: string | null;
}

export interface SnapshotRecord {
  id: string;
  sticker: string;
  thumb: string;
  note: string;
  page: number;
  ts: number;
}

export async function loadSnapshots(): Promise<SnapshotRecord[]> {
  try {
    const json = await FloatingToolbar?.loadPreset(SNAPSHOT_STORE_KEY);
    if (!json) return [];
    const data = JSON.parse(json);
    return Array.isArray(data) ? data.filter((r: any) => r && typeof r.sticker === 'string') : [];
  } catch (e) {
    console.warn('[ToolPresets]: loadSnapshots:', e);
    return [];
  }
}

export async function saveSnapshots(list: SnapshotRecord[]): Promise<void> {
  try {
    await FloatingToolbar?.savePreset(SNAPSHOT_STORE_KEY, JSON.stringify(list));
  } catch (e) {
    console.warn('[ToolPresets]: saveSnapshots:', e);
  }
}

export interface BubbleAction {
  id: string;
  icon: string;
  label: string;
  action: string;
}

const AI_BUBBLE_ACTION_DEFS: { id: string; icon: string; action: string; nameKey: string }[] = [
  { id: 'lasso_ai',       icon: 'AI', action: 'lasso_ai',       nameKey: 'tool_lasso_ai' },
  { id: 'screenshot_ai',  icon: 'St', action: 'screenshot_ai',  nameKey: 'tool_screenshot_ai' },
  { id: 'pen_lasso_ai',   icon: 'Sl', action: 'pen_lasso_ai',   nameKey: 'tool_pen_lasso_ai' },
  { id: 'copilot',        icon: 'Co', action: 'invert_ink',     nameKey: 'tool_invert_ink' },
  { id: 'cancel_ai',      icon: '✕',  action: 'cancel_ai',      nameKey: 'tool_cancel_ai' },
];

const DEFAULT_AI_BUBBLE_ACTIONS = ['lasso_ai', 'screenshot_ai', 'pen_lasso_ai', 'copilot'];

const AI_BUBBLE_ACTION_STORE_KEY = 97;

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

