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
} from './ScreenshotPipeline';

const HEADER_H = 56;
const CTRL_H = 120;
const PAD = 12;
const HANDLE_LEN = 40;
const HANDLE_THICK = 6;
const HIT_RADIUS = 40;
const MIN_VISIBLE = 0.05;

interface DragEdge {
  kind: 'edge';
  imageIndex: number;
  cropKey: keyof ImageCrop;
  oppositeKey: keyof ImageCrop;
  startVal: number;
  sign: number;
  pxPerUnit: number;
}

interface DragBoth {
  kind: 'both';
  cropKey: keyof ImageCrop;
  oppositeKey: keyof ImageCrop;
  startVal0: number;
  startVal1: number;
  sign: number;
  pxPerUnit0: number;
  pxPerUnit1: number;
}

type DragState = DragEdge | DragBoth;

interface LayoutResult {
  scale: number;
  dispW: number;
  dispH: number;
  originX: number;
  originY: number;
  rects: Array<{ x: number; y: number; w: number; h: number }>;
  eff: Array<{ w: number; h: number }>;
}

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

  const [images, setImages] = useState<StitchImage[]>(
    () => initialSession.images.map(img => ({ ...img, crop: { ...img.crop } }))
  );
  const [params, setParams] = useState<StitchParams>(() => ({ ...initialSession.params }));

  const imagesRef = useRef(images);
  const paramsRef = useRef(params);
  useEffect(() => { imagesRef.current = images; }, [images]);
  useEffect(() => { paramsRef.current = params; }, [params]);

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

  const handles = useMemo(() => {
    if (!layout) return null;
    const isVert = params.direction === 'vertical';
    const r0 = layout.rects[0];
    const r1 = layout.rects[1];

    const juncY = isVert ? (r0.y + r0.h + r1.y) / 2 : (r0.y + r0.h / 2);
    const juncX = isVert ? (r0.x + r0.w / 2) : (r0.x + r0.w + r1.x) / 2;

    if (isVert) {
      return {
        outerTop:    { x: r0.x + r0.w / 2, y: r0.y,          horiz: true, type: 'outerAxis' as const, imgIdx: 0, hitEdge: 'top' as const },
        outerBottom: { x: r1.x + r1.w / 2, y: r1.y + r1.h,   horiz: true, type: 'outerAxis' as const, imgIdx: 1, hitEdge: 'bottom' as const },
        overlap:     { x: juncX,            y: juncY,          horiz: true, type: 'overlap' as const },
        perpLeft:    { x: layout.originX,             y: juncY, horiz: false, type: 'perpShared' as const, edge: 'left' as const },
        perpRight:   { x: layout.originX + layout.dispW, y: juncY, horiz: false, type: 'perpShared' as const, edge: 'right' as const },
      };
    } else {
      return {
        outerTop:    { x: r0.x,             y: r0.y + r0.h / 2, horiz: false, type: 'outerAxis' as const, imgIdx: 0, hitEdge: 'left' as const },
        outerBottom: { x: r1.x + r1.w,      y: r1.y + r1.h / 2, horiz: false, type: 'outerAxis' as const, imgIdx: 1, hitEdge: 'right' as const },
        overlap:     { x: juncX,             y: juncY,            horiz: false, type: 'overlap' as const },
        perpLeft:    { x: juncX, y: layout.originY,               horiz: true, type: 'perpShared' as const, edge: 'top' as const },
        perpRight:   { x: juncX, y: layout.originY + layout.dispH, horiz: true, type: 'perpShared' as const, edge: 'bottom' as const },
      };
    }
  }, [layout, params.direction]);

  const dragRef = useRef<DragState | null>(null);
  const overlapStartRef = useRef(0);
  const dragKindRef = useRef<'edge' | 'both' | 'overlap' | null>(null);

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

        const isVert = p.direction === 'vertical';
        const r0 = lo.rects[0];
        const r1 = lo.rects[1];

        const juncY = isVert ? (r0.y + r0.h + r1.y) / 2 : (r0.y + r0.h / 2);
        const juncX = isVert ? (r0.x + r0.w / 2) : (r0.x + r0.w + r1.x) / 2;

        type HandleDef = {
          x: number; y: number;
          type: 'outerAxis' | 'overlap' | 'perpShared';
          imgIdx?: number; hitEdge?: string; edge?: string;
        };

        const allHandles: HandleDef[] = isVert ? [
          { x: r0.x + r0.w / 2, y: r0.y,          type: 'outerAxis', imgIdx: 0, hitEdge: 'top' },
          { x: r1.x + r1.w / 2, y: r1.y + r1.h,   type: 'outerAxis', imgIdx: 1, hitEdge: 'bottom' },
          { x: juncX,            y: juncY,          type: 'overlap' },
          { x: lo.originX,                   y: juncY, type: 'perpShared', edge: 'left' },
          { x: lo.originX + lo.dispW,        y: juncY, type: 'perpShared', edge: 'right' },
        ] : [
          { x: r0.x,             y: r0.y + r0.h / 2, type: 'outerAxis', imgIdx: 0, hitEdge: 'left' },
          { x: r1.x + r1.w,      y: r1.y + r1.h / 2, type: 'outerAxis', imgIdx: 1, hitEdge: 'right' },
          { x: juncX,             y: juncY,            type: 'overlap' },
          { x: juncX, y: lo.originY,                   type: 'perpShared', edge: 'top' },
          { x: juncX, y: lo.originY + lo.dispH,        type: 'perpShared', edge: 'bottom' },
        ];

        let bestDist = HIT_RADIUS;
        let bestH: HandleDef | null = null;
        for (const h of allHandles) {
          const d = Math.sqrt((pageX - h.x) ** 2 + (pageY - h.y) ** 2);
          if (d < bestDist) { bestDist = d; bestH = h; }
        }

        if (!bestH) {

          overlapStartRef.current = p.overlap;
          dragKindRef.current = 'overlap';
          dragRef.current = null;
          return;
        }

        if (bestH.type === 'overlap') {
          overlapStartRef.current = p.overlap;
          dragKindRef.current = 'overlap';
          dragRef.current = null;

        } else if (bestH.type === 'outerAxis') {

          const idx = bestH.imgIdx!;
          const hitEdge = bestH.hitEdge! as 'top' | 'bottom' | 'left' | 'right';
          const targetEdge = (hitEdge === 'top' ? 'bottom' : hitEdge === 'bottom' ? 'top' : hitEdge === 'left' ? 'right' : 'left') as 'top' | 'bottom' | 'left' | 'right';
          const cropKey = `crop${targetEdge.charAt(0).toUpperCase()}${targetEdge.slice(1)}` as keyof ImageCrop;
          const oppositeKey = `crop${hitEdge.charAt(0).toUpperCase()}${hitEdge.slice(1)}` as keyof ImageCrop;
          const isVertAxis = (hitEdge === 'top' || hitEdge === 'bottom');
          const sign = (hitEdge === 'top' || hitEdge === 'left') ? 1 : -1;
          const imgPixelSize = isVertAxis ? imgs[idx].height : imgs[idx].width;

          dragRef.current = {
            kind: 'edge',
            imageIndex: idx,
            cropKey,
            oppositeKey,
            startVal: imgs[idx].crop[cropKey],
            sign,
            pxPerUnit: imgPixelSize * lo.scale,
          };
          dragKindRef.current = 'edge';

        } else {

          const edge = bestH.edge! as 'top' | 'bottom' | 'left' | 'right';
          const cropKey = `crop${edge.charAt(0).toUpperCase()}${edge.slice(1)}` as keyof ImageCrop;
          const oppositeEdge = (edge === 'top' ? 'bottom' : edge === 'bottom' ? 'top' : edge === 'left' ? 'right' : 'left');
          const oppositeKey = `crop${oppositeEdge.charAt(0).toUpperCase()}${oppositeEdge.slice(1)}` as keyof ImageCrop;
          const isVertAxis = (edge === 'top' || edge === 'bottom');
          const sign = (edge === 'top' || edge === 'left') ? 1 : -1;

          dragRef.current = {
            kind: 'both',
            cropKey,
            oppositeKey,
            startVal0: imgs[0].crop[cropKey],
            startVal1: imgs[1].crop[cropKey],
            sign,
            pxPerUnit0: (isVertAxis ? imgs[0].height : imgs[0].width) * lo.scale,
            pxPerUnit1: (isVertAxis ? imgs[1].height : imgs[1].width) * lo.scale,
          };
          dragKindRef.current = 'both';
        }
      },

      onPanResponderMove: (_: GestureResponderEvent, g: PanResponderGestureState) => {
        if (disabled) return;

        if (dragKindRef.current === 'edge' && dragRef.current?.kind === 'edge') {
          const d = dragRef.current;
          const imgs = imagesRef.current;
          const img = imgs[d.imageIndex];
          if (!img) return;

          const isVertAxis = (d.cropKey === 'cropTop' || d.cropKey === 'cropBottom');
          const screenDelta = isVertAxis ? g.dy : g.dx;
          const cropDelta = (d.sign * screenDelta) / d.pxPerUnit;
          const maxVal = 1 - MIN_VISIBLE - img.crop[d.oppositeKey];
          const newVal = Math.max(0, Math.min(maxVal, d.startVal + cropDelta));

          setImages(prev => {
            const next = [...prev];
            next[d.imageIndex] = {
              ...next[d.imageIndex],
              crop: { ...next[d.imageIndex].crop, [d.cropKey]: newVal },
            };
            return next;
          });

        } else if (dragKindRef.current === 'both' && dragRef.current?.kind === 'both') {
          const d = dragRef.current;
          const imgs = imagesRef.current;

          const isVertAxis = (d.cropKey === 'cropTop' || d.cropKey === 'cropBottom');
          const screenDelta = isVertAxis ? g.dy : g.dx;

          const delta0 = (d.sign * screenDelta) / d.pxPerUnit0;
          const delta1 = (d.sign * screenDelta) / d.pxPerUnit1;
          const max0 = 1 - MIN_VISIBLE - imgs[0].crop[d.oppositeKey];
          const max1 = 1 - MIN_VISIBLE - imgs[1].crop[d.oppositeKey];
          const v0 = Math.max(0, Math.min(max0, d.startVal0 + delta0));
          const v1 = Math.max(0, Math.min(max1, d.startVal1 + delta1));

          setImages(prev => {
            const next = [...prev];
            next[0] = { ...next[0], crop: { ...next[0].crop, [d.cropKey]: v0 } };
            next[1] = { ...next[1], crop: { ...next[1].crop, [d.cropKey]: v1 } };
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

      onPanResponderRelease: () => { dragRef.current = null; dragKindRef.current = null; },
      onPanResponderTerminate: () => { dragRef.current = null; dragKindRef.current = null; },
    })
  ).current;

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

  if (images.length < 2 || !layout || !handles) {
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

  const isVert = params.direction === 'vertical';
  const drawOrder = params.topLayerIndex === 0 ? [1, 0] : [0, 1];

  const handleEntries = Object.values(handles);

  return (
    <View style={st.root}>
      <View style={st.header}>
        <Pressable onPress={disabled ? undefined : onCancel} style={st.headerBtn}>
          <Text style={st.headerBtnText}>Cancel</Text>
        </Pressable>
        <Text style={st.headerTitle}>Long Screenshot</Text>
        <Pressable onPress={handleConfirm} style={st.headerBtn}>
          <Text style={[st.headerBtnText, { textAlign: 'right' }]}>Confirm</Text>
        </Pressable>
      </View>

      <View style={[st.previewContainer, { height: previewH }]} {...pan.panHandlers}>

        <View
          style={[st.compositeBorder, {
            left: layout.originX - 1,
            top: layout.originY - HEADER_H - 1,
            width: layout.dispW + 2,
            height: layout.dispH + 2,
          }]}
          pointerEvents="none"
        />

        {drawOrder.map(idx => {
          const r = layout.rects[idx];
          const img = images[idx];
          const fullW = img.width * layout.scale;
          const fullH = img.height * layout.scale;
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
                style={{ width: fullW, height: fullH, marginLeft: -offsetL, marginTop: -offsetT }}
                resizeMode="stretch"
              />
            </View>
          );
        })}

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
              <View style={[st.imgLabel, { left: r.x + 4, top: r.y - HEADER_H + 4 }]} pointerEvents="none">
                <Text style={st.imgLabelText}>{idx + 1}</Text>
              </View>
            </React.Fragment>
          );
        })}

        {handleEntries.map((h, i) => (
          <View
            key={`h-${i}`}
            style={[
              st.handleBar,
              h.horiz ? st.handleH : st.handleV,
              {
                left: h.x - (h.horiz ? HANDLE_LEN / 2 : HANDLE_THICK / 2),
                top: h.y - HEADER_H - (h.horiz ? HANDLE_THICK / 2 : HANDLE_LEN / 2),
              },
              h.type === 'overlap' && st.handleOverlap,
            ]}
            pointerEvents="none"
          />
        ))}
      </View>

      <View style={st.controlPanel}>
        <View style={st.controlRow}>
          <Pressable onPress={disabled ? undefined : toggleDirection} style={st.ctrlBtn}>
            <Text style={st.ctrlBtnText}>{isVert ? '↕ Vertical' : '↔ Horizontal'}</Text>
          </Pressable>
          <Pressable onPress={disabled ? undefined : swapOrder} style={st.ctrlBtn}>
            <Text style={st.ctrlBtnText}>⇅ Swap</Text>
          </Pressable>
          <Pressable onPress={disabled ? undefined : toggleTopLayer} style={st.ctrlBtn}>
            <Text style={st.ctrlBtnText}>☰ Top: {params.topLayerIndex + 1}</Text>
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
      </View>

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

const st = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#e8e8e8' },
  header: {
    height: HEADER_H,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    backgroundColor: '#000',
  },
  headerBtn: { paddingVertical: 10, paddingHorizontal: 16, minWidth: 80 },
  headerBtnText: { fontSize: 19, fontWeight: '600', color: '#fff' },
  headerTitle: { fontSize: 18, fontWeight: '600', color: '#fff' },
  previewContainer: { position: 'relative' },
  compositeBorder: { position: 'absolute', borderWidth: 1, borderColor: '#999' },
  imgBorder: { position: 'absolute', borderWidth: 1, borderStyle: 'dashed' },
  imgLabel: { position: 'absolute', backgroundColor: '#000', paddingHorizontal: 6, paddingVertical: 2 },
  imgLabelText: { color: '#fff', fontSize: 13, fontWeight: '700' },
  overlapZone: {
    position: 'absolute',
    backgroundColor: 'rgba(0, 0, 0, 0.12)',
    borderWidth: 1,
    borderColor: 'rgba(0, 0, 0, 0.25)',
    borderStyle: 'dotted',
  },
  handleBar: { position: 'absolute', backgroundColor: '#000', borderRadius: 3 },
  handleH: { width: HANDLE_LEN, height: HANDLE_THICK },
  handleV: { width: HANDLE_THICK, height: HANDLE_LEN },
  handleOverlap: { backgroundColor: '#666' },
  controlPanel: {
    height: CTRL_H,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#ccc',
    paddingHorizontal: 12,
    paddingVertical: 8,
    justifyContent: 'center',
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
  ctrlBtnText: { fontSize: 15, color: '#000' },
  overlapLabel: { fontSize: 15, color: '#000', marginRight: 8, minWidth: 120 },
  overlapBtns: { flexDirection: 'row', gap: 6 },
  smallBtn: {
    paddingVertical: 4,
    paddingHorizontal: 10,
    borderWidth: 1,
    borderColor: '#666',
    backgroundColor: '#fff',
  },
  smallBtnText: { fontSize: 14, color: '#000' },
  emptyContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 40 },
  emptyText: { fontSize: 20, color: '#000', marginBottom: 12 },
  emptySubText: { fontSize: 16, color: '#666', textAlign: 'center' },
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
  loadingText: { fontSize: 18, color: '#000' },
});
