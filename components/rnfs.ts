import { NativeModules } from 'react-native';

type RNFSModule = typeof import('react-native-fs');

let cached: RNFSModule | null = null;

export function getRNFS(): RNFSModule | null {
  if (cached) return cached;
  if (!NativeModules.RNFSManager) return null;
  try {
    const mod = require('react-native-fs');
    const rnfs = (mod?.default ?? mod) as RNFSModule | undefined;
    if (rnfs && typeof rnfs.exists === 'function') cached = rnfs;
  } catch {
    return null;
  }
  return cached;
}
