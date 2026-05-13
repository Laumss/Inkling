let pendingButtonId = null;

export const setPendingButton = (id) => {
  pendingButtonId = id;
};

export const checkPendingButton = () => {
  const val = pendingButtonId;
  pendingButtonId = null;
  return val;
};

/** Read pending button ID without clearing it */
export const peekPendingButton = () => {
  return pendingButtonId;
};
