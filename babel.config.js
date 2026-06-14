// babel.config.js
//
// By default (WITH_LOGS !== '1') strips console/FileLogger calls at compile time
// for a clean release build. Set WITH_LOGS=1 to keep logs (debug build).
// buildPlugin.sh --with-logs / buildPlugin.ps1 -WithLogs set this env var.

module.exports = function (api) {
  const withLogs = process.env.WITH_LOGS === '1';

  // Cache per WITH_LOGS value so switching modes uses the right cache without --reset-cache.
  api.cache.using(() => process.env.WITH_LOGS);

  return {
    presets: ['module:@react-native/babel-preset'],
    plugins: withLogs ? [] : ['./scripts/babel-plugin-strip-logs.js'],
  };
};
