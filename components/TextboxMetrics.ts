import { NativeModules } from 'react-native';

const { TextboxMetrics } = NativeModules;

export type TextboxMeasurementCalibration = {
  offsetX: number;
  offsetY: number;
  widthAdjustment: number;
};

export const DEFAULT_CALIBRATION: TextboxMeasurementCalibration = {
  offsetX: 0,
  offsetY: 0,
  widthAdjustment: 30,
};

export type NativeTextMeasurement = {
  text: string;
  requestedWidth: number;
  requestedFontSize: number;
  includePad: boolean;
  layoutHeight: number;
  lineCount: number;
  maxLineWidth: number;
};

export type NativeDetailedLine = {
  index: number;
  start: number;
  end: number;
  left: number;
  right: number;
  top: number;
  bottom: number;
  baseline: number;
  width: number;
};

export type NativeDetailedWord = {
  start: number;
  end: number;
  tokenStart: number;
  tokenEnd: number;
  lineIndex: number;
  text: string;
  left: number;
  right: number;
  top: number;
  bottom: number;
  width: number;
  height: number;
  centerX: number;
  centerY: number;
};

export type NativeDetailedMeasurement = NativeTextMeasurement & {
  lines: NativeDetailedLine[];
  words: NativeDetailedWord[];
};

export type SplitResult = {
  fittingText: string;
  overflowText: string;
  fittingHeight: number;
  fittingLineCount: number;
  didSplit: boolean;
};

function getRequestedWidth(width: number, calibration: TextboxMeasurementCalibration): number {
  return Math.max(24, width - calibration.widthAdjustment);
}

export async function splitTextForHeight(
  text: string,
  width: number,
  fontSize: number,
  availableHeight: number,
  calibration: TextboxMeasurementCalibration = DEFAULT_CALIBRATION,
): Promise<SplitResult | null> {
  if (!TextboxMetrics?.splitTextForHeight) return null;
  try {
    return await TextboxMetrics.splitTextForHeight({
      text,
      width: getRequestedWidth(width, calibration),
      fontSize,
      includePad: true,
      availableHeight,
    });
  } catch (e) {
    console.warn('[TextboxMetrics]: splitTextForHeight failed:', e);
    return null;
  }
}
