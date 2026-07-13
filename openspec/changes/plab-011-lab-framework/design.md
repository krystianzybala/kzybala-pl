# Design

## Core interface

```javascript
createLabDefinition({ metadata, initialState, reducer, render, events, scenarios })
mountLab(root, definition)
```

## Reducer

```javascript
function reducer(state, event) {
  switch (event.type) {
    case "STEP": return nextState;
    case "RESET": return initialState;
    default: return state;
  }
}
```

## URL state

Only stable state belongs in the URL:

```text
/lab/false-sharing/?scenario=shared-line&step=3
```

## Shared components

- LabHeader
- LearningObjective
- PrerequisiteList
- TheorySection
- InteractiveStage
- ScenarioSelector
- StepControls
- StateInspector
- CodeTabs
- BenchmarkDisclosure
- Quiz
- TradeOffs
- Sources
- LabNavigation

## Progressive enhancement

Theory, code, limitations and sources remain present without JavaScript.

## Browser support

Latest two Chromium, Firefox and Safari versions, plus mobile Safari and Chromium.
