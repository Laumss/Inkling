// scripts/babel-plugin-strip-logs.js
//
// Babel plugin: removes console.* and FileLogger.* calls at compile time.
// Only loaded by babel.config.js when WITH_LOGS !== '1' (release build).
//
// Handles two forms:
//   - Standalone ExpressionStatement (incl. await-wrapped) → remove entire statement.
//   - Sub-expression (e.g. arrow shorthand in .catch(e => console.error(...)))
//     → replace with void 0 to keep syntax valid and semantics unchanged.

module.exports = function ({ types: t }) {
  const TARGETS = new Set(['console', 'FileLogger']);

  function isStripTarget(path) {
    const callee = path.get('callee');
    if (!callee.isMemberExpression()) return false;
    const object = callee.get('object');
    return object.isIdentifier() && TARGETS.has(object.node.name);
  }

  return {
    name: 'strip-logs',
    visitor: {
      CallExpression(path) {
        if (!isStripTarget(path)) return;

        // Include a wrapping await in the removal/replacement target
        const stmtCandidate = path.parentPath.isAwaitExpression()
          ? path.parentPath
          : path;

        if (stmtCandidate.parentPath.isExpressionStatement()) {
          stmtCandidate.parentPath.remove();
        } else {
          path.replaceWith(t.unaryExpression('void', t.numericLiteral(0)));
        }
      },
    },
  };
};
