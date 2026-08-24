import { NativeUIUtils, PluginManager } from 'sn-plugin-lib';

import FloatingToolbarBridge, { ENABLE_DEBUG } from './FloatingToolbarBridge';
import { t } from './i18n';

const PERM_FILE_READ = 'plugin.permission.FILE:READ';
const PERM_FILE_WRITE = 'plugin.permission.FILE:WRITE';
const PERM_FILE_DELETE = 'plugin.permission.FILE:DELETE';
const PERM_INTERNET = 'plugin.permission.INTERNET';

let permissionFlow: Promise<boolean> | null = null;

function isGranted(status: unknown): boolean {
  const n = typeof status === 'number' ? status : Number(status);
  return n === 1 || n === 2;
}

function log(message: string, extra?: unknown) {
  if (!ENABLE_DEBUG) return;
  if (extra === undefined) console.error(message);
  else console.error(message, extra);
}

async function requestViaNative(permission: string): Promise<boolean> {
  switch (permission) {
    case PERM_FILE_READ:
      return FloatingToolbarBridge.requestFileReadPermission();
    case PERM_FILE_WRITE:
      return FloatingToolbarBridge.requestFileWritePermission();
    case PERM_FILE_DELETE:
      return FloatingToolbarBridge.requestFileDeletePermission();
    case PERM_INTERNET:
      return FloatingToolbarBridge.requestInternetPermission();
    default:
      return false;
  }
}

async function ensureHostPermission(permission: string, desc: string): Promise<boolean> {
  try {
    const current = await PluginManager.hasPermission(permission);
    log(`[PermissionCoordinator] has ${permission} status=${current}`);
    if (isGranted(current)) return true;
  } catch (error) {
    log(`[PermissionCoordinator] has ${permission} failed`, error);
  }

  log(`[PermissionCoordinator] request ${permission}`);
  try {
    const result = await PluginManager.requestPermission(permission, desc);
    log(`[PermissionCoordinator] request ${permission} status=${result}`);
    if (isGranted(result)) return true;
  } catch (error) {
    log(`[PermissionCoordinator] request ${permission} via PluginManager failed`, error);
  }

  const nativeGranted = await requestViaNative(permission);
  log(`[PermissionCoordinator] request ${permission} via native granted=${nativeGranted}`);
  return nativeGranted;
}

async function runInitialPermissionFlow(): Promise<boolean> {
  const steps: Array<{ permission: string; descKey: 'perm_desc_file_read' | 'perm_desc_file_write' | 'perm_desc_file_delete' | 'perm_desc_internet' }> = [
    { permission: PERM_FILE_READ, descKey: 'perm_desc_file_read' },
    { permission: PERM_FILE_WRITE, descKey: 'perm_desc_file_write' },
    { permission: PERM_FILE_DELETE, descKey: 'perm_desc_file_delete' },
    { permission: PERM_INTERNET, descKey: 'perm_desc_internet' },
  ];

  for (const step of steps) {
    const granted = await ensureHostPermission(step.permission, t(step.descKey));
    if (!granted) {
      log(`[PermissionCoordinator] ${step.permission} denied after request`);
      return false;
    }
  }

  return true;
}

export function ensureInitialPermissions(): Promise<boolean> {
  if (permissionFlow) return permissionFlow;

  permissionFlow = runInitialPermissionFlow()
    .catch(error => {
      log('[PermissionCoordinator][FLOW-EXCEPTION]', error);
      NativeUIUtils.showErrorTipDialog(t('initial_permissions_required'));
      return false;
    })
    .finally(() => {
      permissionFlow = null;
    });

  return permissionFlow;
}
