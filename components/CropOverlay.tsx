/**
 * CropOverlay — Viewfinder-style crop interface.
 *
 * - Black header: Cancel / Confirm
 * - White footer: Long Screenshot / Add to History
 * - Black dotted border with hollow L-shaped corners and hollow edge handles
 *   (white fill covers dots beneath; black outline visible)
 * - Dimmed regions outside crop box
 */

import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import {
  View,
  StyleSheet,
  Pressable,
  Text,
  Image,
  PanResponder,
  GestureResponderEvent,
  PanResponderGestureState,
  Dimensions,
} from 'react-native';

export interface CropResult {
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
}

interface CropOverlayProps {
  imageUri: string;
  originalWidth: number;
  originalHeight: number;
  onConfirm: (crop: CropResult, stayOpen: boolean) => void;
  onLongScreenshot: () => void;
  onAddToHistory: (crop: CropResult, stayOpen: boolean) => void;
  onClose: () => void;
  disabled?: boolean;
  hasStitchSession?: boolean;
}

const HEADER_HEIGHT = 56;
const FOOTER_HEIGHT = 56;
const IMAGE_PADDING = 16;
const EDGE_HIT_ZONE = 40;
const MIN_CROP_SIZE = 50;
const DIM_OPACITY = 0.45;
const CORNER_SIZE = 30;
const CORNER_THICKNESS = 12;
const HANDLE_LONG = 32;
const HANDLE_SHORT = 12;

type DragMode =
  | 'move' | 'top' | 'bottom' | 'left' | 'right'
  | 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right'
  | null;

export const CropOverlay: React.FC<CropOverlayProps> = ({
  imageUri,
  originalWidth,
  originalHeight,
  onConfirm,
  onLongScreenshot,
  onAddToHistory,
  onClose,
  disabled = false,
  hasStitchSession = false,
}) => {
  const screen = Dimensions.get('window');
  const [toastMsg, setToastMsg] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [stayOpen, setStayOpen] = useState(false);

  const imageArea = useMemo(() => {
    const availW = screen.width - IMAGE_PADDING * 2;
    const availH = screen.height - HEADER_HEIGHT - FOOTER_HEIGHT - IMAGE_PADDING * 2;
    const imageAspect = originalWidth / originalHeight;
    const areaAspect = availW / availH;

    let dispW: number, dispH: number;
    if (imageAspect > areaAspect) {
      dispW = availW;
      dispH = availW / imageAspect;
    } else {
      dispH = availH;
      dispW = availH * imageAspect;
    }

    const offsetX = (screen.width - dispW) / 2;
    const offsetY = HEADER_HEIGHT + (availH - dispH) / 2 + IMAGE_PADDING;

    return { x: offsetX, y: offsetY, w: dispW, h: dispH };
  }, [screen, originalWidth, originalHeight]);

  const [cropBox, setCropBox] = useState({
    x: imageArea.x + imageArea.w * 0.05,
    y: imageArea.y + imageArea.h * 0.05,
    w: imageArea.w * 0.9,
    h: imageArea.h * 0.9,
  });

  useEffect(() => {
    setCropBox({
      x: imageArea.x + imageArea.w * 0.05,
      y: imageArea.y + imageArea.h * 0.05,
      w: imageArea.w * 0.9,
      h: imageArea.h * 0.9,
    });
  }, [imageArea]);

  const cropBoxRef = useRef(cropBox);
  const dragModeRef = useRef<DragMode>(null);
  const dragStartRef = useRef({ x: 0, y: 0, box: { x: 0, y: 0, w: 0, h: 0 } });
  const imageAreaRef = useRef(imageArea);

  useEffect(() => { cropBoxRef.current = cropBox; }, [cropBox]);
  useEffect(() => { imageAreaRef.current = imageArea; }, [imageArea]);

  const detectDragMode = useCallback((touchX: number, touchY: number): DragMode => {
    const box = cropBoxRef.current;
    const nearLeft = Math.abs(touchX - box.x) < EDGE_HIT_ZONE;
    const nearRight = Math.abs(touchX - (box.x + box.w)) < EDGE_HIT_ZONE;
    const nearTop = Math.abs(touchY - box.y) < EDGE_HIT_ZONE;
    const nearBottom = Math.abs(touchY - (box.y + box.h)) < EDGE_HIT_ZONE;

    if (nearTop && nearLeft) return 'top-left';
    if (nearTop && nearRight) return 'top-right';
    if (nearBottom && nearLeft) return 'bottom-left';
    if (nearBottom && nearRight) return 'bottom-right';
    if (nearTop) return 'top';
    if (nearBottom) return 'bottom';
    if (nearLeft) return 'left';
    if (nearRight) return 'right';

    if (touchX >= box.x && touchX <= box.x + box.w &&
        touchY >= box.y && touchY <= box.y + box.h) return 'move';
    return null;
  }, []);

  /** Always read imageArea from ref so PanResponder never uses a stale closure */
  const clampBoxLive = (box: { x: number; y: number; w: number; h: number }) => {
    const ia = imageAreaRef.current;
    let { x, y, w, h } = box;
    w = Math.max(MIN_CROP_SIZE, Math.min(w, ia.w));
    h = Math.max(MIN_CROP_SIZE, Math.min(h, ia.h));
    x = Math.max(ia.x, Math.min(x, ia.x + ia.w - w));
    y = Math.max(ia.y, Math.min(y, ia.y + ia.h - h));
    return { x, y, w, h };
  };

  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (evt: GestureResponderEvent) => {
        const { pageX, pageY } = evt.nativeEvent;
        dragModeRef.current = detectDragMode(pageX, pageY);
        dragStartRef.current = { x: pageX, y: pageY, box: { ...cropBoxRef.current } };
      },
      onPanResponderMove: (_: GestureResponderEvent, gesture: PanResponderGestureState) => {
        const mode = dragModeRef.current;
        if (!mode) return;
        const { dx, dy } = gesture;
        const orig = dragStartRef.current.box;
        let newBox = { ...orig };
        switch (mode) {
          case 'move':
            newBox.x = orig.x + dx; newBox.y = orig.y + dy; break;
          case 'top':
            newBox.y = orig.y + dy; newBox.h = orig.h - dy; break;
          case 'bottom':
            newBox.h = orig.h + dy; break;
          case 'left':
            newBox.x = orig.x + dx; newBox.w = orig.w - dx; break;
          case 'right':
            newBox.w = orig.w + dx; break;
          case 'top-left':
            newBox.x = orig.x + dx; newBox.y = orig.y + dy;
            newBox.w = orig.w - dx; newBox.h = orig.h - dy; break;
          case 'top-right':
            newBox.y = orig.y + dy; newBox.w = orig.w + dx; newBox.h = orig.h - dy; break;
          case 'bottom-left':
            newBox.x = orig.x + dx; newBox.w = orig.w - dx; newBox.h = orig.h + dy; break;
          case 'bottom-right':
            newBox.w = orig.w + dx; newBox.h = orig.h + dy; break;
        }
        setCropBox(clampBoxLive(newBox));
      },
      onPanResponderRelease: () => { dragModeRef.current = null; },
    })
  ).current;

  const getCropResult = useCallback((): CropResult => {
    const ia = imageAreaRef.current;
    const scaleX = originalWidth / ia.w;
    const scaleY = originalHeight / ia.h;
    const relX = cropBox.x - ia.x;
    const relY = cropBox.y - ia.y;
    const origX = Math.max(0, Math.round(relX * scaleX));
    const origY = Math.max(0, Math.round(relY * scaleY));
    const origW = Math.min(originalWidth - origX, Math.round(cropBox.w * scaleX));
    const origH = Math.min(originalHeight - origY, Math.round(cropBox.h * scaleY));
    return { offsetX: origX, offsetY: origY, width: Math.max(1, origW), height: Math.max(1, origH) };
  }, [cropBox, originalWidth, originalHeight]);

  const handleConfirm = useCallback(() => {
    if (disabled) return;
    onConfirm(getCropResult(), stayOpen);
    if (stayOpen) {
      if (toastTimer.current) clearTimeout(toastTimer.current);
      setToastMsg('Queued for Insert');
      toastTimer.current = setTimeout(() => setToastMsg(null), 1500);
    }
  }, [disabled, onConfirm, getCropResult, stayOpen]);

  const handleAddToHistory = useCallback(() => {
    if (disabled) return;
    onAddToHistory(getCropResult(), stayOpen);
    if (stayOpen) {
      if (toastTimer.current) clearTimeout(toastTimer.current);
      setToastMsg('Saved to History');
      toastTimer.current = setTimeout(() => setToastMsg(null), 1500);
    }
  }, [disabled, onAddToHistory, getCropResult, stayOpen]);

  const dims = useMemo(() => {
    const relCropX = cropBox.x - imageArea.x;
    const relCropY = cropBox.y - imageArea.y;
    return {
      top: { left: 0, top: 0, right: 0, height: Math.max(0, relCropY) },
      bottom: { left: 0, right: 0, bottom: 0, top: relCropY + cropBox.h },
      left: { left: 0, top: relCropY, width: Math.max(0, relCropX), height: cropBox.h },
      right: { right: 0, top: relCropY, left: relCropX + cropBox.w, height: cropBox.h },
    };
  }, [cropBox, imageArea]);

  // Offset to place crop frame elements centered on the dashed border
  const CO = -(CORNER_THICKNESS - 1) / 2;  // corner offset from edge

  return (
    <View style={st.root}>
      {/* Header */}
      <View style={st.header}>
        <Pressable onPress={disabled ? undefined : onClose} style={st.headerBtn}>
          <Text style={st.headerBtnText}>Cancel</Text>
        </Pressable>
        <Pressable onPress={handleConfirm} style={st.headerBtn}>
          <Text style={st.headerBtnText}>Confirm</Text>
        </Pressable>
      </View>

      {/* Image + crop area */}
      <View style={st.imageContainer} {...panResponder.panHandlers}>
        <Image
          source={{ uri: imageUri }}
          style={{
            position: 'absolute',
            left: imageArea.x,
            top: imageArea.y - HEADER_HEIGHT,
            width: imageArea.w,
            height: imageArea.h,
          }}
          resizeMode="stretch"
        />

        <View
          style={[st.imageBorder, {
            left: imageArea.x - 1,
            top: imageArea.y - HEADER_HEIGHT - 1,
            width: imageArea.w + 2,
            height: imageArea.h + 2,
          }]}
          pointerEvents="none"
        />

        {/* Dim overlay */}
        <View
          style={[st.dimContainer, {
            left: imageArea.x,
            top: imageArea.y - HEADER_HEIGHT,
            width: imageArea.w,
            height: imageArea.h,
          }]}
          pointerEvents="none"
        >
          <View style={[st.dim, dims.top]} />
          <View style={[st.dim, dims.bottom]} />
          <View style={[st.dim, dims.left]} />
          <View style={[st.dim, dims.right]} />
        </View>

        {/* Crop frame */}
        <View
          style={[st.cropFrame, {
            left: cropBox.x,
            top: cropBox.y - HEADER_HEIGHT,
            width: cropBox.w,
            height: cropBox.h,
          }]}
          pointerEvents="none"
        >
          {/* L-shaped corner brackets — hollow (white fill + black border, covers dots) */}
          <View style={[st.cornerH, { top: CO, left: CO }]} />
          <View style={[st.cornerV, { top: CO, left: CO }]} />
          <View style={[st.cornerH, { top: CO, right: CO }]} />
          <View style={[st.cornerV, { top: CO, right: CO }]} />
          <View style={[st.cornerH, { bottom: CO, left: CO }]} />
          <View style={[st.cornerV, { bottom: CO, left: CO }]} />
          <View style={[st.cornerH, { bottom: CO, right: CO }]} />
          <View style={[st.cornerV, { bottom: CO, right: CO }]} />

          {/* Edge midpoint handles — filled black */}
          <View style={[st.edgeMidH, { top: -HANDLE_SHORT / 2, left: '50%', marginLeft: -HANDLE_LONG / 2 }]} />
          <View style={[st.edgeMidH, { bottom: -HANDLE_SHORT / 2, left: '50%', marginLeft: -HANDLE_LONG / 2 }]} />
          <View style={[st.edgeMidV, { left: -HANDLE_SHORT / 2, top: '50%', marginTop: -HANDLE_LONG / 2 }]} />
          <View style={[st.edgeMidV, { right: -HANDLE_SHORT / 2, top: '50%', marginTop: -HANDLE_LONG / 2 }]} />
        </View>
      </View>

      {/* Footer */}
      <View style={st.footer}>
        <Pressable
          onPress={disabled ? undefined : () => setStayOpen(v => !v)}
          style={[st.toggleBtn, stayOpen && st.toggleBtnActive]}
        >
          <View style={[st.toggleBox, stayOpen && st.toggleBoxChecked]}>
            {stayOpen && <Text style={st.toggleCheck}>✓</Text>}
          </View>
          <Text style={st.toggleLabel}>Multi</Text>
        </Pressable>
        <Pressable onPress={disabled ? undefined : onLongScreenshot} style={st.footerBtn}>
          <Text style={st.footerBtnText}>
            {hasStitchSession ? '✦ Long Screenshot' : 'Long Screenshot'}
          </Text>
        </Pressable>
        <Pressable onPress={disabled ? undefined : handleAddToHistory} style={st.footerBtn}>
          <Text style={st.footerBtnText}>Add to History</Text>
        </Pressable>
      </View>

      {/* Toast */}
      {toastMsg && (
        <View style={st.toastContainer} pointerEvents="none">
          <View style={st.toast}>
            <Text style={st.toastText}>{toastMsg}</Text>
          </View>
        </View>
      )}
    </View>
  );
};

const st = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#e8e8e8',
  },

  header: {
    height: HEADER_HEIGHT,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    backgroundColor: '#000',
  },
  headerBtn: {
    paddingVertical: 10,
    paddingHorizontal: 16,
  },
  headerBtnText: {
    fontSize: 19,
    fontWeight: '600',
    color: '#fff',
  },

  footer: {
    height: FOOTER_HEIGHT,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-evenly',
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#ccc',
    paddingHorizontal: 8,
  },
  footerBtn: {
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderWidth: 1,
    borderColor: '#000',
    backgroundColor: '#fff',
  },
  footerBtnText: {
    fontSize: 17,
    color: '#000',
  },

  toggleBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    paddingHorizontal: 10,
    gap: 6,
  },
  toggleBtnActive: {},
  toggleBox: {
    width: 22,
    height: 22,
    borderWidth: 2,
    borderColor: '#000',
    backgroundColor: '#fff',
    justifyContent: 'center',
    alignItems: 'center',
  },
  toggleBoxChecked: {
    backgroundColor: '#000',
  },
  toggleCheck: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '700',
    marginTop: -1,
  },
  toggleLabel: {
    fontSize: 15,
    color: '#000',
  },

  imageContainer: {
    flex: 1,
    position: 'relative',
  },
  imageBorder: {
    position: 'absolute',
    borderWidth: 1,
    borderColor: '#999',
  },

  dimContainer: {
    position: 'absolute',
    overflow: 'hidden',
  },
  dim: {
    position: 'absolute',
    backgroundColor: `rgba(0, 0, 0, ${DIM_OPACITY})`,
  },

  cropFrame: {
    position: 'absolute',
    borderWidth: 2,
    borderStyle: 'dotted',
    borderColor: '#000',
  },

  /* Corners & handles: white opaque fill covers dots, black border outlines them */
  cornerH: {
    position: 'absolute',
    width: CORNER_SIZE,
    height: CORNER_THICKNESS,
    backgroundColor: '#fff',
    borderWidth: 2,
    borderColor: '#000',
  },
  cornerV: {
    position: 'absolute',
    width: CORNER_THICKNESS,
    height: CORNER_SIZE,
    backgroundColor: '#fff',
    borderWidth: 2,
    borderColor: '#000',
  },

  edgeMidH: {
    position: 'absolute',
    width: HANDLE_LONG,
    height: HANDLE_SHORT,
    backgroundColor: '#fff',
    borderWidth: 2,
    borderColor: '#000',
  },
  edgeMidV: {
    position: 'absolute',
    width: HANDLE_SHORT,
    height: HANDLE_LONG,
    backgroundColor: '#fff',
    borderWidth: 2,
    borderColor: '#000',
  },

  toastContainer: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: FOOTER_HEIGHT + 20,
    alignItems: 'center',
    zIndex: 100,
  },
  toast: {
    backgroundColor: '#000',
    paddingHorizontal: 20,
    paddingVertical: 8,
  },
  toastText: {
    color: '#fff',
    fontSize: 16,
  },
});
