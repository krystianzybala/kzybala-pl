// Pure state/reducer for the false-sharing prototype demo — no DOM access,
// so it's directly unit-testable (spec.md "Pure transitions").
export const falseSharingInitialState = { writeSide: 0 };

export function falseSharingReducer(state, event) {
  switch (event.type) {
    case "COHERENCE_STEP": return { writeSide: 1 - state.writeSide };
    case "RESET": return falseSharingInitialState;
    default: return state;
  }
}

export function announceFalseSharing(state) {
  const other = 1 - state.writeSide;
  return `CPU ${state.writeSide} cache line Modified, CPU ${other} cache line Invalid.`;
}
