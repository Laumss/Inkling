# SQLite Local Storage Pattern

---

## Pattern 11: SQLite Local Storage in a Plugin

Plugins can use SQLite for persistent data storage via `react-native-sqlite-storage`. The database path must be prefixed with `plugins/<pluginID>/` to stay within the plugin's sandboxed directory.

### Setup

Place a patched copy of the library under `node_change/react-native-sqlite-storage/` and reference it in `package.json`:

```json
{
  "dependencies": {
    "react-native-sqlite-storage": "file:./node_change/react-native-sqlite-storage"
  }
}
```

The `node_change/` directory holds third-party npm packages that need source-level patches to work inside the plugin environment. The build script (`buildPlugin.sh`) auto-detects native code there and compiles it into `app.npk`.

### Database Initialization

```ts
// src/db/index.ts
import SQLite from 'react-native-sqlite-storage';
import PluginConfig from '../../PluginConfig.json';

// DB path prefix — keeps files inside the plugin's sandbox
const PLUGIN_ID = PluginConfig.pluginID;
const DB_LOCATION = `plugins/${PLUGIN_ID}/`;

let db: SQLite.SQLiteDatabase | null = null;

export async function initDB() {
  db = SQLite.openDatabase(
    { name: 'my_plugin.db', location: DB_LOCATION },
    () => console.log('DB opened'),
    (err) => console.error('DB open error', err),
  );
  // Create tables
  await runSQL(`CREATE TABLE IF NOT EXISTS Items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    path TEXT NOT NULL,
    created_at INTEGER
  )`);
}

export function runSQL(sql: string, args: any[] = []): Promise<{ rows: any[]; insertId?: number }> {
  return new Promise((resolve, reject) => {
    db!.transaction(tx => {
      tx.executeSql(
        sql, args,
        (_, result) => resolve({ rows: result.rows.raw(), insertId: result.insertId }),
        (_, err)    => { reject(err); return false; },
      );
    });
  });
}
```
