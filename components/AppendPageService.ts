import { PluginCommAPI, PluginFileAPI, PluginNoteAPI } from 'sn-plugin-lib';

let appendCounter = 0;

export interface CropInfo {
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
  imageWidth: number;
  imageHeight: number;
}

type Corner = 'tl' | 'tr' | 'bl' | 'br';

function pickCorner(crop: CropInfo, pageW: number, pageH: number): Corner {
  const sx = pageW / crop.imageWidth;
  const sy = pageH / crop.imageHeight;
  const cx = (crop.offsetX + crop.width / 2) * sx;
  const cy = (crop.offsetY + crop.height / 2) * sy;

  const d = [
    { k: 'tl' as Corner, v: cx * cx + cy * cy },
    { k: 'tr' as Corner, v: (pageW - cx) ** 2 + cy * cy },
    { k: 'bl' as Corner, v: cx * cx + (pageH - cy) ** 2 },
    { k: 'br' as Corner, v: (pageW - cx) ** 2 + (pageH - cy) ** 2 },
  ];
  return d.reduce((a, b) => a.v < b.v ? a : b).k;
}

function linkRectForCrop(
  corner: Corner, crop: CropInfo,
  pageW: number, pageH: number,
) {
  const sx = pageW / crop.imageWidth;
  const sy = pageH / crop.imageHeight;
  const cl = crop.offsetX * sx;
  const ct = crop.offsetY * sy;
  const cr = (crop.offsetX + crop.width) * sx;
  const cb = (crop.offsetY + crop.height) * sy;

  const w = 130, h = 60, gap = 10;
  let left: number, top: number;

  switch (corner) {
    case 'tl': left = cl - w - gap; top = ct - h - gap; break;
    case 'tr': left = cr + gap;     top = ct - h - gap; break;
    case 'bl': left = cl - w - gap; top = cb + gap;     break;
    case 'br': left = cr + gap;     top = cb + gap;     break;
  }

  const m = 8;
  left = Math.max(m, Math.min(left, pageW - w - m));
  top  = Math.max(m, Math.min(top,  pageH - h - m));

  return {
    left: Math.round(left), top: Math.round(top),
    right: Math.round(left + w), bottom: Math.round(top + h),
  };
}

function linkRectDefault(pageW: number, pageH: number) {
  const m = 60, w = 130, h = 60;
  return { left: pageW - m - w, top: pageH - m - h, right: pageW - m, bottom: pageH - m };
}

async function waitForPage(targetPage: number, timeoutMs = 30000): Promise<boolean> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const pgRes: any = await PluginCommAPI.getCurrentPageNum();
      if (pgRes?.success && pgRes.result === targetPage) return true;
    } catch {}
    await new Promise(r => setTimeout(r, 500));
  }
  return false;
}

export async function appendPageWithLink(imagePath: string, crop?: CropInfo): Promise<string> {
  const fpRes: any = await PluginCommAPI.getCurrentFilePath();
  if (!fpRes?.success || !fpRes.result) throw new Error('No file path');
  const notePath: string = fpRes.result;

  const pgRes: any = await PluginCommAPI.getCurrentPageNum();
  if (!pgRes?.success || pgRes.result === undefined) throw new Error('No page num');
  const sourcePage: number = pgRes.result;

  const totalRes: any = await PluginFileAPI.getNoteTotalPageNum(notePath);
  if (!totalRes?.success || totalRes.result === undefined) throw new Error('No total pages');
  const totalPages: number = totalRes.result;

  const newPageIdx = totalPages;
  appendCounter++;
  const num = appendCounter;

  let pageW = 1404, pageH = 1872;
  try {
    const szRes: any = await PluginFileAPI.getPageSize(notePath, sourcePage);
    if (szRes?.success && szRes.result) {
      pageW = (szRes.result as any).width || pageW;
      pageH = (szRes.result as any).height || pageH;
    }
  } catch {}

  let template = 'style_white';
  try {
    const templates: any = await PluginCommAPI.getNoteSystemTemplates();
    if (Array.isArray(templates) && templates.length > 0) {
      const pick = templates.find((x: any) => x.name === 'style_white')
                || templates.find((x: any) => x.name === 'style_blank')
                || templates[0];
      if (pick?.name) template = pick.name;
    }
  } catch {}

  const insRes: any = await PluginFileAPI.insertNotePage({ notePath, page: newPageIdx, template } as any);
  if (!insRes?.success) throw new Error(`insertNotePage failed: ${insRes?.error?.message}`);

  await PluginCommAPI.reloadFile();

  try {
    await (PluginNoteAPI as any).insertTextLink({
      destPath: notePath,
      destPage: newPageIdx,
      style: 1,
      linkType: 0,
      rect: crop
        ? linkRectForCrop(pickCorner(crop, pageW, pageH), crop, pageW, pageH)
        : linkRectDefault(pageW, pageH),
      fontSize: 36,
      fullText: `[${num}]`,
      showText: `[${num}]`,
      isItalic: 0,
    });
  } catch (e) { console.warn('[AppendPage]: fwd link failed:', e); }

  const arrived = await waitForPage(newPageIdx);
  if (!arrived) {
    console.warn('[AppendPage]: user did not navigate to new page within timeout');
    return `[${num}] → p${newPageIdx + 1} (pending)`;
  }

  try {
    await PluginNoteAPI.insertImage(imagePath);
  } catch (e) { console.warn('[AppendPage]: insertImage failed:', e); }

  let newPageW = pageW, newPageH = pageH;
  try {
    const szRes2: any = await PluginFileAPI.getPageSize(notePath, newPageIdx);
    if (szRes2?.success && szRes2.result) {
      newPageW = (szRes2.result as any).width || newPageW;
      newPageH = (szRes2.result as any).height || newPageH;
    }
  } catch {}

  try {
    await (PluginNoteAPI as any).insertTextLink({
      destPath: notePath,
      destPage: sourcePage,
      style: 1,
      linkType: 0,
      rect: linkRectDefault(newPageW, newPageH),
      fontSize: 36,
      fullText: `[←${num}]`,
      showText: `[←${num}]`,
      isItalic: 0,
    });
  } catch (e) { console.warn('[AppendPage]: back link failed:', e); }

  console.log(`[AppendPage]: done — fwd [${num}] on p${sourcePage + 1}, image+backlink on p${newPageIdx + 1}`);
  return `[${num}] → p${newPageIdx + 1}`;
}
