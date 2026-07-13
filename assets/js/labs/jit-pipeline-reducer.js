// Pure state/reducer for the JIT-pipeline prototype demo.
export const jitStageLabels = ["Java source", "Bytecode", "Interpreter", "C1 + profiling", "C2 machine code"];
export const jitPipelineInitialState = { stageIndex: 0 };

export function jitPipelineReducer(state, event) {
  switch (event.type) {
    case "JIT_STEP": return { stageIndex: (state.stageIndex + 1) % jitStageLabels.length };
    case "RESET": return jitPipelineInitialState;
    default: return state;
  }
}

export function announceJitPipeline(state) {
  return `Compilation stage: ${jitStageLabels[state.stageIndex]}.`;
}
