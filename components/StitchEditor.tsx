/**
 * StitchEditor — Long screenshot compositing editor.
 *
 * Architecture: ONE PanResponder on the preview area.
 * On touch-start it hit-tests against 8 handle zones (2 images × 4 edges).
 * If a handle was hit → drag adjusts that ONE edge's crop fraction.
 * If nothing hit → drag adjusts overlap.
 *
 * This avoids all gesture-competition issues between child PanResponders
 * that plague React Native on Android.
 */

import React, { useState, useRef, useCallback, useMemo, useEffect } from 'react';
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
import {
  StitchSessionData,
  StitchImage,
  StitchParams,
  ImageCrop,
} from './StitchSession';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const HEADER_H = 56;
const CTRL_H = 160;
const PAD = 12;
const HANDLE_LEN = 40;       // visible bar length
const HANDLE_THICK = 6;      // visible bar thickness
const HIT_RADIUS = 36;       // touch hit-test radius from edge midpoint
const MIN_VISIBLE = 0.05;    // minimum 5% visible per axis

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type EdgeId = {
  imageIndex: number;
  edge: 'top' | 'bottom' | 'left' | 'right';
};

interface DragState {
  kind: 'edge';
  imageIndex: number;
  edge: 'top' | 'bottom' | 'left' | 'right';
  cropKey: keyof ImageCrop;
  oppositeKey: keyof ImageCrop;
  startCropVal: number;
  /** sign: +1 means "screen delta in primary axis → positive crop change" */
  sign: number;
  /** pixels-per-unit: how many screen pixels = 1.0 crop fraction */
  pxPerUnit: number;
}

interface LayoutResult {
  scale: number;
  dispW: number;
  dispH: number;
  originX: number;
  originY: number;
  /** Screen-space rects for each image (absolute, includes HEADER_H) */
  rects: Array<{ x: number; y: number; w: number; h: number }>;
  eff: Array<{ w: number; h: number }>;
}

// ---------------------------------------------------------------------------
// StitchEditor
// ---------------------------------------------------------------------------

interface StitchEditorProps {
  session: StitchSessionData;
  onConfirm: (session: StitchSessionData) => void;
  onCancel: () => void;
  disabled?: boolean;
}

export const StitchEditor: React.FC<StitchEditorProps> = ({
  session: initialSession,
  onConfirm,
  onCancel,
  disabled = false,
}) => {
  const screen = Dimensions.get('window');
  const previewH = screen.height - HEADER_H - CTRL_H;

  // Local mutable state
  const [images, setImages] = useState<StitchImage[]>(
    () => initialSession.images.map(img => ({ ...img, crop: { ...img.crop } }))
  );
  const [params, setParams] = useState<StitchParams>(() => ({ ...initialSession.params }));

  // Refs mirroring state (for PanResponder callbacks which can't re-capture state)
  const imagesRef = useRef(images);
  const paramsRef = useRef(params);
  useEffect(() => { imagesRef.current = images; }, [images]);
  useEffect(() => { paramsRef.current = params; }, [params]);

  // -------------------------------------------------------------------------
  // Layout calculation
  // -------------------------------------------------------------------------

  const layout: LayoutResult | null = useMemo(() => {
    if (images.length < 2) return null;
    const dir = params.direction;

    const eff = images.map(img => ({
      w: img.width * (1 - img.crop.cropLeft - img.crop.cropRight),
      h: img.height * (1 - img.crop.cropTop - img.crop.cropBottom),
    }));

    let totalW: number, totalH: number;
    if (dir === 'vertical') {
      totalW = Math.max(eff[0].w, eff[1].w);
      totalH = eff[0].h + eff[1].h - params.overlap;
    } else {
      totalW = eff[0].w + eff[1].w - params.overlap;
      totalH = Math.max(eff[0].h, eff[1].h);
    }

    const availW = screen.width - PAD * 2;
    const availH = previewH - PAD * 2;
    const scale = Math.min(availW / Math.max(totalW, 1), availH / Math.max(totalH, 1), 1);

    const dispW = totalW * scale;
    const dispH = totalH * scale;
    const originX = (screen.width - dispW) / 2;
    const originY = HEADER_H + (previewH - dispH) / 2;

    const rects = [
      { x: 0, y: 0, w: eff[0].w * scale, h: eff[0].h * scale },
      { x: 0, y: 0, w: eff[1].w * scale, h: eff[1].h * scale },
    ];
    if (dir === 'vertical') {
      rects[0].x = originX; rects[0].y = originY;
      rects[1].x = originX; rects[1].y = originY + eff[0].h * scale - params.overlap * scale;
    } else {
      rects[0].x = originX; rects[0].y = originY;
      rects[1].x = originX + eff[0].w * scale - params.overlap * scale; rects[1].y = originY;
    }

    return { scale, dispW, dispH, originX, originY, rects, eff };
  }, [images, params, screen, previewH]);

  const layoutRef = useRef(layout);
  useEffect(() => { layoutRef.current = layout; }, [layout]);

  // -------------------------------------------------------------------------
  // Compute handle screen positions (for rendering AND hit-testing)
  // -------------------------------------------------------------------------

  const handlePositions = useMemo(() => {
    if (!layout) return [];
    return [0, 1].map(idx => {
      const r = layout.rects[idx];
      const midX = r.x + r.w / 2;
      const midY = r.y + r.h / 2;
      return {
        top:    { x: midX,      y: r.y },
        bottom: { x: midX,      y: r.y + r.h },
        left:   { x: r.x,       y: midY },
        right:  { x: r.x + r.w, y: midY },
      };
    });
  }, [layout]);

  // -------------------------------------------------------------------------
  // Single PanResponder — hit-test on grant, track drag, update state
  // -------------------------------------------------------------------------

  const dragRef = useRef<DragState | null>(null);
  const overlapStartRef = useRef(0);
  const dragKindRef = useRef<'edge' | 'overlap' | null>(null);

  const pan = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => !disabled,
      onMoveShouldSetPanResponder: () => !disabled,
      onPanResponderGrant: (evt: GestureResponderEvent) => {
        if (disabled) return;
        const { pageX, pageY } = evt.nativeEvent;
        const lo = layoutRef.current;
        const imgs = imagesRef.current;
        const p = paramsRef.current;
        if (!lo || imgs.length < 2) return;

        // Determine which edges are "shared boundary" (adjust overlap, not crop)
        const isSharedBoundary = (idx: number, edge: string): boolean => {
          if (p.direction === 'vertical') {
            return (idx === 0 && edge === 'bottom') || (idx === 1 && edge === 'top');
          } else {
            return (idx === 0 && edge === 'right') || (idx === 1 && edge === 'left');
          }
        };

        // Hit-test: find nearest handle within HIT_RADIUS
        let bestDist = HIT_RADIUS;
        let bestEdge: EdgeId | null = null;

        for (let idx = 0; idx < 2; idx++) {
          const r = lo.rects[idx];
          const midX = r.x + r.w / 2;
          const midY = r.y + r.h / 2;
          const edges: Array<{ edge: 'top' | 'bottom' | 'left' | 'right'; hx: number; hy: number }> = [
            { edge: 'top',    hx: midX,      hy: r.y },
            { edge: 'bottom', hx: midX,      hy: r.y + r.h },
            { edge: 'left',   hx: r.x,       hy: midY },
            { edge: 'right',  hx: r.x + r.w, hy: midY },
          ];
          for (const e of edges) {
            const dist = Math.sqrt((pageX - e.hx) ** 2 + (pageY - e.hy) ** 2);
            if (dist < bestDist) {
              bestDist = dist;
              bestEdge = { imageIndex: idx, edge: e.edge };
            }
          }
        }

        if (bestEdge && isSharedBoundary(bestEdge.imageIndex, bestEdge.edge)) {
          // Shared boundary → overlap drag
          overlapStartRef.current = p.overlap;
          dragKindRef.current = 'overlap';
          dragRef.current = null;
        } else if (bestEdge) {
          // Outer edge → SAME image, OPPOSITE edge crop:
          //   Dragging img1.bottom up → increases img1.cropTop (trims img1 near seam)
          //   Dragging img0.top down  → increases img0.cropBottom (trims img0 near seam)
          const hitIdx = bestEdge.imageIndex;
          const hitEdge = bestEdge.edge;

          // Target: SAME image, opposite edge
          const targetIdx = hitIdx;
          const targetEdge = (
            hitEdge === 'top' ? 'bottom' :
            hitEdge === 'bottom' ? 'top' :
            hitEdge === 'left' ? 'right' : 'left'
          ) as 'top' | 'bottom' | 'left' | 'right';

          const targetImg = imgs[targetIdx];
          const cropKey = (`crop${targetEdge.charAt(0).toUpperCase()}${targetEdge.slice(1)}`) as keyof ImageCrop;
          const oppositeKey = (
            targetEdge === 'top' ? 'cropBottom' :
            targetEdge === 'bottom' ? 'cropTop' :
            targetEdge === 'left' ? 'cropRight' : 'cropLeft'
          ) as keyof ImageCrop;

          // Sign based on HIT edge direction (not target):
          // top/left hit: drag inward (down/right) → positive delta → increases target crop
          // bottom/right hit: drag inward (up/left) → negative delta → sign=-1 inverts to positive
          const isVertAxis = (hitEdge === 'top' || hitEdge === 'bottom');
          const sign = (hitEdge === 'top' || hitEdge === 'left') ? 1 : -1;
          const imgPixelSize = isVertAxis ? targetImg.height : targetImg.width;

          dragRef.current = {
            kind: 'edge',
            imageIndex: targetIdx,
            edge: targetEdge,
            cropKey,
            oppositeKey,
            startCropVal: targetImg.crop[cropKey],
            sign,
            pxPerUnit: imgPixelSize * lo.scale,
          };
          dragKindRef.current = 'edge';
        } else {
          // No handle hit → overlap drag
          overlapStartRef.current = p.overlap;
          dragKindRef.current = 'overlap';
          dragRef.current = null;
        }
      },

      onPanResponderMove: (_: GestureResponderEvent, g: PanResponderGestureState) => {
        if (disabled) return;

        if (dragKindRef.current === 'edge' && dragRef.current) {
          const d = dragRef.current;
          const imgs = imagesRef.current;
          const img = imgs[d.imageIndex];
          if (!img) return;

          const isVertAxis = (d.edge === 'top' || d.edge === 'bottom');
          const screenDelta = isVertAxis ? g.dy : g.dx;
          const cropDelta = (d.sign * screenDelta) / d.pxPerUnit;

          const oppositeVal = img.crop[d.oppositeKey];
          const maxVal = 1 - MIN_VISIBLE - oppositeVal;
          const newVal = Math.max(0, Math.min(maxVal, d.startCropVal + cropDelta));

          setImages(prev => {
            const next = [...prev];
            next[d.imageIndex] = {
              ...next[d.imageIndex],
              crop: { ...next[d.imageIndex].crop, [d.cropKey]: newVal },
            };
            return next;
          });

        } else if (dragKindRef.current === 'overlap') {
          const lo = layoutRef.current;
          const p = paramsRef.current;
          const imgs = imagesRef.current;
          if (!lo || imgs.length < 2) return;

          const isVert = p.direction === 'vertical';
          const screenDelta = isVert ? -g.dy : -g.dx;
          const imgDelta = screenDelta / lo.scale;

          const dim0 = isVert
            ? imgs[0].height * (1 - imgs[0].crop.cropTop - imgs[0].crop.cropBottom)
            : imgs[0].width * (1 - imgs[0].crop.cropLeft - imgs[0].crop.cropRight);
          const dim1 = isVert
            ? imgs[1].height * (1 - imgs[1].crop.cropTop - imgs[1].crop.cropBottom)
            : imgs[1].width * (1 - imgs[1].crop.cropLeft - imgs[1].crop.cropRight);
          const maxOvl = Math.min(dim0, dim1) * 0.8;
          const newOvl = Math.max(0, Math.min(maxOvl, overlapStartRef.current + imgDelta));

          setParams(prev => ({ ...prev, overlap: Math.round(newOvl) }));
        }
      },

      onPanResponderRelease: () => {
        dragRef.current = null;
        dragKindRef.current = null;
      },
      onPanResponderTerminate: () => {
        dragRef.current = null;
        dragKindRef.current = null;
      },
    })
  ).current;

  // -------------------------------------------------------------------------
  // Control panel actions
  // -------------------------------------------------------------------------

  const toggleDirection = useCallback(() => {
    setParams(p => ({
      ...p,
      direction: p.direction === 'vertical' ? 'horizontal' : 'vertical',
      overlap: Math.round(p.overlap * 0.5),
    }));
  }, []);

  const swapOrder = useCallback(() => {
    setImages(prev => [prev[1], prev[0]]);
  }, []);

  const toggleTopLayer = useCallback(() => {
    setParams(p => ({ ...p, topLayerIndex: p.topLayerIndex === 0 ? 1 : 0 }));
  }, []);

  const adjustOverlap = useCallback((delta: number) => {
    setParams(p => {
      const imgs = imagesRef.current;
      if (imgs.length < 2) return p;
      const dim0 = p.direction === 'vertical'
        ? imgs[0].height * (1 - imgs[0].crop.cropTop - imgs[0].crop.cropBottom)
        : imgs[0].width * (1 - imgs[0].crop.cropLeft - imgs[0].crop.cropRight);
      const dim1 = p.direction === 'vertical'
        ? imgs[1].height * (1 - imgs[1].crop.cropTop - imgs[1].crop.cropBottom)
        : imgs[1].width * (1 - imgs[1].crop.cropLeft - imgs[1].crop.cropRight);
      const maxOvl = Math.min(dim0, dim1) * 0.8;
      return { ...p, overlap: Math.max(0, Math.min(Math.round(maxOvl), p.overlap + delta)) };
    });
  }, []);

  const handleConfirm = useCallback(() => {
    if (disabled) return;
    onConfirm({ ...initialSession, images, params });
  }, [disabled, images, params, initialSession, onConfirm]);

  // -------------------------------------------------------------------------
  // Render — waiting for second image
  // -------------------------------------------------------------------------

  if (images.length < 2 || !layout) {
    return (
      <View style={st.root}>
        <View style={st.header}>
          <Pressable onPress={onCancel} style={st.headerBtn}>
            <Text style={st.headerBtnText}>Cancel</Text>
          </Pressable>
          <Text style={st.headerTitle}>Long Screenshot</Text>
          <View style={st.headerBtn} />
        </View>
        <View style={st.emptyContainer}>
          <Text style={st.emptyText}>Waiting for second image…</Text>
          <Text style={st.emptySubText}>Flip the page, then press the DOC button again.</Text>
        </View>
      </View>
    );
  }

  // -------------------------------------------------------------------------
  // Render — editor
  // -------------------------------------------------------------------------

  const isVert = params.direction === 'vertical';
  const drawOrder = params.topLayerIndex === 0 ? [1, 0] : [0, 1];

  return (
    <View style={st.root}>
      {/* Header */}
      <View style={st.header}>
        <Pressable onPress={disabled ? undefined : onCancel} style={st.headerBtn}>
          <Text style={st.headerBtnText}>Cancel</Text>
        </Pressable>
        <Text style={st.headerTitle}>Long Screenshot</Text>
        <Pressable onPress={handleConfirm} style={st.headerBtn}>
          <Text style={[st.headerBtnText, { textAlign: 'right' }]}>Confirm</Text>
        </Pressable>
      </View>

      {/* Preview area — SINGLE PanResponder handles everything */}
      <View style={[st.previewContainer, { height: previewH }]} {...pan.panHandlers}>

        {/* Composite border */}
        <View
          style={[st.compositeBorder, {
            left: layout.originX - 1,
            top: layout.originY - HEADER_H - 1,
            width: layout.dispW + 2,
            height: layout.dispH + 2,
          }]}
          pointerEvents="none"
        />

        {/* Images in layer order — clipped to show only cropped portion */}
        {drawOrder.map(idx => {
          const r = layout.rects[idx];
          const img = images[idx];
          // Full image size at display scale
          const fullW = img.width * layout.scale;
          const fullH = img.height * layout.scale;
          // Offset to skip cropped-out area
          const offsetL = img.crop.cropLeft * img.width * layout.scale;
          const offsetT = img.crop.cropTop * img.height * layout.scale;
          return (
            <View
              key={`img-clip-${idx}`}
              style={{
                position: 'absolute',
                left: r.x,
                top: r.y - HEADER_H,
                width: r.w,
                height: r.h,
                overflow: 'hidden',
              }}
            >
              <Image
                source={{ uri: `file://${img.path}` }}
                style={{
                  width: fullW,
                  height: fullH,
                  marginLeft: -offsetL,
                  marginTop: -offsetT,
                }}
                resizeMode="stretch"
              />
            </View>
          );
        })}

        {/* Overlap zone indicator */}
        {params.overlap > 0 && (
          <View
            style={[st.overlapZone, isVert ? {
              left: layout.originX,
              top: layout.rects[1].y - HEADER_H,
              width: layout.dispW,
              height: Math.min(params.overlap * layout.scale, layout.rects[0].h),
            } : {
              left: layout.rects[1].x,
              top: layout.originY - HEADER_H,
              width: Math.min(params.overlap * layout.scale, layout.rects[0].w),
              height: layout.dispH,
            }]}
            pointerEvents="none"
          />
        )}

        {/* Per-image dashed borders + labels */}
        {[0, 1].map(idx => {
          const r = layout.rects[idx];
          return (
            <React.Fragment key={`deco-${idx}`}>
              <View
                style={[st.imgBorder, {
                  left: r.x,
                  top: r.y - HEADER_H,
                  width: r.w,
                  height: r.h,
                  borderColor: idx === 0 ? '#888' : '#444',
                }]}
                pointerEvents="none"
              />
              <View
                style={[st.imgLabel, {
                  left: r.x + 4,
                  top: r.y - HEADER_H + 4,
                }]}
                pointerEvents="none"
              >
                <Text style={st.imgLabelText}>{idx + 1}</Text>
              </View>
            </React.Fragment>
          );
        })}

        {/* Visual handle indicators (non-interactive — PanResponder does hit-testing) */}
        {handlePositions.map((hp, idx) => {
          // Determine which edges are shared boundary (only render once)
          const skipTop = isVert && idx === 1;    // img1 top = shared → already shown as img0 bottom
          const skipBottom = isVert && idx === 0 && false; // img0 bottom = shared → render it
          const skipLeft = !isVert && idx === 1;  // img1 left = shared → already shown as img0 right
          const skipRight = !isVert && idx === 0 && false;

          // Shared boundary uses a wider handle to indicate it controls overlap
          const isSharedBottom = isVert && idx === 0;
          const isSharedRight = !isVert && idx === 0;

          return (
            <React.Fragment key={`vis-handles-${idx}`}>
              {/* Top */}
              {!skipTop && (
                <View style={[st.handleBar, st.handleH, {
                  left: hp.top.x - HANDLE_LEN / 2,
                  top: hp.top.y - HEADER_H - HANDLE_THICK / 2,
                }]} pointerEvents="none" />
              )}
              {/* Bottom */}
              <View style={[st.handleBar, isSharedBottom ? st.handleHShared : st.handleH, {
                left: hp.bottom.x - (isSharedBottom ? HANDLE_LEN * 0.75 : HANDLE_LEN / 2),
                top: hp.bottom.y - HEADER_H - HANDLE_THICK / 2,
              }]} pointerEvents="none" />
              {/* Left */}
              {!skipLeft && (
                <View style={[st.handleBar, st.handleV, {
                  left: hp.left.x - HANDLE_THICK / 2,
                  top: hp.left.y - HEADER_H - HANDLE_LEN / 2,
                }]} pointerEvents="none" />
              )}
              {/* Right */}
              <View style={[st.handleBar, isSharedRight ? st.handleVShared : st.handleV, {
                left: hp.right.x - HANDLE_THICK / 2,
                top: hp.right.y - HEADER_H - (isSharedRight ? HANDLE_LEN * 0.75 : HANDLE_LEN / 2),
              }]} pointerEvents="none" />
            </React.Fragment>
          );
        })}
      </View>

      {/* Control panel */}
      <View style={st.controlPanel}>
        <View style={st.controlRow}>
          <Pressable onPress={disabled ? undefined : toggleDirection} style={st.ctrlBtn}>
            <Text style={st.ctrlBtnText}>
              {isVert ? '↕ Vertical' : '↔ Horizontal'}
            </Text>
          </Pressable>
          <Pressable onPress={disabled ? undefined : swapOrder} style={st.ctrlBtn}>
            <Text style={st.ctrlBtnText}>⇅ Swap</Text>
          </Pressable>
          <Pressable onPress={disabled ? undefined : toggleTopLayer} style={st.ctrlBtn}>
            <Text style={st.ctrlBtnText}>
              ☰ Top: {params.topLayerIndex + 1}
            </Text>
          </Pressable>
        </View>

        <View style={st.controlRow}>
          <Text style={st.overlapLabel}>Overlap: {params.overlap}px</Text>
          <View style={st.overlapBtns}>
            <Pressable onPress={() => adjustOverlap(-50)} style={st.smallBtn}>
              <Text style={st.smallBtnText}>−50</Text>
            </Pressable>
            <Pressable onPress={() => adjustOverlap(-10)} style={st.smallBtn}>
              <Text style={st.smallBtnText}>−10</Text>
            </Pressable>
            <Pressable onPress={() => adjustOverlap(10)} style={st.smallBtn}>
              <Text style={st.smallBtnText}>+10</Text>
            </Pressable>
            <Pressable onPress={() => adjustOverlap(50)} style={st.smallBtn}>
              <Text style={st.smallBtnText}>+50</Text>
            </Pressable>
          </View>
        </View>

        <Text style={st.hintText}>
          Outer edges trim near the seam. Shared boundary adjusts overlap.
        </Text>
      </View>

      {/* Compositing loading overlay */}
      {disabled && (
        <View style={st.loadingOverlay}>
          <View style={st.loadingBox}>
            <Text style={st.loadingText}>Compositing…</Text>
          </View>
        </View>
      )}
    </View>
  );
};

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const st = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#e8e8e8',
  },
  header: {
    height: HEADER_H,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    backgroundColor: '#000',
  },
  headerBtn: {
    paddingVertical: 10,
    paddingHorizontal: 16,
    minWidth: 80,
  },
  headerBtnText: {
    fontSize: 19,
    fontWeight: '600',
    color: '#fff',
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#fff',
  },
  previewContainer: {
    position: 'relative',
  },
  compositeBorder: {
    position: 'absolute',
    borderWidth: 1,
    borderColor: '#999',
  },
  imgBorder: {
    position: 'absolute',
    borderWidth: 1,
    borderStyle: 'dashed',
  },
  imgLabel: {
    position: 'absolute',
    backgroundColor: '#000',
    paddingHorizontal: 6,
    paddingVertical: 2,
  },
  imgLabelText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '700',
  },
  overlapZone: {
    position: 'absolute',
    backgroundColor: 'rgba(0, 0, 0, 0.12)',
    borderWidth: 1,
    borderColor: 'rgba(0, 0, 0, 0.25)',
    borderStyle: 'dotted',
  },

  // Handle visual indicators
  handleBar: {
    position: 'absolute',
    backgroundColor: '#000',
    borderRadius: 3,
  },
  handleH: {
    width: HANDLE_LEN,
    height: HANDLE_THICK,
  },
  handleV: {
    width: HANDLE_THICK,
    height: HANDLE_LEN,
  },
  handleHShared: {
    width: HANDLE_LEN * 1.5,
    height: HANDLE_THICK,
    borderWidth: 1,
    borderColor: '#666',
    backgroundColor: '#fff',
  },
  handleVShared: {
    width: HANDLE_THICK,
    height: HANDLE_LEN * 1.5,
    borderWidth: 1,
    borderColor: '#666',
    backgroundColor: '#fff',
  },

  // Control panel
  controlPanel: {
    height: CTRL_H,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#ccc',
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  controlRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 6,
    gap: 10,
  },
  ctrlBtn: {
    paddingVertical: 6,
    paddingHorizontal: 14,
    borderWidth: 1,
    borderColor: '#000',
    backgroundColor: '#fff',
  },
  ctrlBtnText: {
    fontSize: 15,
    color: '#000',
  },
  overlapLabel: {
    fontSize: 15,
    color: '#000',
    marginRight: 8,
    minWidth: 120,
  },
  overlapBtns: {
    flexDirection: 'row',
    gap: 6,
  },
  smallBtn: {
    paddingVertical: 4,
    paddingHorizontal: 10,
    borderWidth: 1,
    borderColor: '#666',
    backgroundColor: '#fff',
  },
  smallBtnText: {
    fontSize: 14,
    color: '#000',
  },
  hintText: {
    fontSize: 12,
    color: '#888',
    textAlign: 'center',
    marginTop: 2,
  },

  // Empty state
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 40,
  },
  emptyText: {
    fontSize: 20,
    color: '#000',
    marginBottom: 12,
  },
  emptySubText: {
    fontSize: 16,
    color: '#666',
    textAlign: 'center',
  },

  // Loading overlay (shown during compositing)
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0, 0, 0, 0.15)',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 50,
  },
  loadingBox: {
    backgroundColor: '#fff',
    borderWidth: 1,
    borderColor: '#000',
    paddingHorizontal: 40,
    paddingVertical: 16,
  },
  loadingText: {
    fontSize: 18,
    color: '#000',
  },
});
