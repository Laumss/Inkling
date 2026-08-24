let pendingButtonId = null;

export const setPendingButton = (id) => {
  pendingButtonId = id;
};

export const checkPendingButton = () => {
  const val = pendingButtonId;
  pendingButtonId = null;
  return val;
};

export const peekPendingButton = () => {
  return pendingButtonId;
};

let _appMounted = false;
export const setAppMounted = (v) => { _appMounted = v; };
export const isAppMounted = () => _appMounted;

let _lastHostButtonEventAt = 0;
export const markHostButtonEvent = () => { _lastHostButtonEventAt = Date.now(); };
export const getLastHostButtonEventAt = () => _lastHostButtonEventAt;
