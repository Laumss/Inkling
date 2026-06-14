const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

// Partition metro transform cache by WITH_LOGS so log-included and log-stripped
// builds use separate buckets, avoiding stale results when switching modes.
const config = {
  cacheVersion: process.env.WITH_LOGS === '1' ? 'with-logs' : 'no-logs',
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
