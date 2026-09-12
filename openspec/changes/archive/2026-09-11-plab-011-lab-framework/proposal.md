# PLAB-011: Performance Lab Framework

## Summary

Implement the reusable static framework used by every Performance Lab module.

## Motivation

Current prototype interactions are hand-written and not reusable. A shared framework is required before more labs are added.

## Scope

- Lab shell
- Metadata loader
- State/reducer contract
- Shared controls and timeline
- Theory panel
- Java/Rust code tabs
- Benchmark disclosure
- Quiz and investigation task
- Sources panel
- Reset/share/deep-link behaviour
- Accessibility announcer
- Registry and filtering

## Non-goals

- SPA migration
- Runtime code execution
- Accounts or persistence
- Building every future lab

## Success criteria

- False Sharing uses no private one-off infrastructure.
- A second lab reuses at least 80% of the framework surface.
- State transitions are independently testable.
- Deep links restore named scenarios.
