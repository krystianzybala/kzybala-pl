# Design

## Architecture

```text
content/labs/<lab-id>/
  lab.json
  theory.md
  java.md
  rust.md
  benchmark.md
  sources.md

assets/js/core/
assets/js/labs/
assets/data/labs-index.json
lab/<lab-id>/index.html
```

## Runtime model

Every interactive lab uses:

```text
initialState
event
reducer(state, event) -> nextState
render(nextState)
```

## Metadata

```json
{
  "id": "false-sharing",
  "title": "False Sharing",
  "status": "stable",
  "level": 2,
  "difficulty": "intermediate",
  "durationMinutes": 20,
  "topics": ["cpu-cache", "coherence", "concurrency"],
  "prerequisites": ["cache-lines"],
  "unlocks": ["mesi", "cache-aware-layout"],
  "languages": ["java", "rust"],
  "interactive": true,
  "benchmark": true,
  "conceptualModel": true
}
```

## Learning graph

Directed and acyclic. Invalid references and cycles fail CI.

## Accessibility

- Keyboard accessible controls
- Visible focus
- Semantic headings
- Textual state descriptions
- Reduced motion
- No colour-only meaning
- Screen-reader announcements

## Performance budgets

- HTML <= 80 KB uncompressed per lab
- Shared CSS <= 100 KB
- Shared JS <= 120 KB
- Per-lab JS <= 80 KB
- No blocking remote fonts
- No unreviewed third-party runtime

## Testing

- Metadata schema
- Duplicate IDs
- Broken prerequisites
- Cycle detection
- HTML and links
- Accessibility smoke tests
- Reducer unit tests
- Visual snapshots
- GitHub Pages smoke test
