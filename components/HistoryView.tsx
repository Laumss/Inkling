/**
 * HistoryView — Select a past screenshot to insert into note.
 * E-ink optimized: large tap targets, high contrast, no animations.
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View, Text, StyleSheet, Pressable, FlatList,
  Image, Dimensions,
} from 'react-native';
import { StagingService, HistoryEntry } from './StagingService';

const screenWidth = Dimensions.get('window').width;
const screenHeight = Dimensions.get('window').height;
const PANEL_WIDTH = screenWidth * 0.72;
const PANEL_HEIGHT = screenHeight * 0.78;

interface Props {
  onInsert: (path: string) => void;
  onClose: () => void;
}

export const HistoryView: React.FC<Props> = ({ onInsert, onClose }) => {
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [showClearDialog, setShowClearDialog] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    const h = await StagingService.getHistory();
    setHistory(h);
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleClear = useCallback(() => {
    setShowClearDialog(true);
  }, []);

  const confirmClear = useCallback(async () => {
    setShowClearDialog(false);
    await StagingService.clearHistory();
    load();
  }, [load]);

  const formatTime = (ts: number) => {
    const d = new Date(ts);
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const min = String(d.getMinutes()).padStart(2, '0');
    return `${mm}-${dd} ${hh}:${min}`;
  };

  return (
    <View style={s.overlay}>
      <View style={[s.panel, { width: PANEL_WIDTH, height: PANEL_HEIGHT }]}>
        {/* Title bar */}
        <View style={s.titleBar}>
          <Pressable onPress={handleClear} style={s.closeBtn} hitSlop={8}>
            <Text style={s.closeBtnText}>Clear History</Text>
          </Pressable>
          <Text style={s.titleText}>Screenshot History</Text>
          <Pressable onPress={onClose} style={s.closeBtn} hitSlop={8}>
            <Text style={s.closeBtnText}>Close</Text>
          </Pressable>
        </View>

        {/* Content */}
        <View style={s.contentArea}>
          {loading ? (
            <View style={s.center}>
              <Text style={s.hint}>Loading...</Text>
            </View>
          ) : history.length === 0 ? (
            <View style={s.center}>
              <Text style={s.hint}>No screenshots yet</Text>
              <Text style={s.hintSub}>Screenshots captured from a document will appear here</Text>
            </View>
          ) : (
            <FlatList
              data={history}
              keyExtractor={item => String(item.timestamp)}
              numColumns={2}
              contentContainerStyle={s.grid}
              renderItem={({ item }) => (
                <Pressable
                  style={s.card}
                  onPress={() => onInsert(item.path)}
                >
                  <Image
                    source={{ uri: `file://${item.path}` }}
                    style={s.thumb}
                    resizeMode="cover"
                  />
                  <Text style={s.cardTime}>{formatTime(item.timestamp)}</Text>
                  <Text style={s.cardAction}>Tap to insert</Text>
                </Pressable>
              )}
            />
          )}
        </View>
      </View>

      {showClearDialog && (
        <View style={s.dialogOverlay}>
          <View style={s.dialog}>
            <Text style={s.dialogTitle}>Clear History</Text>
            <View style={s.dialogBody}>
              <Text style={s.dialogIcon}>&#9432;</Text>
              <Text style={s.dialogMsg}>Delete all screenshot history?</Text>
            </View>
            <View style={s.dialogActions}>
              <Pressable onPress={() => setShowClearDialog(false)} style={s.dialogBtnOutline}>
                <Text style={s.dialogBtnOutlineText}>Cancel</Text>
              </Pressable>
              <Pressable onPress={confirmClear} style={s.dialogBtnFilled}>
                <Text style={s.dialogBtnFilledText}>Clear</Text>
              </Pressable>
            </View>
          </View>
        </View>
      )}
    </View>
  );
};

const s = StyleSheet.create({
  overlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'center',
    alignItems: 'center',
  },
  panel: {
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#000000',
    borderRadius: 8,
    overflow: 'hidden',
  },
  titleBar: {
    flexDirection: 'row',
    paddingVertical: 20,
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#D8D8D8',
  },
  titleText: { fontSize: 22, fontWeight: '600', color: '#000000' },
  closeBtn: {
    paddingVertical: 6,
    paddingHorizontal: 14,
    borderWidth: 1,
    borderColor: '#999999',
    borderRadius: 4,
  },
  closeBtnText: { fontSize: 13, fontWeight: '500', color: '#333333' },

  contentArea: { flex: 1 },

  center: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 40 },
  hint: { fontSize: 18, color: '#333', fontWeight: 'bold' },
  hintSub: { fontSize: 13, color: '#999', marginTop: 8, textAlign: 'center' },

  grid: { padding: 12 },
  card: {
    flex: 1,
    margin: 8,
    borderWidth: 1,
    borderColor: '#D8D8D8',
    borderRadius: 6,
    overflow: 'hidden',
    backgroundColor: '#F5F5F5',
  },
  thumb: {
    width: '100%',
    aspectRatio: 0.75,
    backgroundColor: '#E0E0E0',
  },
  cardTime: {
    fontSize: 13, fontWeight: '500', color: '#000',
    paddingHorizontal: 8, paddingTop: 6,
  },
  cardAction: {
    fontSize: 12, color: '#666666',
    paddingHorizontal: 8, paddingBottom: 8,
  },

  dialogOverlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'center',
    alignItems: 'center',
  },
  dialog: {
    backgroundColor: '#fff',
    borderWidth: 1,
    borderColor: '#000',
    paddingTop: 12,
    paddingHorizontal: 24,
    paddingBottom: 20,
    minWidth: 440,
    maxWidth: 500,
  },
  dialogTitle: {
    fontSize: 19,
    fontWeight: 'normal',
    color: '#000',
    marginBottom: 10,
    marginLeft: 4,
    marginTop: 4,
  },
  dialogBody: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 20,
    marginLeft: 2,
  },
  dialogIcon: { fontSize: 34, color: '#000', marginRight: 10 },
  dialogMsg: { fontSize: 20, color: '#000', flex: 1, lineHeight: 28 },
  dialogActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
    marginBottom: 14,
    marginRight: 2,
  },
  dialogBtnOutline: {
    paddingVertical: 7,
    paddingHorizontal: 26,
    borderWidth: 1,
    borderColor: '#000',
    backgroundColor: '#fff',
  },
  dialogBtnOutlineText: { fontSize: 18, fontWeight: 'normal', color: '#000' },
  dialogBtnFilled: {
    paddingVertical: 7,
    paddingHorizontal: 26,
    borderWidth: 1,
    borderColor: '#000',
    backgroundColor: '#000',
  },
  dialogBtnFilledText: { fontSize: 18, fontWeight: 'normal', color: '#fff' },
});
