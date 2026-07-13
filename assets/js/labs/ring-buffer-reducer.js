// Pure state/reducer for the SPSC ring-buffer prototype demo.
export const ringBufferCapacity = 8;
export const ringBufferInitialState = { head: 0, tail: 0, size: 0, lastResult: null };

export function ringBufferReducer(state, event) {
  switch (event.type) {
    case "PRODUCE":
      if (state.size >= ringBufferCapacity) return { ...state, lastResult: "full" };
      return { head: (state.head + 1) % ringBufferCapacity, tail: state.tail, size: state.size + 1, lastResult: "produced" };
    case "CONSUME":
      if (state.size <= 0) return { ...state, lastResult: "empty" };
      return { head: state.head, tail: (state.tail + 1) % ringBufferCapacity, size: state.size - 1, lastResult: "consumed" };
    case "RESET": return ringBufferInitialState;
    default: return state;
  }
}

export function announceRingBuffer(state) {
  switch (state.lastResult) {
    case "produced": return `Produced. Buffer size ${state.size} of ${ringBufferCapacity}.`;
    case "consumed": return `Consumed. Buffer size ${state.size} of ${ringBufferCapacity}.`;
    case "full": return "Buffer full. Produce ignored.";
    case "empty": return "Buffer empty. Consume ignored.";
    default: return null;
  }
}
