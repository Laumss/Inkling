export const PAGE_MARGIN_RIGHT = 0.04;
export const MAX_INSERT_LEFT_RATIO = 0.60;
export const MIN_TEXTBOX_WIDTH_RATIO = 0.20;
export const MIN_TEXTBOX_FONT_COLUMNS = 8;

export interface SafeInsertionGeometry {
  left: number;
  right: number;
  safeMaxLeft: number;
  minTextboxWidth: number;
}

/**
 * Constrain an explicit insertion column without changing the normal default
 * left margin. The native layout engine mirrors this formula as a final guard.
 */
export function normalizeInsertionLeft(
  pageWidth: number,
  requestedLeft: number,
  fontSize: number,
): SafeInsertionGeometry {
  const width = Math.max(1, Math.round(pageWidth));
  const right = width - Math.floor(width * PAGE_MARGIN_RIGHT);
  const minTextboxWidth = Math.min(
    right,
    Math.max(fontSize * MIN_TEXTBOX_FONT_COLUMNS, Math.floor(width * MIN_TEXTBOX_WIDTH_RATIO)),
  );
  const safeMaxLeft = Math.max(
    0,
    Math.min(Math.floor(width * MAX_INSERT_LEFT_RATIO), right - minTextboxWidth),
  );
  const left = Math.max(0, Math.min(Math.round(requestedLeft), safeMaxLeft));
  return { left, right, safeMaxLeft, minTextboxWidth };
}
