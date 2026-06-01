import type {
  NativeDetailedMeasurement,
  NativeDetailedWord,
} from './TextboxMetrics';

export type PageWordCandidate = NativeDetailedWord & {
  absoluteLeft: number;
  absoluteRight: number;
  absoluteTop: number;
  absoluteBottom: number;
  absoluteCenterX: number;
  absoluteCenterY: number;
};

export type TextRect = {
  left: number;
  top: number;
  right: number;
  bottom: number;
};

export function buildPageWordCandidates(
  measurement: NativeDetailedMeasurement,
  textRect: TextRect,
  calibration: { offsetX: number; offsetY: number },
): PageWordCandidate[] {
  return measurement.words.map(word => ({
    ...word,
    absoluteLeft: textRect.left + calibration.offsetX + word.left,
    absoluteRight: textRect.left + calibration.offsetX + word.right,
    absoluteTop: textRect.top + calibration.offsetY + word.top,
    absoluteBottom: textRect.top + calibration.offsetY + word.bottom,
    absoluteCenterX: textRect.left + calibration.offsetX + word.centerX,
    absoluteCenterY: textRect.top + calibration.offsetY + word.centerY,
  }));
}

export function findWordAtPoint(
  candidates: PageWordCandidate[],
  x: number,
  y: number,
): PageWordCandidate | null {
  const direct = candidates.find(
    w => x >= w.absoluteLeft && x <= w.absoluteRight &&
         y >= w.absoluteTop && y <= w.absoluteBottom,
  );
  if (direct) return direct;
  if (candidates.length === 0) return null;

  return [...candidates].sort((a, b) => {
    const dA = Math.hypot(a.absoluteCenterX - x, a.absoluteCenterY - y);
    const dB = Math.hypot(b.absoluteCenterX - x, b.absoluteCenterY - y);
    return dA - dB;
  })[0];
}

export function findCharacterOffsetAtPoint(
  candidates: PageWordCandidate[],
  x: number,
  y: number,
): number | null {
  const word = findWordAtPoint(candidates, x, y);
  if (!word) return null;

  const wordLength = Math.max(1, word.end - word.start);
  const relativeX =
    word.absoluteRight <= word.absoluteLeft
      ? 0
      : (Math.min(word.absoluteRight, Math.max(word.absoluteLeft, x)) - word.absoluteLeft) /
        (word.absoluteRight - word.absoluteLeft);
  const offsetInWord = Math.min(wordLength, Math.max(0, Math.round(relativeX * wordLength)));
  return Math.min(word.end, word.start + offsetInWord);
}

export function findLineAtY(
  measurement: NativeDetailedMeasurement,
  textRect: TextRect,
  calibration: { offsetY: number },
  pageY: number,
): number | null {
  for (const line of measurement.lines) {
    const absTop = textRect.top + calibration.offsetY + line.top;
    const absBottom = textRect.top + calibration.offsetY + line.bottom;
    if (pageY >= absTop && pageY <= absBottom) return line.index;
  }
  return null;
}
